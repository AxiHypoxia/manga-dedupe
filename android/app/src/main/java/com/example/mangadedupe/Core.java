package com.example.mangadedupe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 判定核心（不依赖 android.*，可直接在 JVM 单元测试里跑）。
 *
 * 与 PC 版 Core.cs 一一对应，顺序敏感：
 * 0 首图指纹相同 → 强重复
 * 1 卷号不重合（且标题也对得上）→ 同作品不同卷
 * 2 标题完全相同 → 强重复 / 圈团不同降级 / 页数差很多降级
 * 3 标题不同但页数+分辨率一致 → 内容相同
 * 4 标题近似 → 疑似
 *
 * 扫描（SAF / ZIP 解页 / 首图解码）在 SafScanner 里做，本类只吃 DirInfo。
 */
public final class Core {

    /** 待删除标记：加在目录名末尾，可见、可撤销、不用点前缀 */
    public static final String MARK_SUFFIX = "【待删除】";
    public static final String TRASH_DIR = ".dsh-trash";

    /** 判定级别 */
    public static final int LVL_NONE = 0;
    public static final int LVL_VOLUMES = 1;
    public static final int LVL_MEDIUM = 2;
    public static final int LVL_STRONG = 3;
    public static final int LVL_FUZZY = 4;
    public static final int LVL_CONTENT = 5;

    public static final String LABEL_DUP = "可能重复";
    public static final String LABEL_CONTENT = "内容相同（标题不同）";
    public static final String LABEL_FUZZY = "疑似（标题不完全一致）";
    public static final String LABEL_VOLUMES = "同作品不同卷";
    public static final String LABEL_UNGROUPED = "（未分组）";

    static final Set<String> IMAGE_EXT = new HashSet<String>(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif", ".jxl", ".heic"));
    static final Set<String> ARCHIVE_EXT = new HashSet<String>(Arrays.asList(".zip", ".cbz"));

    static final String[] CN_STRONG = {
            "汉化", "漢化", "中文", "中译", "中譯", "简中", "簡中", "繁中", "简体", "繁体",
            "中国翻訳", "中国翻译", "中文化", "汉化版", "漢化版", "中文版", "中文翻译",
            "chinese", "mandarin"};
    static final String[] CN_WEAK = {"翻译", "翻譯", "机翻", "機翻", "熟肉", "字幕组"};
    static final String[] JP_MARK = {
            "日本語", "日文", "日語", "日语", "日本语", "生肉", "原檔", "原档"};
    static final String[] VERSION_MARK = {
            "高清", "重制", "重置", "修复", "珍藏", "完全版", "全集", "收藏版",
            "remaster", "hd", "扫描版", "扫图", "合订", "爱藏"};
    static final String[] PART_TOKENS = {
            "前編", "前篇", "後編", "後篇", "中編", "中篇",
            "上巻", "上卷", "中巻", "中卷", "下巻", "下卷"};
    static final int[] PART_VALUES = {1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 3, 3};

    static final Pattern VOL_RANGE = Pattern.compile(
            "第?\\s*(\\d{1,4})\\s*[-~ー〜至]\\s*(\\d{1,4})\\s*[卷巻話话集册冊]");
    static final Pattern VOL_SINGLE = Pattern.compile(
            "第\\s*(\\d{1,4})\\s*[卷巻話话集册冊]"
                    + "|vol\\.?\\s*(\\d{1,4})"
                    + "|ch(?:apter)?\\.?\\s*(\\d{1,4})"
                    + "|(?:^|[\\s\\[\\]()【】_\\-])v(\\d{2,4})(?=[\\s.\\[\\]()【】_\\-]|$)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern BRACKET_RE = Pattern.compile(
            "\\[[^\\]]*\\]|\\([^\\)]*\\)|【[^】]*】|（[^）]*）");
    static final Pattern VOL_PAT = Pattern.compile(
            "(第?\\s*\\d+\\s*[-~ー〜至]\\s*\\d+\\s*[卷巻話話集册冊]?"
                    + "|第?\\s*\\d+\\s*[卷巻話話集册冊]"
                    + "|\\d+\\s*[-~]\\s*\\d+\\s*[卷巻話話集册冊]"
                    + "|vol\\.?\\s*\\d+|v\\s*\\d+$|全\\s*\\d+\\s*[卷册冊])",
            Pattern.CASE_INSENSITIVE);
    static final Pattern PUNCT_RE = Pattern.compile(
            "[\\s_\\-·.,，、+~!！?？:：;；'\"“”‘’\\[\\]【】()（）]");
    static final Pattern KANA_RE = Pattern.compile("[\\u3040-\\u309f\\u30a0-\\u30ff]");
    static final Pattern CIRCLE_BR = Pattern.compile("\\[([^\\]]+)\\]|【([^】]+)】");
    static final Pattern EVENT_TAG = Pattern.compile(
            "^(c\\d{2,3}|comic\\d*|例大祭|コミティア\\s*\\d*|コミケ\\d*|cwt\\d*|ff\\d*|dl版|"
                    + "同人誌|同人志|18禁|成年|エロ|全年齢|再録|総集編|合同誌)$",
            Pattern.CASE_INSENSITIVE);
    static final Pattern AUTHOR_PAREN = Pattern.compile("\\([^\\)]*\\)|（[^）]*）");

    private Core() {
    }

    // ================= 基础判断 =================

    public static boolean isImageName(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        for (String e : IMAGE_EXT) {
            if (low.endsWith(e)) return true;
        }
        return false;
    }

    public static boolean isArchiveName(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        for (String e : ARCHIVE_EXT) {
            if (low.endsWith(e)) return true;
        }
        return false;
    }

    public static String cnKind(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        for (String w : CN_STRONG) {
            if (low.contains(w)) return "strong";
        }
        for (String w : CN_WEAK) {
            if (low.contains(w)) return "weak";
        }
        return null;
    }

    public static boolean hasCnMarker(String name) {
        return cnKind(name) != null;
    }

    public static boolean hasKana(String name) {
        return KANA_RE.matcher(name).find();
    }

    public static boolean hasJpMarker(String name) {
        for (String w : JP_MARK) {
            if (name.contains(w)) return true;
        }
        return false;
    }

    public static boolean isMarked(String name) {
        return name.endsWith(MARK_SUFFIX);
    }

    public static String markName(String name) {
        return isMarked(name) ? name : name + MARK_SUFFIX;
    }

    public static String unmarkName(String name) {
        String s = name;
        while (s.endsWith(MARK_SUFFIX)) {
            s = s.substring(0, s.length() - MARK_SUFFIX.length());
        }
        return s;
    }

    // ================= 卷号 =================

    public static Set<Integer> volNumbers(String name) {
        Set<Integer> out = new LinkedHashSet<Integer>();
        Matcher m = VOL_RANGE.matcher(name);
        while (m.find()) {
            try {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                if (a > 0 && b >= a && b <= 500 && (b - a) <= 200) {
                    for (int v = a; v <= b; v++) out.add(v);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        m = VOL_SINGLE.matcher(name);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                String s = m.group(g);
                if (s == null) continue;
                try {
                    int v = Integer.parseInt(s);
                    if (v > 0 && v <= 2000) out.add(v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int i = 0; i < PART_TOKENS.length; i++) {
            if (name.contains(PART_TOKENS[i])) out.add(PART_VALUES[i]);
        }
        return out;
    }

    // ================= 圈团 =================

    public static String circleOf(String name) {
        Matcher m = CIRCLE_BR.matcher(name);
        while (m.find()) {
            String raw = (m.group(1) != null ? m.group(1) : m.group(2));
            if (raw == null) continue;
            raw = raw.trim();
            if (EVENT_TAG.matcher(raw).matches()) continue;
            if (hasCnMarker(raw)) continue;
            boolean isVersion = false;
            String lowRaw = raw.toLowerCase(Locale.ROOT);
            for (String w : VERSION_MARK) {
                if (lowRaw.contains(w)) {
                    isVersion = true;
                    break;
                }
            }
            if (isVersion) continue;
            String s = AUTHOR_PAREN.matcher(raw).replaceAll("").trim();
            s = s.replaceAll("^[\\s\\-_]+|[\\s\\-_]+$", "");
            if (s.length() > 0) return s;
        }
        return "";
    }

    // ================= 标题归一化 =================

    public static String normTitle(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        s = BRACKET_RE.matcher(s).replaceAll(" ");
        s = VOL_PAT.matcher(s).replaceAll(" ");
        for (String w : CN_STRONG) s = s.replace(w, " ");
        for (String w : CN_WEAK) s = s.replace(w, " ");
        for (String w : JP_MARK) s = s.replace(w, " ");
        for (String w : PART_TOKENS) s = s.replace(w, " ");
        s = s.replaceAll(
                "(?i)(chs|cht|jpn|jp|raw|raws|zip|rar|7z|cbz|cbr|chinese|mandarin|remastered|remaster)",
                " ");
        s = PUNCT_RE.matcher(s).replaceAll("");
        s = s.replace("副本", "").replace("copy", "").replace("コピー", "");
        return s.trim();
    }

    static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<String>();
        if (s.length() <= 1) {
            if (s.length() == 1) out.add(s);
            return out;
        }
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        int inter = 0;
        for (String x : a) {
            if (b.contains(x)) inter++;
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    /** 返回 {字符集Jaccard, 包含度, 二元组Jaccard} */
    public static double[] titleMetrics(String a, String b) {
        Set<Character> sa = new HashSet<Character>();
        Set<Character> sb = new HashSet<Character>();
        for (int i = 0; i < a.length(); i++) sa.add(a.charAt(i));
        for (int i = 0; i < b.length(); i++) sb.add(b.charAt(i));
        int inter = 0;
        for (Character c : sa) {
            if (sb.contains(c)) inter++;
        }
        int union = sa.size() + sb.size() - inter;
        double jac = union == 0 ? 0 : (double) inter / union;
        double cont = Math.min(sa.size(), sb.size()) == 0
                ? 0 : (double) inter / Math.min(sa.size(), sb.size());
        return new double[]{jac, cont, jaccard(bigrams(a), bigrams(b))};
    }

    /** 页级指纹比较：返回 {页数差, 宽高一致率, 完全一致率} */
    public static double[] comparePages(DirInfo a, DirInfo b) {
        List<String> x = a.pageSig == null ? new ArrayList<String>() : a.pageSig;
        List<String> y = b.pageSig == null ? new ArrayList<String>() : b.pageSig;
        int delta = x.size() - y.size();
        int n = Math.min(x.size(), y.size());
        if (n == 0) return new double[]{delta, 0, 0};
        int dimOk = 0;
        int exactOk = 0;
        for (int i = 0; i < n; i++) {
            String[] px = x.get(i).split(":");
            String[] py = y.get(i).split(":");
            if (px.length >= 2 && py.length >= 2 && px[0].equals(py[0]) && px[1].equals(py[1])) {
                dimOk++;
            }
            if (x.get(i).equals(y.get(i))) exactOk++;
        }
        return new double[]{delta, (double) dimOk / n, (double) exactOk / n};
    }

    // ================= 判定 =================

    public static boolean isChildOf(String path, String maybeParent) {
        String sep = path.contains("\\") ? "\\" : "/";
        return path.startsWith(maybeParent + sep);
    }

    public static Pair classify(DirInfo a, DirInfo b) {
        Pair p = new Pair();
        p.a = a;
        p.b = b;

        if (isChildOf(a.path, b.path) || isChildOf(b.path, a.path)) {
            p.level = LVL_NONE;
            p.why = "父子目录";
            return p;
        }
        String ka = normTitle(a.name);
        String kb = normTitle(b.name);
        if (ka.length() == 0 || kb.length() == 0) {
            p.level = LVL_NONE;
            p.why = "标题归一化后为空";
            return p;
        }

        boolean hasPages = a.pages >= 3 && b.pages >= 3;
        double pageRatio = 1.0;
        if (hasPages) {
            pageRatio = Math.min(a.pages, b.pages) / (double) Math.max(a.pages, b.pages);
        }
        boolean hasRes = a.sampleN > 0 && b.sampleN > 0;
        double resRatio = 0;
        if (hasRes) {
            double aw = a.avgW();
            double ah = a.avgH();
            double bw = b.avgW();
            double bh = b.avgH();
            if (aw > 0 && ah > 0 && bw > 0 && bh > 0) {
                double wr = Math.min(aw, bw) / Math.max(aw, bw);
                double hr = Math.min(ah, bh) / Math.max(ah, bh);
                resRatio = Math.min(wr, hr);
            }
        }
        String pageInfo = hasPages ? (a.pages + "页/" + b.pages + "页") : "页数未知";

        boolean titleSame = ka.equals(kb);
        int shortLen = Math.min(ka.length(), kb.length());
        double[] m = titleMetrics(ka, kb);
        double cont = m[1];
        double bjac = m[2];
        double lenRatio = Math.max(ka.length(), kb.length()) == 0 ? 0
                : (double) shortLen / Math.max(ka.length(), kb.length());
        boolean fuzzyTitle = !titleSame && shortLen >= Settings.fuzzyMinChars
                && cont >= Settings.fuzzyCont && bjac >= Settings.fuzzyBigram
                && lenRatio >= Settings.fuzzyLenRatio;

        // 0) 首图完全相同 → 同一份文件的副本
        if (a.firstHash != null && a.firstHash.length() > 0
                && b.firstHash != null && a.firstHash.equals(b.firstHash)) {
            p.level = LVL_STRONG;
            p.why = "首图完全相同（同一份文件）";
            return p;
        }

        // 1) 卷号不重合（必须标题也对得上）
        if (!a.nums.isEmpty() && !b.nums.isEmpty() && !intersects(a.nums, b.nums)
                && (titleSame || fuzzyTitle)) {
            p.level = LVL_VOLUMES;
            p.why = "卷话号不重合(" + ranges(a.nums) + " vs " + ranges(b.nums) + ")";
            return p;
        }

        // 2) 标题完全相同
        if (titleSame) {
            String ca = circleOf(a.name);
            String cb = circleOf(b.name);
            boolean circleDiff = ca.length() > 0 && cb.length() > 0
                    && !hasCnMarker(ca) && !hasCnMarker(cb)
                    && !normTitle(ca).equals(normTitle(cb));
            if (hasPages && pageRatio < Settings.pageDiffDowngrade) {
                p.level = LVL_FUZZY;
                p.why = "标题相同但页数差很多(" + pageInfo + ")，疑合集/短篇，需人工确认";
                return p;
            }
            if (circleDiff) {
                p.level = LVL_MEDIUM;
                p.why = "标题相同但圈团不同(" + ca + " vs " + cb + ")";
                return p;
            }
            p.level = LVL_STRONG;
            p.why = "标题完全相同" + (hasPages ? "，" + pageInfo : "");
            return p;
        }

        // 3) 标题不同但内容指纹一致（汉化改名）
        if (hasPages && pageRatio >= Settings.contentPageRatio
                && a.pages >= Settings.contentMinPages
                && (!hasRes || resRatio >= Settings.contentResRatio)) {
            String ca = circleOf(a.name);
            String cb = circleOf(b.name);
            boolean circleOk = ca.length() == 0 || cb.length() == 0
                    || hasCnMarker(ca) || hasCnMarker(cb)
                    || normTitle(ca).equals(normTitle(cb));
            if (circleOk) {
                p.level = LVL_CONTENT;
                p.why = "标题不同但页数/分辨率一致(" + pageInfo
                        + (hasRes ? " " + (int) a.avgW() + "x" + (int) a.avgH() : "") + ")";
                return p;
            }
        }

        // 4) 标题近似 → 疑似
        if (fuzzyTitle) {
            if (hasPages && pageRatio < Settings.fuzzyPageFloor) {
                p.level = LVL_NONE;
                p.why = "标题近似但页数差太多(" + pageInfo + ")";
                return p;
            }
            p.level = LVL_FUZZY;
            p.why = "标题近似(包含" + fmt2(cont) + " 字序" + fmt2(bjac)
                    + " 长度" + fmt2(lenRatio) + ")";
            return p;
        }

        p.level = LVL_NONE;
        p.why = "标题差异(包含" + fmt2(cont) + " 字序" + fmt2(bjac) + ")";
        return p;
    }

    static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static boolean intersects(Set<Integer> a, Set<Integer> b) {
        for (Integer v : a) {
            if (b.contains(v)) return true;
        }
        return false;
    }

    // ================= 分组 =================

    public static List<Group> groupPairs(List<DirInfo> units) {
        int n = units.size();
        String[] keys = new String[n];
        Map<String, List<Integer>> idx = new HashMap<String, List<Integer>>();
        for (int i = 0; i < n; i++) {
            keys[i] = normTitle(units.get(i).name);
            for (String bg : bigrams(keys[i])) {
                List<Integer> l = idx.get(bg);
                if (l == null) {
                    l = new ArrayList<Integer>();
                    idx.put(bg, l);
                }
                l.add(i);
            }
        }
        Map<Long, List<Integer>> byPages = new HashMap<Long, List<Integer>>();
        Map<String, List<Integer>> byHash = new HashMap<String, List<Integer>>();
        for (int i = 0; i < n; i++) {
            DirInfo d = units.get(i);
            if (d.pages >= 12) {
                List<Integer> l = byPages.get(d.pages);
                if (l == null) {
                    l = new ArrayList<Integer>();
                    byPages.put(d.pages, l);
                }
                l.add(i);
            }
            if (d.firstHash != null && d.firstHash.length() > 0) {
                List<Integer> l = byHash.get(d.firstHash);
                if (l == null) {
                    l = new ArrayList<Integer>();
                    byHash.put(d.firstHash, l);
                }
                l.add(i);
            }
        }

        Map<Long, Integer> shared = new HashMap<Long, Integer>();
        for (List<Integer> l : idx.values()) {
            if (l.size() < 2 || l.size() > 200) continue;
            for (int x = 0; x < l.size(); x++) {
                for (int y = x + 1; y < l.size(); y++) {
                    addPair(shared, Math.min(l.get(x), l.get(y)),
                            Math.max(l.get(x), l.get(y)), n, 1);
                }
            }
        }
        for (List<Integer> l : byPages.values()) {
            if (l.size() < 2 || l.size() > 60) continue;
            for (int x = 0; x < l.size(); x++) {
                for (int y = x + 1; y < l.size(); y++) {
                    addPair(shared, Math.min(l.get(x), l.get(y)),
                            Math.max(l.get(x), l.get(y)), n, 2);
                }
            }
        }
        for (List<Integer> l : byHash.values()) {
            if (l.size() < 2 || l.size() > 60) continue;
            for (int x = 0; x < l.size(); x++) {
                for (int y = x + 1; y < l.size(); y++) {
                    addPair(shared, Math.min(l.get(x), l.get(y)),
                            Math.max(l.get(x), l.get(y)), n, 2);
                }
            }
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (Map.Entry<Long, Integer> e : shared.entrySet()) {
            if (e.getValue() < 2) continue;
            int i = (int) (e.getKey() / n);
            int j = (int) (e.getKey() % n);
            if (classify(units.get(i), units.get(j)).level == LVL_NONE) continue;
            union(parent, i, j);
        }
        for (int i = 0; i < n; i++) {
            if (keys[i].length() == 0 || keys[i].length() >= 3) continue;
            for (int j = i + 1; j < n; j++) {
                if (keys[j].equals(keys[i])
                        && classify(units.get(i), units.get(j)).level > LVL_NONE) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<DirInfo>> maps = new HashMap<Integer, List<DirInfo>>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            List<DirInfo> g = maps.get(r);
            if (g == null) {
                g = new ArrayList<DirInfo>();
                maps.put(r, g);
            }
            g.add(units.get(i));
        }

        List<Group> groups = new ArrayList<Group>();
        for (List<DirInfo> members : maps.values()) {
            if (members.size() < 2) continue;
            boolean dup = false;
            boolean content = false;
            boolean fuzzy = false;
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    int lv = classify(members.get(i), members.get(j)).level;
                    if (lv == LVL_MEDIUM || lv == LVL_STRONG) dup = true;
                    else if (lv == LVL_CONTENT) content = true;
                    else if (lv == LVL_FUZZY) fuzzy = true;
                }
            }
            String label = dup ? LABEL_DUP
                    : (content ? LABEL_CONTENT : (fuzzy ? LABEL_FUZZY : LABEL_VOLUMES));
            Collections.sort(members, new Comparator<DirInfo>() {
                @Override
                public int compare(DirInfo x, DirInfo y) {
                    if (x.pages != y.pages) return Long.compare(y.pages, x.pages);
                    if (x.images != y.images) return Long.compare(y.images, x.images);
                    return Long.compare(y.bytes, x.bytes);
                }
            });
            groups.add(new Group(label, members));
        }
        Collections.sort(groups, new Comparator<Group>() {
            @Override
            public int compare(Group x, Group y) {
                int rx = rank(x.label);
                int ry = rank(y.label);
                if (rx != ry) return Integer.compare(rx, ry);
                return Long.compare(y.totalBytes(), x.totalBytes());
            }
        });
        return groups;
    }

    static void addPair(Map<Long, Integer> shared, int i, int j, int n, int addValue) {
        long k = (long) i * n + j;
        Integer old = shared.get(k);
        if (addValue >= 2) {
            shared.put(k, 2);
        } else {
            shared.put(k, (old == null ? 0 : old) + 1);
        }
    }

    static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    static void union(int[] p, int a, int b) {
        int ra = find(p, a);
        int rb = find(p, b);
        if (ra != rb) p[rb] = ra;
    }

    /** 排序权重：越小越靠前（需确认的置顶） */
    public static int rank(String label) {
        if (LABEL_DUP.equals(label)) return 0;
        if (LABEL_CONTENT.equals(label)) return 1;
        if (LABEL_FUZZY.equals(label)) return 2;
        if (LABEL_VOLUMES.equals(label)) return 3;
        return 9;
    }

    public static boolean needsReview(String label) {
        return rank(label) <= 2;
    }

    /** 是否默认勾选日文版：只对“确认重复/内容相同”生效 */
    public static boolean autoCheckJp(String label) {
        if (!Settings.autoCheckJp) return false;
        return LABEL_DUP.equals(label) || LABEL_CONTENT.equals(label);
    }

    /** 建议勾选（= 准备删除）的成员下标：非中文版；组里没有中文版则不给建议 */
    public static List<Integer> suggestMarked(List<DirInfo> members) {
        List<Integer> out = new ArrayList<Integer>();
        boolean hasCn = false;
        for (DirInfo d : members) {
            if (hasCnMarker(d.name)) {
                hasCn = true;
                break;
            }
        }
        if (!hasCn) return out;
        for (int i = 0; i < members.size(); i++) {
            if (!hasCnMarker(members.get(i).name)) out.add(i);
        }
        return out;
    }

    // ================= 展示工具 =================

    public static String ranges(Set<Integer> nums) {
        if (nums == null || nums.isEmpty()) return "-";
        List<Integer> v = new ArrayList<Integer>(nums);
        Collections.sort(v);
        StringBuilder sb = new StringBuilder();
        int start = v.get(0);
        int prev = start;
        for (int i = 1; i < v.size(); i++) {
            int cur = v.get(i);
            if (cur == prev + 1) {
                prev = cur;
                continue;
            }
            if (sb.length() > 0) sb.append(',');
            sb.append(start == prev ? String.valueOf(start) : (start + "-" + prev));
            start = cur;
            prev = cur;
        }
        if (sb.length() > 0) sb.append(',');
        sb.append(start == prev ? String.valueOf(start) : (start + "-" + prev));
        String s = sb.toString();
        return s.length() <= 24 ? s : s.substring(0, 21) + "...";
    }

    public static String human(long b) {
        if (b < 1024) return b + "B";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.ROOT, "%.1fKB", k);
        double mm = k / 1024.0;
        if (mm < 1024) return String.format(Locale.ROOT, "%.1fMB", mm);
        return String.format(Locale.ROOT, "%.2fGB", mm / 1024.0);
    }

    public static String relText(DirInfo a, DirInfo b) {
        if (a.nums.isEmpty() && b.nums.isEmpty()) return "两边都没有卷号";
        if (a.nums.isEmpty() || b.nums.isEmpty()) return "一边没有卷号 → 覆盖未知";
        Set<Integer> inter = new HashSet<Integer>(a.nums);
        inter.retainAll(b.nums);
        if (inter.isEmpty()) return "卷号不重合 → 不同分卷（不是重复）";
        if (inter.size() == a.nums.size() && inter.size() == b.nums.size()) return "卷号完全一致";
        Set<Integer> onlyA = new HashSet<Integer>(a.nums);
        onlyA.removeAll(b.nums);
        Set<Integer> onlyB = new HashSet<Integer>(b.nums);
        onlyB.removeAll(a.nums);
        return "部分重叠：重合 " + ranges(inter) + "，各自独有 " + ranges(onlyA) + " / " + ranges(onlyB);
    }
}
