package com.example.mangadedupe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 判定核心的回归测试（8 个场景，与 PC 版 golden_test.cs 一一对应）。
 * 跑法：gradle testDebugUnitTest（CI 已配置）。
 */
public class CoreGoldenTest {

    // ================= 构造工具 =================

    private static DirInfo dir(String name, int pages, int w, int h, int bytesPerPage) {
        DirInfo d = new DirInfo();
        d.path = "/lib/" + name;
        d.name = name;
        d.rel = name;
        d.depth = 1;
        d.pages = pages;
        d.images = pages;
        d.files = pages;
        d.bytes = (long) pages * bytesPerPage;
        d.sampleN = 5;
        d.sumW = 5.0 * w;
        d.sumH = 5.0 * h;
        d.nums.addAll(Core.volNumbers(name));
        d.firstHash = md5(name + "|" + pages + "|" + d.bytes);
        return d;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String labels(List<Group> groups) {
        StringBuilder sb = new StringBuilder();
        for (Group g : groups) sb.append("[").append(g.label).append(" x").append(g.members.size()).append("]");
        return sb.toString();
    }

    // ================= 用例 =================

    /** 1. 三版本同标题（日文 + 中国翻訳 + 汉化组）→ 一组「可能重复」，默认勾日文版 */
    @Test
    public void case1_threeVersions() {
        List<DirInfo> units = new ArrayList<DirInfo>();
        units.add(dir("[サークルA] 作品P (原作X)", 24, 1200, 1700, 100));
        units.add(dir("[サークルA] 作品P (原作X) [中国翻訳]", 24, 1200, 1700, 101));
        units.add(dir("[漢化組B] 作品P (原作X) [汉化]", 24, 1200, 1700, 102));

        List<Group> groups = Core.groupPairs(units);
        assertEquals(labels(groups), 1, groups.size());
        assertEquals(Core.LABEL_DUP, groups.get(0).label);
        assertEquals(3, groups.get(0).members.size());
        assertTrue(Core.autoCheckJp(groups.get(0).label));

        List<Integer> sug = Core.suggestMarked(groups.get(0).members);
        assertEquals("应只勾选 1 个非中文版", 1, sug.size());
        assertFalse(Core.hasCnMarker(groups.get(0).members.get(sug.get(0)).name));
    }

    /** 2. 标题被汉化组改写、页数/分辨率一致 → 「内容相同（标题不同）」 */
    @Test
    public void case2_renamedTitleSamePages() {
        List<DirInfo> units = new ArrayList<DirInfo>();
        units.add(dir("[サークルC] 作品Q (原作Y)", 30, 1400, 2000, 100));
        units.add(dir("[漢化組D] 完全不同的中文标题 (原作Y)", 30, 1400, 2000, 130));

        List<Group> groups = Core.groupPairs(units);
        assertEquals(labels(groups), 1, groups.size());
        assertEquals(Core.LABEL_CONTENT, groups.get(0).label);
        assertEquals(2, groups.get(0).members.size());
    }

    /** 3. 合集 200 页 vs 短篇 20 页、标题相同 → 「疑似」，不能判可能重复 */
    @Test
    public void case3_collectionVsShort() {
        List<DirInfo> units = new ArrayList<DirInfo>();
        units.add(dir("[サークルE] 作品R (原作Z)", 200, 1000, 1500, 50));
        units.add(dir("[サークルE] 作品R (原作Z) [中国翻訳]", 20, 1000, 1500, 70));

        List<Group> groups = Core.groupPairs(units);
        assertEquals(labels(groups), 1, groups.size());
        assertEquals(Core.LABEL_FUZZY, groups.get(0).label);
        assertFalse(Core.autoCheckJp(groups.get(0).label));
    }

    /** 4. 同标题不同卷 → 「同作品不同卷」，不自动勾选、不给删除建议 */
    @Test
    public void case4_volumesOnly() {
        List<DirInfo> units = new ArrayList<DirInfo>();
        units.add(dir("[サークルF] 作品S 第01巻", 18, 1100, 1600, 80));
        units.add(dir("[サークルF] 作品S 第02巻", 18, 1100, 1600, 95));

        List<Group> groups = Core.groupPairs(units);
        assertEquals(labels(groups), 1, groups.size());
        assertEquals(Core.LABEL_VOLUMES, groups.get(0).label);
        assertFalse(Core.autoCheckJp(groups.get(0).label));
        assertTrue(Core.suggestMarked(groups.get(0).members).isEmpty());
    }

    /** 5. 不同作品共享 bigram（都有第01/02卷）→ 不能被合并成一组 */
    @Test
    public void case5_differentWorksSharedBigrams() {
        List<DirInfo> units = new ArrayList<DirInfo>();
        units.add(dir("[サークルY] 長い作品タイトルABC 第01巻", 20, 1200, 1700, 90));
        units.add(dir("[サークルY] 長い作品タイトルABC 第02巻", 20, 1200, 1700, 91));
        units.add(dir("[サークルZ] 別作品タイトルZZZ 第01巻", 18, 1200, 1700, 92));
        units.add(dir("[サークルZ] 別作品タイトルZZZ 第02巻", 18, 1200, 1700, 93));

        List<Group> groups = Core.groupPairs(units);
        assertEquals(labels(groups), 2, groups.size());
        for (Group g : groups) {
            assertEquals(Core.LABEL_VOLUMES, g.label);
            assertEquals(2, g.members.size());
            for (DirInfo d : g.members) {
                boolean y = d.name.contains("ABC");
                boolean z = d.name.contains("ZZZ");
                assertTrue("组成员不应混入另一部作品: " + g.label, y || z);
                assertFalse("同一组里不能既有 ABC 又有 ZZZ", y && z);
            }
        }
    }

    /** 6. 首图完全相同（复制/改名）→ 即使标题完全不同也判重复 */
    @Test
    public void case6_identicalFirstImage() {
        DirInfo a = dir("[サークルG] 作品T (原作W)", 14, 260, 380, 200);
        DirInfo b = dir("[サークルG] 完全不同的名字", 14, 260, 380, 200);
        String same = md5("same-first-image");
        a.firstHash = same;
        b.firstHash = same;

        List<DirInfo> units = new ArrayList<DirInfo>();
        units.add(a);
        units.add(b);

        List<Group> groups = Core.groupPairs(units);
        assertEquals(labels(groups), 1, groups.size());
        assertEquals(Core.LABEL_DUP, groups.get(0).label);
        Pair p = Core.classify(a, b);
        assertEquals(3, p.level);
        assertTrue(p.why, p.why.contains("首图"));
    }

    /** 7. 组内同时有「分卷」与「近似」关系 → 必须判需确认，不能降级成同作品不同卷 */
    @Test
    public void case7_mixedVolumesAndFuzzy() {
        List<DirInfo> units = new ArrayList<DirInfo>();
        units.add(dir("[サークルH] 作品U 第01巻", 20, 1200, 1700, 90));
        units.add(dir("[サークルH] 作品U 第02巻", 20, 1200, 1700, 95));
        units.add(dir("[サークルH] 作品U 第01巻 別版", 20, 1200, 1700, 99));

        List<Group> groups = Core.groupPairs(units);
        assertEquals(labels(groups), 1, groups.size());
        assertNotEquals("不能判成同作品不同卷", Core.LABEL_VOLUMES, groups.get(0).label);
    }

    /** 8. 页级指纹能测出缺 2 页 */
    @Test
    public void case8_pageSigDetectsMissingPages() {
        DirInfo a = dir("[サークルI] 作品V", 40, 1200, 1700, 100);
        DirInfo b = dir("[サークルI] 作品V [中国翻訳]", 38, 1200, 1700, 100);
        a.pageSig = new ArrayList<String>();
        b.pageSig = new ArrayList<String>();
        for (int i = 0; i < 40; i++) a.pageSig.add("1200:1700:" + (1000 + i));
        for (int i = 0; i < 38; i++) b.pageSig.add("1200:1700:" + (1000 + i));

        double[] r = Core.comparePages(a, b);
        assertEquals(2.0, r[0], 0.001);          // 页数差 2
        assertTrue("宽高一致率应 >= 0.9", r[1] >= 0.9);
    }

    /** 附加：基础解析自检（卷号 / 圈团 / 汉化标记 / 归一化） */
    @Test
    public void case9_basicParsing() {
        assertEquals(3, Core.volNumbers("作品 第1-3巻").size());
        assertTrue(Core.volNumbers("作品 第1-3巻").contains(2));
        assertTrue(Core.volNumbers("作品 前編").contains(1));
        assertEquals("サークルA", Core.circleOf("[C99] [サークルA (作者)] タイトル"));
        assertEquals("strong", Core.cnKind("[中国翻訳] タイトル"));
        assertEquals("weak", Core.cnKind("タイトル [翻译]"));
        assertEquals("作品p", Core.normTitle("[サークルA] 作品P (原作X) [汉化]"));
        assertTrue(Core.isMarked(Core.markName("abc")));
        assertEquals("abc", Core.unmarkName(Core.markName("abc")));
    }
}
