package com.example.mangadedupe;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 直接文件系统扫描器（快速模式）。
 *
 * 相比 SAF 版：
 *  · 不经过 DocumentsUI / ContentProvider，一个目录一次 listFiles()
 *  · .cbz/.zip 用 ZipFile 读中央目录精确数页（不需要解压）
 *  · 首图用 FileInputStream 读 64KB 头，配合 ImgHead 解析宽高
 *
 * 仍然是两阶段：scanTree() 只列目录（零图片 IO）→ enrich() 只分析候选组成员。
 */
public final class FileScanner {

    public static final int PHASE_DIRS = 1;
    public static final int PHASE_IMAGES = 2;

    public interface Progress {
        void onProgress(int phase, int done, int total, String detail);
    }

    private final File root;
    private final int candidateDepth;
    private final int maxDepth;
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    private int dirCount;

    public FileScanner(File root, int candidateDepth) {
        this.root = root;
        this.candidateDepth = candidateDepth;
        this.maxDepth = Math.max(6, candidateDepth + 4);
    }

    public void cancel() {
        cancel.set(true);
    }

    public boolean isCancelled() {
        return cancel.get();
    }

    // ================= 阶段 1：只列目录 =================

    public List<DirInfo> scanTree(Progress cb) {
        Map<String, DirInfo> all = new HashMap<String, DirInfo>();
        dirCount = 0;
        walk(root, "", 0, all, cb);

        List<DirInfo> list = new ArrayList<DirInfo>(all.values());
        Collections.sort(list, new Comparator<DirInfo>() {
            @Override
            public int compare(DirInfo a, DirInfo b) {
                return Integer.compare(b.depth, a.depth);
            }
        });
        for (DirInfo d : list) {
            if (d.depth == 0) continue;
            DirInfo p = all.get(parentOf(d.rel));
            if (p == null) continue;
            p.files += d.files;
            p.images += d.images;
            p.pages += d.pages;
            p.bytes += d.bytes;
            p.nums.addAll(d.nums);
        }

        List<DirInfo> units = new ArrayList<DirInfo>();
        for (DirInfo d : list) {
            if (d.depth != candidateDepth) continue;
            if (d.pages <= 0) continue;
            if (Core.isMarked(d.name)) continue;
            units.add(d);
        }
        return units;
    }

    private void walk(File dir, String rel, int depth, Map<String, DirInfo> out, Progress cb) {
        DirInfo d = new DirInfo();
        d.path = dir.getAbsolutePath();
        d.rel = rel;
        d.name = rel.isEmpty() ? dir.getName() : lastName(rel);
        d.depth = depth;
        if (!rel.isEmpty()) d.nums.addAll(Core.volNumbers(d.name));
        out.put(rel, d);

        dirCount++;
        if (cb != null && (dirCount % 20 == 0 || depth == 0)) {
            cb.onProgress(PHASE_DIRS, dirCount, 0, rel);
        }
        if (cancel.get() || depth >= maxDepth) return;

        File[] children = dir.listFiles();
        if (children == null) return;

        List<File> subDirs = new ArrayList<File>();
        List<File> images = new ArrayList<File>();

        for (File f : children) {
            String nm = f.getName();
            if (nm.startsWith(".") || nm.contains(".l2s")) continue;
            if (f.isDirectory()) {
                subDirs.add(f);
                continue;
            }
            long size = f.length();
            d.files++;
            d.bytes += size;
            if (Core.isImageName(nm)) {
                d.images++;
                d.pages++;
                images.add(f);
            } else if (Core.isArchiveName(nm)) {
                d.pages += zipPages(f);
                d.nums.addAll(Core.volNumbers(nm));
            }
        }

        // 候选层挑首图（不打开）
        if (depth == candidateDepth && !images.isEmpty()) {
            Collections.sort(images, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            File first = images.get(0);
            d.firstImage = first.getAbsolutePath();
            d.firstImageSize = first.length();
        }

        Collections.sort(subDirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File sd : subDirs) {
            if (cancel.get()) break;
            String childRel = rel.isEmpty() ? sd.getName() : rel + "/" + sd.getName();
            walk(sd, childRel, depth + 1, out, cb);
        }
    }

    /** ZipFile 只读中央目录，不解压，很快 */
    private int zipPages(File f) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            int n = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String nm = e.getName().toLowerCase(Locale.ROOT);
                if (nm.endsWith("/")) continue;
                if (Core.isImageName(nm)) n++;
            }
            return n > 0 ? n : 1;
        } catch (Exception e) {
            return 1;
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ================= 阶段 2：分析首图 =================

    public void enrich(List<DirInfo> units, Progress cb) {
        int total = units.size();
        int done = 0;
        for (DirInfo d : units) {
            if (cancel.get()) return;
            if (d.firstImage != null
                    && (d.sampleN == 0 || d.firstHash == null || d.firstHash.length() == 0)) {
                enrichOne(d);
            }
            done++;
            if (cb != null && (done % 3 == 0 || done == total)) {
                cb.onProgress(PHASE_IMAGES, done, total, d.name);
            }
        }
    }

    private void enrichOne(DirInfo d) {
        InputStream in = null;
        try {
            File f = new File(d.firstImage);
            in = new FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n = 0;
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            if (n <= 0) return;

            int[] wh = ImgHead.size(buf, n);
            if (wh[0] > 0 && wh[1] > 0) {
                d.sumW = wh[0];
                d.sumH = wh[1];
                d.sampleN = 1;
            }

            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(buf, 0, n);
            md.update(String.valueOf(d.firstImageSize).getBytes("UTF-8"));
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            d.firstHash = sb.toString();
        } catch (Exception ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static String parentOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    static String lastName(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }
}
