package com.local.epubsplitter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FILE = 1001;
    private static final int REQ_PICK_FOLDER = 1002;
    private static final String PREFS = "epub_splitter_prefs";

    private Uri selectedFileUri;
    private Uri selectedFolderUri;
    private String selectedFileName = "Book.epub";

    private TextView tvSelectedFile;
    private TextView tvSelectedFolder;
    private EditText etChaptersPerPart;
    private CheckBox cbIncremental;
    private Button btnSplit;
    private ProgressBar progressBar;
    private TextView tvLog;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnPickFile = findViewById(R.id.btnPickFile);
        Button btnPickFolder = findViewById(R.id.btnPickFolder);
        tvSelectedFile = findViewById(R.id.tvSelectedFile);
        tvSelectedFolder = findViewById(R.id.tvSelectedFolder);
        etChaptersPerPart = findViewById(R.id.etChaptersPerPart);
        cbIncremental = findViewById(R.id.cbIncremental);
        btnSplit = findViewById(R.id.btnSplit);
        progressBar = findViewById(R.id.progressBar);
        tvLog = findViewById(R.id.tvLog);

        restoreSavedSelections();

        btnPickFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/epub+zip",
                    "application/octet-stream"
            });
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_PICK_FILE);
        });

        btnPickFolder.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_PICK_FOLDER);
        });

        btnSplit.setOnClickListener(v -> startSplitting());
    }

    private void restoreSavedSelections() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String fileUriStr = prefs.getString("file_uri", null);
        String folderUriStr = prefs.getString("folder_uri", null);
        int savedChapters = prefs.getInt("chapters_per_part", 350);
        etChaptersPerPart.setText(String.valueOf(savedChapters));

        if (fileUriStr != null) {
            try {
                Uri uri = Uri.parse(fileUriStr);
                DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
                if (doc != null && doc.exists()) {
                    selectedFileUri = uri;
                    selectedFileName = doc.getName() != null ? doc.getName() : "Book.epub";
                    tvSelectedFile.setText("Selected: " + selectedFileName);
                }
            } catch (Exception ignored) { }
        }

        if (folderUriStr != null) {
            try {
                Uri uri = Uri.parse(folderUriStr);
                DocumentFile dir = DocumentFile.fromTreeUri(this, uri);
                if (dir != null && dir.exists() && dir.canWrite()) {
                    selectedFolderUri = uri;
                    String folderName = dir.getName() != null ? dir.getName() : uri.getLastPathSegment();
                    tvSelectedFolder.setText("Folder: " + folderName);
                }
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();

        if (requestCode == REQ_PICK_FILE) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }

            selectedFileUri = uri;
            DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
            selectedFileName = (doc != null && doc.getName() != null) ? doc.getName() : "Book.epub";
            tvSelectedFile.setText("Selected: " + selectedFileName);
            editor.putString("file_uri", uri.toString()).apply();

        } else if (requestCode == REQ_PICK_FOLDER) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) { }

            selectedFolderUri = uri;
            DocumentFile dir = DocumentFile.fromTreeUri(this, uri);
            String folderName = (dir != null && dir.getName() != null) ? dir.getName() : uri.getLastPathSegment();
            tvSelectedFolder.setText("Folder: " + folderName);
            editor.putString("folder_uri", uri.toString()).apply();
        }
    }

    private void startSplitting() {
        if (selectedFileUri == null) {
            Toast.makeText(this, "Please choose an EPUB file first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedFolderUri == null) {
            Toast.makeText(this, "Please choose an output folder first.", Toast.LENGTH_SHORT).show();
            return;
        }

        int chaptersPerPart;
        try {
            chaptersPerPart = Integer.parseInt(etChaptersPerPart.getText().toString().trim());
            if (chaptersPerPart <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Enter a valid positive number of chapters.", Toast.LENGTH_SHORT).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt("chapters_per_part", chaptersPerPart)
                .apply();

        final Uri fileUri = selectedFileUri;
        final Uri folderUri = selectedFolderUri;
        final String fileName = selectedFileName;
        final int perPart = chaptersPerPart;
        final boolean incremental = cbIncremental.isChecked();

        btnSplit.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        tvLog.setText("Reading EPUB...\n");
        // Keep the screen on so Android doesn't suspend the job halfway
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        executor.execute(() -> {
            String error = null;
            try {
                EpubSplitter.split(
                        this,
                        fileUri,
                        fileName,
                        folderUri,
                        perPart,
                        incremental,
                        new EpubSplitter.Callback() {
                            @Override
                            public void onLog(String message) {
                                runOnUiThread(() -> tvLog.append(message + "\n"));
                            }

                            @Override
                            public void onProgress(int current, int total) {
                                runOnUiThread(() -> {
                                    progressBar.setIndeterminate(false);
                                    progressBar.setMax(total);
                                    progressBar.setProgress(current);
                                });
                            }
                        }
                );
            } catch (Throwable t) { // includes OutOfMemoryError
                String msg = t.getMessage();
                error = (msg != null && !msg.isEmpty()) ? msg : t.toString();
            }

            final String err = error;
            runOnUiThread(() -> {
                if (err == null) {
                    tvLog.append("\nDone! Original file was not modified.");
                } else {
                    tvLog.append("\nError: " + err);
                }
                btnSplit.setEnabled(true);
                progressBar.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // shutdown() (not shutdownNow) lets a running split finish
        executor.shutdown();
    }
}
