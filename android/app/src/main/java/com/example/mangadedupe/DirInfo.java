package com.example.mangadedupe;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一个候选目录（扫描结果）。字段与 PC 版 Core.cs 的 DirInfo 对应。 */
public final class DirInfo {

    public String path = "";
    public String name = "";
    public String rel = "";
    public int depth;

    /** 递归文件数 */
    public long files;
    /** 图片文件个数（不含压缩包内部条目） */
    public long images;
    /** 总页数：图片文件 + .cbz/.zip 内部图片条目 */
    public long pages;
    /** 递归字节数 */
    public long bytes;

    /** 分辨率采样：宽度之和 */
    public double sumW;
    /** 分辨率采样：高度之和 */
    public double sumH;
    /** 参与分辨率采样的页数 */
    public long sampleN;
    /** 0 字节图片个数 */
    public int zeroPage;

    /** 卷/话/分部号 */
    public final Set<Integer> nums = new HashSet<Integer>();

    /** 首图（压缩包里的会解到缓存目录） */
    public String firstImage;
    /** 首图字节数（算哈希时一起参与，抗“同尺寸不同内容”） */
    public long firstImageSize;
    /** 首图指纹：大小 + 前 64KB 的 MD5 */
    public String firstHash;

    /** 页级指纹：每页 "宽:高:字节" */
    public List<String> pageSig;
    public boolean pageSigDone;

    public double avgW() {
        return sampleN > 0 ? sumW / sampleN : 0;
    }

    public double avgH() {
        return sampleN > 0 ? sumH / sampleN : 0;
    }

    public double megapixels() {
        return sampleN > 0 ? avgW() * avgH() / 1000000.0 : 0;
    }
}
