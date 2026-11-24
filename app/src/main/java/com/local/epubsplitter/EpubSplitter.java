package com.local.epubsplitter;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class EpubSplitter {

    public interface Callback {
        void onLog(String message);
        void onProgress(int current, int total);
    }

    private static class ManifestItem {
        final String id;
        final String mediaType;
        final String properties;
        final String zipPath; // decoded, normalized path inside the source zip

        ManifestItem(String id, String mediaType, String properties, String zipPath) {
            this.id = id;
            this.mediaType = mediaType;
            this.properties = properties;
            this.zipPath = zipPath;
        }
    }

    // Whole-stem match, so "index_split_000" (Calibre chapters) is NOT skipped
    private static final Pattern SKIP_NAMES = Pattern.compile(
            "^(cover\\d*|nav|toc|tableofcontents|title|title[-_ ]?page|titlepage|copyright|about|info|index)$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern H_TAG = Pattern.compile("<h[1-3][^>]*>(.*?)</h[1-3]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEAD_BLOCK = Pattern.compile("<head[^>]*>.*?</head>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REF_ATTR = Pattern.compile("(?:src|href|poster)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*[\"']?([^\"')]+?)[\"']?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile("@import\\s+[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    public static void split(
            Context context,
            Uri inputUri,
            String inputFileName,
            Uri outputTreeUri,
            int chaptersPerPart,
            boolean incremental,
            Callback callback
    ) throws Exception {

        DocumentFile outDir = DocumentFile.fromTreeUri(context, outputTreeUri);
        if (outDir == null || !outDir.canWrite()) {
            throw new IllegalStateException("Cannot write to selected folder.");
        }

        String baseTitle = inputFileName.replaceFirst("(?i)\\.epub$", "");
        File tempEpub = new File(context.getCacheDir(), "temp_source.epub");

        try {
            try (InputStream in = context.getContentResolver().openInputStream(inputUri);
                 FileOutputStream out = new FileOutputStream(tempEpub)) {
                if (in == null) throw new IllegalStateException("Unable to open EPUB file.");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }

            try (ZipFile zipFile = new ZipFile(tempEpub)) {
                ZipEntry containerEntry = zipFile.getEntry("META-INF/container.xml");
                if (containerEntry == null) {
                    throw new IllegalStateException("Invalid EPUB: missing META-INF/container.xml");
                }

                Document containerDoc;
                try (InputStream is = zipFile.getInputStream(containerEntry)) {
                    containerDoc = parseXml(is);
                }
                NodeList rootfiles = containerDoc.getElementsByTagNameNS("*", "rootfile");
                if (rootfiles.getLength() == 0) {
                    throw new IllegalStateException("Invalid EPUB: missing rootfile.");
                }
                String opfPath = ((Element) rootfiles.item(0)).getAttribute("full-path");
                int slashIdx = opfPath.lastIndexOf('/');
                String opfDir = slashIdx >= 0 ? opfPath.substring(0, slashIdx) : "";

                ZipEntry opfEntry = zipFile.getEntry(opfPath);
                if (opfEntry == null) {
                    throw new IllegalStateException("Invalid EPUB: missing OPF file.");
                }
                Document opfDoc;
                try (InputStream is = zipFile.getInputStream(opfEntry)) {
                    opfDoc = parseXml(is);
                }

                String language = firstText(opfDoc, "language", "en");
                String creator = firstText(opfDoc, "creator", "");

                Map<String, ManifestItem> byId = new HashMap<>();
                Map<String, ManifestItem> byPath = new HashMap<>();

                NodeList items = opfDoc.getElementsByTagNameNS("*", "item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element el = (Element) items.item(i);
                    String href = el.getAttribute("href");
                    if (href.isEmpty()) continue;
                    String zipPath = normalizeZipPath(opfDir, decodeHref(href));
                    ManifestItem mi = new ManifestItem(
                            el.getAttribute("id"),
                            el.getAttribute("media-type"),
                            el.getAttribute("properties"),
                            zipPath);
                    byId.put(mi.id, mi);
                    byPath.put(zipPath, mi);
                }

                List<ManifestItem> chapters = new ArrayList<>();
                NodeList itemrefs = opfDoc.getElementsByTagNameNS("*", "itemref");
                for (int i = 0; i < itemrefs.getLength(); i++) {
                    Element el = (Element) itemrefs.item(i);
                    ManifestItem mi = byId.get(el.getAttribute("idref"));
                    if (mi == null) continue;
                    if (!isHtml(mi.mediaType)) continue;
                    if (hasToken(mi.properties, "nav")) continue;

                    String fileNameOnly = mi.zipPath.substring(mi.zipPath.lastIndexOf('/') + 1);
                    String stem = fileNameOnly.replaceFirst("\\.[^.]+$", "");
                    if (SKIP_NAMES.matcher(stem).find()) continue;

                    if (zipFile.getEntry(mi.zipPath) != null) {
                        chapters.add(mi);
                    }
                }

                int totalChapters = chapters.size();
                if (totalChapters == 0) {
                    throw new IllegalStateException("No chapters found in this EPUB.");
                }
                callback.onLog("Found " + totalChapters + " chapters.");

                int totalParts = (int) Math.ceil((double) totalChapters / chaptersPerPart);
                DocumentFile[] existingFiles = outDir.listFiles();

                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                String modified = iso.format(new Date());

                for (int partIdx = 1; partIdx <= totalParts; partIdx++) {
                    callback.onProgress(partIdx - 1, totalParts);

                    int startIdx = (partIdx - 1) * chaptersPerPart;
                    int endIdx = Math.min(startIdx + chaptersPerPart, totalChapters);

                    String partTitle = String.format(
                            Locale.US,
                            "%s - Part %02d (Chapters %d-%d)",
                            baseTitle, partIdx, startIdx + 1, endIdx
                    );
                    String outFileName = partTitle + ".epub";

                    if (incremental && fileExists(existingFiles, outFileName)) {
                        callback.onLog("Skipped: " + outFileName);
                        continue;
                    }

                    // Old versions of this part (any chapter range). A same-name file must be
                    // removed first (createFile would otherwise make "name (1).epub"); other
                    // names are removed only AFTER the new file is written successfully.
                    String partPrefix = String.format(Locale.US, "%s - Part %02d (", baseTitle, partIdx);
                    boolean replacedOld = false;
                    List<DocumentFile> staleFiles = new ArrayList<>();
                    if (existingFiles != null) {
                        for (DocumentFile f : existingFiles) {
                            String name = f.getName();
                            if (name != null && name.startsWith(partPrefix) && name.endsWith(".epub")) {
                                replacedOld = true;
                                if (name.equals(outFileName)) f.delete();
                                else staleFiles.add(f);
                            }
                        }
                    }

                    DocumentFile outFile = outDir.createFile("application/epub+zip", outFileName);
                    if (outFile == null) {
                        throw new IllegalStateException("Failed to create " + outFileName);
                    }

                    long partWords = 0;
                    boolean ok = false;
                    try {
                        try (OutputStream rawOut = context.getContentResolver().openOutputStream(outFile.getUri(), "wt")) {
                            if (rawOut == null) {
                                throw new IllegalStateException("Cannot open " + outFileName + " for writing.");
                            }
                            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut, 65536))) {
                                partWords = writePart(zos, zipFile, chapters, startIdx, endIdx,
                                        byPath, opfDir, partTitle, baseTitle, partIdx,
                                        language, creator, modified);
                            }
                        }
                        ok = true;
                    } finally {
                        if (!ok) outFile.delete(); // never leave a half-written EPUB behind
                    }

                    for (DocumentFile f : staleFiles) f.delete();

                    String action = replacedOld ? "Updated: " : "Created: ";
                    callback.onLog(String.format(Locale.US, "%s%s (~%,d words)", action, outFileName, partWords));
                }

                callback.onProgress(totalParts, totalParts);
            }
        } finally {
            if (tempEpub.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempEpub.delete();
            }
        }
    }

    /** Writes one complete EPUB part. Returns the approximate word count. */
    private static long writePart(
            ZipOutputStream zos,
            ZipFile zipFile,
            List<ManifestItem> chapters,
            int startIdx,
            int endIdx,
            Map<String, ManifestItem> byPath,
            String opfDir,
            String partTitle,
            String baseTitle,
            int partIdx,
            String language,
            String creator,
            String modified
    ) throws Exception {

        long partWords = 0;
        Set<String> written = new HashSet<>();
        StringBuilder navLinks = new StringBuilder();
        StringBuilder manifestXml = new StringBuilder();
        StringBuilder spineXml = new StringBuilder();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();

        // mimetype: first entry, STORED, no extra field
        byte[] mimeBytes = "application/epub+zip".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(mimeBytes);
        ZipEntry mimeEntry = new ZipEntry("mimetype");
        mimeEntry.setMethod(ZipEntry.STORED);
        mimeEntry.setSize(mimeBytes.length);
        mimeEntry.setCompressedSize(mimeBytes.length);
        mimeEntry.setCrc(crc.getValue());
        zos.putNextEntry(mimeEntry);
        zos.write(mimeBytes);
        zos.closeEntry();
        written.add("mimetype");

        writeZipText(zos, written, "META-INF/container.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                "  <rootfiles>\n" +
                "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                "  </rootfiles>\n" +
                "</container>\n"
        );

        // Chapters
        for (int idx = startIdx; idx < endIdx; idx++) {
            ManifestItem ch = chapters.get(idx);
            int chNum = idx + 1;
            ZipEntry ze = zipFile.getEntry(ch.zipPath);
            if (ze == null) continue;

            byte[] chBytes;
            try (InputStream is = zipFile.getInputStream(ze)) {
                chBytes = readAllBytes(is);
            }
            String chText = new String(chBytes, StandardCharsets.UTF_8);
            partWords += countWords(chText);

            String rel = relToOpf(ch.zipPath, opfDir);
            String internalPath = normalizeZipPath("OEBPS", rel);
            if (written.add(internalPath)) {
                writeZipBytes(zos, internalPath, chBytes);
            }

            String id = "ch" + chNum;
            String chTitle = extractTitle(chText, "Chapter " + chNum);
            navLinks.append("      <li><a href=\"").append(escapeXml(encodePath(rel)))
                    .append("\">").append(escapeXml(chTitle)).append("</a></li>\n");
            appendItem(manifestXml, id, rel, ch.mediaType, ch.properties);
            spineXml.append("    <itemref idref=\"").append(id).append("\"/>\n");

            // Find images, fonts and CSS this chapter uses
            collectRefs(chText, ch.zipPath, byPath, zipFile, queue, queued, REF_ATTR);
        }

        // Only the resources actually used by this part's chapters (not the whole book)
        int resCount = 0;
        while (!queue.isEmpty()) {
            String zp = queue.removeFirst();
            ManifestItem mi = byPath.get(zp);
            ZipEntry ze = zipFile.getEntry(zp);
            if (mi == null || ze == null) continue;

            String rel = relToOpf(zp, opfDir);
            String internalPath = normalizeZipPath("OEBPS", rel);
            if (!written.add(internalPath)) continue;

            if ("text/css".equals(mi.mediaType)) {
                byte[] cssBytes;
                try (InputStream is = zipFile.getInputStream(ze)) {
                    cssBytes = readAllBytes(is);
                }
                writeZipBytes(zos, internalPath, cssBytes);
                collectRefs(new String(cssBytes, StandardCharsets.UTF_8), zp, byPath, zipFile,
                        queue, queued, CSS_URL, CSS_IMPORT);
            } else {
                zos.putNextEntry(new ZipEntry(internalPath));
                try (InputStream is = zipFile.getInputStream(ze)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            appendItem(manifestXml, "res" + (++resCount), rel, mi.mediaType, mi.properties);
        }

        String navXhtml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE html>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n" +
                "<head><title>Table of Contents</title></head>\n" +
                "<body>\n" +
                "  <nav epub:type=\"toc\" id=\"toc\">\n" +
                "    <h1>" + escapeXml(partTitle) + "</h1>\n" +
                "    <ol>\n" + navLinks + "    </ol>\n" +
                "  </nav>\n" +
                "</body>\n" +
                "</html>\n";
        writeZipText(zos, written, "OEBPS/nav.xhtml", navXhtml);

        String uuid = UUID.nameUUIDFromBytes(
                (baseTitle + "|part|" + partIdx).getBytes(StandardCharsets.UTF_8)).toString();

        String contentOpf =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"BookId\">\n" +
                "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                "    <dc:identifier id=\"BookId\">urn:uuid:" + uuid + "</dc:identifier>\n" +
                "    <dc:title>" + escapeXml(partTitle) + "</dc:title>\n" +
                (creator.isEmpty() ? "" : "    <dc:creator>" + escapeXml(creator) + "</dc:creator>\n") +
                "    <dc:language>" + escapeXml(language) + "</dc:language>\n" +
                "    <meta property=\"dcterms:modified\">" + modified + "</meta>\n" +
                "  </metadata>\n" +
                "  <manifest>\n" +
                "    <item id=\"toc-nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n" +
                manifestXml +
                "  </manifest>\n" +
                "  <spine>\n" +
                spineXml +
                "  </spine>\n" +
                "</package>\n";
        writeZipText(zos, written, "OEBPS/content.opf", contentOpf);

        return partWords;
    }

    private static void collectRefs(
            String text,
            String ownerZipPath,
            Map<String, ManifestItem> byPath,
            ZipFile zip,
            Deque<String> queue,
            Set<String> queued,
            Pattern... patterns
    ) {
        int s = ownerZipPath.lastIndexOf('/');
        String dir = s >= 0 ? ownerZipPath.substring(0, s) : "";
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String ref = m.group(1).trim();
                if (ref.isEmpty() || SCHEME.matcher(ref).find()) continue; // http:, data:, mailto: ...
                String zp = normalizeZipPath(dir, decodeHref(ref));
                ManifestItem mi = byPath.get(zp);
                if (mi == null) continue;
                if (isHtml(mi.mediaType) || mi.mediaType.contains("ncx")) continue; // links to other chapters
                if (zip.getEntry(zp) == null) continue;
                if (queued.add(zp)) queue.addLast(zp);
            }
        }
    }

    private static void appendItem(StringBuilder sb, String id, String rel, String type, String props) {
        sb.append("    <item id=\"").append(escapeXml(id))
                .append("\" href=\"").append(escapeXml(encodePath(rel)))
                .append("\" media-type=\"").append(escapeXml(type)).append("\"");
        String clean = cleanProps(props);
        if (!clean.isEmpty()) sb.append(" properties=\"").append(escapeXml(clean)).append("\"");
        sb.append("/>\n");
    }

    private static String cleanProps(String props) {
        if (props == null || props.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : props.trim().split("\\s+")) {
            if (t.equals("nav") || t.equals("cover-image")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static boolean hasToken(String props, String token) {
        if (props == null) return false;
        for (String t : props.trim().split("\\s+")) {
            if (t.equals(token)) return true;
        }
        return false;
    }

    private static boolean isHtml(String mediaType) {
        return mediaType != null && mediaType.toLowerCase(Locale.US).contains("html");
    }

    /** Path of a zip entry relative to the OPF folder, so links between files keep working. */
    private static String relToOpf(String zipPath, String opfDir) {
        if (opfDir.isEmpty()) return zipPath;
        if (zipPath.startsWith(opfDir + "/")) return zipPath.substring(opfDir.length() + 1);
        return "external/" + zipPath;
    }

    private static String firstText(Document doc, String localName, String fallback) {
        NodeList nl = doc.getElementsByTagNameNS("*", localName);
        if (nl.getLength() > 0) {
            String t = nl.item(0).getTextContent();
            if (t != null && !t.trim().isEmpty()) return t.trim();
        }
        return fallback;
    }

    private static boolean fileExists(DocumentFile[] files, String targetName) {
        if (files == null) return false;
        for (DocumentFile f : files) {
            // length() > 0 so an empty/broken file from a crashed run is rebuilt
            if (targetName.equals(f.getName()) && f.length() > 0) return true;
        }
        return false;
    }

    private static Document parseXml(InputStream is) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(is);
    }

    private static String decodeHref(String href) {
        String s = href;
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        try {
            // '+' is a literal plus in a path, but URLDecoder would turn it into a space
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String encodePath(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String seg : path.split("/", -1)) {
            if (!first) sb.append('/');
            first = false;
            sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String normalizeZipPath(String baseDir, String relPath) {
        String combined = (baseDir == null || baseDir.isEmpty()) ? relPath : baseDir + "/" + relPath;
        Deque<String> stack = new ArrayDeque<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(part);
            }
        }
        return String.join("/", stack);
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int n;
        while ((n = is.read(data)) != -1) {
            buffer.write(data, 0, n);
        }
        return buffer.toByteArray();
    }

    private static void writeZipText(ZipOutputStream zos, Set<String> written, String path, String content) throws Exception {
        if (written.add(path)) {
            writeZipBytes(zos, path, content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeZipBytes(ZipOutputStream zos, String path, byte[] bytes) throws Exception {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static String extractTitle(String html, String fallback) {
        for (Pattern p : new Pattern[]{H_TAG, TITLE_TAG}) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                String clean = m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                // Titles come from XML, so decode entities first; escapeXml re-encodes them once
                clean = unescapeEntities(clean).trim();
                if (!clean.isEmpty()) return clean;
            }
        }
        return fallback;
    }

    private static String unescapeEntities(String s) {
        Matcher m = NUM_ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep;
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                rep = new String(Character.toChars(cp));
            } catch (Exception e) {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString()
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static long countWords(String html) {
        String text = HEAD_BLOCK.matcher(html).replaceAll(" ").replaceAll("<[^>]+>", " ").trim();
        if (text.isEmpty()) return 0;
        return text.split("\\s+").length;
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
