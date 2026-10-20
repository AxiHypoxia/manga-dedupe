package com.example.mangadedupe;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 两阶段扫描（针对"文件夹多、文件大"的库优化）：
 *
 *   阶段 1 scanTree()  只查目录/文件/页数 —— 不打开任何图片，只走 SAF query
 *   阶段 2 enrich()    只对"疑似重复组"的成员打开首图一次，从文件头解析宽高 + 算哈希
 *
 * 和旧版相比：宽高不再靠 BitmapFactory 解码（每次都要走解码器），
 * 而是直接读文件头（ImgHead），每目录的 IO 次数从 6 次降到 1 次；
 * 且只有候选组的成员才需要这 1 次。
 */
public final class SafScanner {

    private static final String TAG = "SafScanner";
    public static final int PHASE_DIRS = 1;
    public static final int PHASE_IMAGES = 2;

    public interface Progress {
        void onProgress(int phase, int done, int total, String detail);
    }

    private final ContentResolver cr;
    private final Uri treeUri;
    private final int maxDepth;
    private final int candidateDepth;
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    private int dirCount;

    public SafScanner(Context ctx, Uri treeUri, int candidateDepth) {
        this.cr = ctx.getContentResolver();
        this.treeUri = treeUri;
        this.candidateDepth = candidateDepth;
        this.maxDepth = Math.max(6, candidateDepth + 4);
    }

    public void cancel() {
        cancel.set(true);
    }

    public boolean isCancelled() {
        return cancel.get();
    }

    // ================= 阶段 1：只查目录 =================

    /** 返回候选层（candidateDepth）目录，页数>0 且未标记。 */
    public List<DirInfo> scanTree(Progress cb) {
        Map<String, DirInfo> all = new HashMap<String, DirInfo>();
        dirCount = 0;
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            Log.w(TAG, "getTreeDocumentId failed", e);
            return new ArrayList<DirInfo>();
        }
        walk(rootId, "", 0, all, cb);

        // 自底向上聚合
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

    private DirInfo walk(String docId, String rel, int depth,
                         Map<String, DirInfo> out, Progress cb) {
        DirInfo d = new DirInfo();
        d.path = rel;
        d.rel = rel;
        d.name = rel.isEmpty() ? "/" : lastName(rel);
        d.depth = depth;
        if (!rel.isEmpty()) d.nums.addAll(Core.volNumbers(d.name));
        out.put(rel, d);

        dirCount++;
        if (cb != null && (dirCount % 20 == 0 || depth == 0)) {
            cb.onProgress(PHASE_DIRS, dirCount, 0, rel);
        }
        if (cancel.get() || depth >= maxDepth) return d;

        List<String[]> subDirs = new ArrayList<String[]>();
        List<String[]> images = new ArrayList<String[]>();   // {docId, name}
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            while (c != null && c.moveToNext()) {
                String id = c.getString(0);
                String nm = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (nm == null || nm.startsWith(".") || nm.contains(".l2s")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    subDirs.add(new String[]{id, nm});
                    continue;
                }
                d.files++;
                d.bytes += size;
                if (Core.isImageName(nm)) {
                    d.images++;
                    d.pages++;
                    images.add(new String[]{id, nm, String.valueOf(size)});
                } else if (Core.isArchiveName(nm)) {
                    // TODO(轮次 B)：缓存到私有目录后 ZipFile 精确数页
                    d.pages += 1;
                    d.nums.addAll(Core.volNumbers(nm));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query failed: " + rel, e);
        } finally {
            if (c != null) c.close();
        }

        // 只在候选层挑"首图"（按文件名排序取第一张），但不打开它
        if (depth == candidateDepth && !images.isEmpty()) {
            Collections.sort(images, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    return a[1].compareTo(b[1]);
                }
            });
            String[] first = images.get(0);
            d.firstImage = documentUri(first[0]).toString();
            try {
                d.firstImageSize = Long.parseLong(first[2]);
            } catch (NumberFormatException ignored) {
            }
        }

        for (String[] sd : subDirs) {
            if (cancel.get()) break;
            String childRel = rel.isEmpty() ? sd[1] : rel + "/" + sd[1];
            walk(sd[0], childRel, depth + 1, out, cb);
        }
        return d;
    }

    // ================= 阶段 2：只分析首图 =================

    /** 对给定目录（通常是疑似重复组里的成员）打开首图一次，解析宽高 + 首图哈希。 */
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
            Uri u = Uri.parse(d.firstImage);
            in = cr.openInputStream(u);
            if (in == null) return;
            byte[] buf = new byte[64 * 1024];
            int n = 0;
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            if (n <= 0) return;

            // 宽高：直接解析文件头（微秒级），失败才退回解码器
            int[] wh = ImgHead.size(buf, n);
            if (wh[0] > 0 && wh[1] > 0) {
                d.sumW = wh[0];
                d.sumH = wh[1];
                d.sampleN = 1;
            } else {
                slowBounds(d, u);
            }

            // 哈希：大小 + 前 64KB
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(buf, 0, n);
            md.update(String.valueOf(d.firstImageSize).getBytes("UTF-8"));
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            d.firstHash = sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "enrich failed: " + d.name, e);
        } finally {
            closeQuietly(in);
        }
    }

    private void slowBounds(DirInfo d, Uri u) {
        InputStream in = null;
        try {
            in = cr.openInputStream(u);
            if (in == null) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            if (o.outWidth > 0 && o.outHeight > 0) {
                d.sumW = o.outWidth;
                d.sumH = o.outHeight;
                d.sampleN = 1;
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(in);
        }
    }

    // ================= 小工具 =================

    private Uri documentUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
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
