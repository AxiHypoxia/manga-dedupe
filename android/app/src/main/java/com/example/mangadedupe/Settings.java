package com.example.mangadedupe;

import java.util.Properties;

/**
 * 判定阈值。默认值与 PC 版 manga_dedupe.ini 的默认值一致（见 docs/需求文档.md 阈值总表）。
 * 之后可以做成界面里的“宽松/标准/严格”三档。
 */
public final class Settings {

    /** 标题相同但页数比低于此值 → 疑似（合集/短篇） */
    public static double pageDiffDowngrade = 0.70;
    /** 内容相同：页数比下限 */
    public static double contentPageRatio = 0.995;
    /** 内容相同：最少页数 */
    public static int contentMinPages = 12;
    /** 内容相同：分辨率比下限 */
    public static double contentResRatio = 0.95;
    /** 疑似：包含度 */
    public static double fuzzyCont = 0.92;
    /** 疑似：二元组 */
    public static double fuzzyBigram = 0.85;
    /** 疑似：长度比 */
    public static double fuzzyLenRatio = 0.90;
    /** 疑似：标题短于此长度不做近似判断 */
    public static int fuzzyMinChars = 8;
    /** 疑似：页数比低于此值直接不判 */
    public static double fuzzyPageFloor = 0.50;
    /** 重复组是否默认勾选日文版 */
    public static boolean autoCheckJp = true;

    private Settings() {
    }

    /** 从 Properties 覆盖（key 与 PC 版 ini 一致） */
    public static void load(Properties p) {
        if (p == null) return;
        pageDiffDowngrade = getD(p, "pagediff", pageDiffDowngrade);
        contentPageRatio = getD(p, "contentpageratio", contentPageRatio);
        contentMinPages = (int) getD(p, "contentminpages", contentMinPages);
        contentResRatio = getD(p, "contentresratio", contentResRatio);
        fuzzyCont = getD(p, "fuzzycont", fuzzyCont);
        fuzzyBigram = getD(p, "fuzzybigram", fuzzyBigram);
        fuzzyLenRatio = getD(p, "fuzzylenratio", fuzzyLenRatio);
        fuzzyMinChars = (int) getD(p, "fuzzyminchars", fuzzyMinChars);
        fuzzyPageFloor = getD(p, "fuzzypagefloor", fuzzyPageFloor);
        String ac = p.getProperty("autocheckjp");
        if (ac != null) autoCheckJp = Boolean.parseBoolean(ac.trim());
    }

    static double getD(Properties p, String key, double def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
