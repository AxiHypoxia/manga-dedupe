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

/**
 * 用 SAF 扫描一个目录树，产出 Core 需要的 DirInfo 列表。
 *
 * 性能取舍（重要）：
 *  · 文件数/体积/图片数/页数：每层一次 query，全量统计（便宜）
 *  · 分辨率采样 / 首图 / 首图哈希：**只对候选层（candidateDepth）做**，因为只有它们参与判定
 *  · 页级指纹：本轮不做（要对每页读头，代价高），下一轮按需对“需确认的组”算
 *  · .cbz/.zip 的页数：本轮按“1 个压缩包 = 1 页”处理（TODO: 缓存到私有目录后用 ZipFile 精确数）
 */
public final class SafScanner {

    private static final String TAG = "SafScanner";
    private static final int MAX_SAMPLE = 5;

    public interface Progress {
        /** @param dirs  已扫描目录数
         *  @param rel   当前目录相对路径（给用户看） */
        void onProgress(int dirs, String rel);
    }

    private final Context ctx;
    private final ContentResolver cr;
    private final Uri treeUri;
    private final int maxDepth;
    private final int candidateDepth;

    private int dirCount;

    public SafScanner(Context ctx, Uri treeUri, int candidateDepth) {
        this.ctx = ctx;
        this.cr = ctx.getContentResolver();
        this.treeUri = treeUri;
        this.candidateDepth = candidateDepth;
        this.maxDepth = Math.max(6, candidateDepth + 3);
    }

    /** 扫描并返回全部目录（含递归聚合值）。候选层由调用方按 depth 过滤。 */
    public List<DirInfo> scan(Progress cb) {
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

        // 自底向上聚合到父目录（按 rel 深度倒序）
        List<DirInfo> list = new ArrayList<DirInfo>(all.values());
        Collections.sort(list, new Comparator<DirInfo>() {
            @Override
            public int compare(DirInfo a, DirInfo b) {
                return Integer.compare(b.depth, a.depth);
            }
        });
        for (DirInfo d : list) {
            if (d.depth == 0) continue;
            String parentRel = parentOf(d.rel);
            DirInfo p = all.get(parentRel);
            if (p == null) continue;
            p.files += d.files;
            p.images += d.images;
            p.pages += d.pages;
            p.bytes += d.bytes;
            p.nums.addAll(d.nums);
        }
        return list;
    }

    // ---------------- 递归 ----------------

    private DirInfo walk(String docId, String rel, int depth,
                         Map<String, DirInfo> out, Progress cb) {
        DirInfo d = new DirInfo();
        d.path = rel;
        d.rel = rel;
        d.name = rel.isEmpty() ? "/" : lastName(rel);
        d.depth = depth;
        if (!rel.isEmpty()) {
            d.nums.addAll(Core.volNumbers(d.name));
        }
        out.put(rel, d);
        dirCount++;
        if (cb != null && (dirCount % 25 == 0 || depth == 0)) {
            cb.onProgress(dirCount, rel);
        }
        if (depth >= maxDepth) return d;

        List<String[]> subDirs = new ArrayList<String[]>();   // {docId, name}
        List<ImageRef> images = new ArrayList<ImageRef>();    // 直接图片，按名字排序用
        long archiveCount = 0;

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
                    images.add(new ImageRef(id, nm, size));
                } else if (Core.isArchiveName(nm)) {
                    // TODO: 精确页数（下一轮缓存到私有目录用 ZipFile 数）
                    d.pages += 1;
                    d.nums.addAll(Core.volNumbers(nm));
                    archiveCount++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query failed: " + rel, e);
        } finally {
            if (c != null) c.close();
        }

        // 只对候选层做重活：分辨率采样 / 首图 / 首图哈希
        if (depth == candidateDepth && d.pages > 0) {
            Collections.sort(images, new Comparator<ImageRef>() {
                @Override
                public int compare(ImageRef a, ImageRef b) {
                    return a.name.compareTo(b.name);
                }
            });
            int sample = 0;
            for (ImageRef img : images) {
                if (sample >= MAX_SAMPLE) break;
                Uri u = documentUri(img.docId);
                int[] wh = bounds(u);
                if (wh[0] > 0 && wh[1] > 0) {
                    d.sumW += wh[0];
                    d.sumH += wh[1];
                    d.sampleN++;
                    sample++;
                }
            }
            if (!images.isEmpty()) {
                ImageRef first = images.get(0);
                d.firstImage = documentUri(first.docId).toString();
                if (first.size > 0) {
                    d.firstHash = headHash(documentUri(first.docId), first.size);
                }
            }
        }

        // 递归子目录
        for (String[] sd : subDirs) {
            String childRel = rel.isEmpty() ? sd[1] : rel + "/" + sd[1];
            walk(sd[0], childRel, depth + 1, out, cb);
        }
        return d;
    }

    // ---------------- 小工具 ----------------

    private Uri documentUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    /** 只读文件头拿宽高，不解码整图 */
    private int[] bounds(Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            return new int[]{o.outWidth, o.outHeight};
        } catch (Exception e) {
            return new int[]{0, 0};
        } finally {
            closeQuietly(in);
        }
    }

    /** 首图指纹：文件大小 + 前 64KB 的 MD5 */
    private String headHash(Uri uri, long size) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            byte[] buf = new byte[64 * 1024];
            int n = in.read(buf);
            if (n <= 0) return "";
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(buf, 0, n);
            md.update(String.valueOf(size).getBytes("UTF-8"));
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            closeQuietly(in);
        }
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

    private static final class ImageRef {
        final String docId;
        final String name;
        final long size;

        ImageRef(String docId, String name, long size) {
            this.docId = docId;
            this.name = name;
            this.size = size;
        }
    }
}
