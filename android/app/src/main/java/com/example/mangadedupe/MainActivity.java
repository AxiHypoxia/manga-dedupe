package com.example.mangadedupe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 快速模式界面：申请「所有文件访问权限」→ 内置目录浏览 → 两阶段扫描 → 分组网格。
 * 不再走 SAF / 系统文件选择器（部分 ROM 上 DocumentsUI 会转圈转不出来）。
 */
public class MainActivity extends Activity {

    private static final int REQ_ALL_FILES = 2001;
    private static final int REQ_READ = 2002;

    private static final int BG = 0xFF0F0F11;
    private static final int TEXT = 0xFFEDEDEF;
    private static final int SUB = 0xFF8A8D94;
    private static final int ACCENT = 0xFF4C8DFF;

    private TextView status;
    private TextView pathText;
    private TextView empty;
    private LinearLayout browseList;
    private ScrollView browseScroll;
    private RecyclerView rv;
    private Button authBtn;
    private Button browseBtn;
    private Button upBtn;
    private Button useBtn;
    private Button depthBtn;
    private Button scanBtn;

    private GroupAdapter adapter;
    private ImageLoader loader;

    private File currentDir;
    private File chosenDir;
    private int depth = 1;
    private boolean busy;
    private FileScanner scanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = (int) (d * 12);
        root.setPadding(pad, (int) (d * 8), pad, 0);

        TextView title = new TextView(this);
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        title.setText("漫画重复筛选  v" + ver);
        title.setTextSize(19);
        title.setTextColor(TEXT);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(SUB);
        status.setPadding(0, (int) (d * 4), 0, (int) (d * 6));
        root.addView(status);

        pathText = new TextView(this);
        pathText.setTextSize(12);
        pathText.setTextColor(ACCENT);
        pathText.setSingleLine(true);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pathText.setPadding(0, (int) (d * 4), 0, (int) (d * 4));
        root.addView(pathText);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row1);
        authBtn = button("授权");
        row1.addView(authBtn, lp(d, 0, 6));
        browseBtn = button("浏览");
        row1.addView(browseBtn, lp(d, 0, 6));
        upBtn = button("↑ 上级");
        row1.addView(upBtn, lp(d, 0, 0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2lp.topMargin = (int) (d * 6);
        root.addView(row2, row2lp);
        useBtn = button("用这个目录");
        row2.addView(useBtn, lp(d, 0, 6));
        depthBtn = button("层数 1");
        row2.addView(depthBtn, lp(d, 0, 6));
        scanBtn = button("扫描");
        row2.addView(scanBtn, lp(d, 0, 0));

        // 中间区域：浏览列表 / 结果网格 二选一
        android.widget.FrameLayout content = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = (int) (d * 8);
        root.addView(content, clp);

        browseScroll = new ScrollView(this);
        browseList = new LinearLayout(this);
        browseList.setOrientation(LinearLayout.VERTICAL);
        browseScroll.addView(browseList);
        content.addView(browseScroll, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.addItemDecoration(new GridSpace((int) (d * 6)));
        rv.setVisibility(View.GONE);
        content.addView(rv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setTextSize(12);
        empty.setTextColor(SUB);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        content.addView(empty, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        loader = new ImageLoader(getContentResolver());
        adapter = new GroupAdapter(this, loader, new GroupAdapter.OnGroupClick() {
            @Override
            public void onClick(Group g, int position) {
                StringBuilder sb = new StringBuilder();
                sb.append(g.label).append(" · ").append(g.members.size()).append(" 个版本");
                for (DirInfo m : g.members) {
                    sb.append("\n· ").append(m.name)
                            .append("  ").append(m.pages).append("页")
                            .append("  ").append(Core.human(m.bytes));
                }
                Toast.makeText(MainActivity.this, sb.toString(), Toast.LENGTH_LONG).show();
            }
        });
        rv.setAdapter(adapter);

        authBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAccess();
            }
        });
        browseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDir != null) browse(currentDir);
            }
        });
        upBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDir == null) return;
                File p = currentDir.getParentFile();
                if (p == null) {
                    Toast.makeText(MainActivity.this, "已经到顶了", Toast.LENGTH_SHORT).show();
                    return;
                }
                browse(p);
            }
        });
        useBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentDir == null) {
                    Toast.makeText(MainActivity.this, "先浏览到一个目录", Toast.LENGTH_SHORT).show();
                    return;
                }
                chosenDir = currentDir;
                browseScroll.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
                empty.setVisibility(View.GONE);
                status.setText("已选目录：" + chosenDir.getAbsolutePath()
                        + "\n点「层数」调整，然后点「扫描」");
            }
        });
        depthBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                depth = depth >= 3 ? 1 : depth + 1;
                depthBtn.setText("层数 " + depth);
            }
        });
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    if (scanner != null) scanner.cancel();
                    scanBtn.setText("取消中…");
                    scanBtn.setEnabled(false);
                } else {
                    startScan();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasAccess()) {
            status.setText("需要「所有文件访问权限」才能直接读取手机文件"
                    + "\n点「授权」→ 在系统设置里打开开关 → 返回");
            authBtn.setVisibility(View.VISIBLE);
            return;
        }
        authBtn.setVisibility(View.GONE);
        if (currentDir == null || browseList.getChildCount() == 0) {
            File start = Environment.getExternalStorageDirectory();
            browse(start);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ) {
            if (hasAccess()) {
                browse(Environment.getExternalStorageDirectory());
            } else {
                status.setText("没拿到读取权限，无法扫描");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ALL_FILES) {
            // 回来时 onResume 会重新检查
            if (!hasAccess()) {
                status.setText("还没打开「所有文件访问权限」\n"
                        + "设置 → 应用 → 漫画重复筛选 → 权限 → 所有文件访问权限");
            }
        }
    }

    // ---------------- 权限 ----------------

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_ALL_FILES);
            } catch (Exception e) {
                try {
                    startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            REQ_ALL_FILES);
                } catch (Exception e2) {
                    Toast.makeText(this, "打不开设置页，请手动到：设置 → 应用 → 漫画重复筛选 → 权限",
                            Toast.LENGTH_LONG).show();
                }
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_READ);
        }
    }

    // ---------------- 目录浏览 ----------------

    private void browse(File dir) {
        if (dir == null || !dir.isDirectory()) {
            Toast.makeText(this, "不是有效目录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasAccess() && !dir.canRead()) {
            status.setText("没有权限读取该目录，请先点「授权」");
            return;
        }
        currentDir = dir;
        pathText.setText(dir.getAbsolutePath());
        browseList.removeAllViews();
        browseScroll.setVisibility(View.VISIBLE);
        rv.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);

        File parent = dir.getParentFile();
        if (parent != null) {
            addRow("↑ 上一级（" + parent.getName() + "）", parent, true);
        }

        File[] kids = dir.listFiles();
        List<File> dirs = new ArrayList<File>();
        int fileCount = 0;
        if (kids != null) {
            for (File f : kids) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) dirs.add(f);
                else fileCount++;
            }
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : dirs) {
            addRow("▸ " + f.getName(), f, false);
        }
        if (dirs.isEmpty() && fileCount == 0) {
            addRow("（空目录）", null, false);
        }
        status.setText(dirs.size() + " 个子文件夹 · " + fileCount + " 个文件"
                + " · 点文件夹进入，或点「用这个目录」直接选当前目录");
    }

    private void addRow(String text, final File target, boolean isUp) {
        final float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(isUp ? SUB : TEXT);
        t.setPadding((int) (d * 10), (int) (d * 12), (int) (d * 10), (int) (d * 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A1B1E);
        bg.setCornerRadius(d * 8);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (d * 6);
        t.setLayoutParams(lp);
        if (target != null) {
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    browse(target);
                }
            });
        }
        browseList.addView(t);
    }

    // ---------------- 扫描 ----------------

    private void startScan() {
        if (chosenDir == null) {
            Toast.makeText(this, "先点「用这个目录」", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        authBtn.setEnabled(false);
        browseBtn.setEnabled(false);
        upBtn.setEnabled(false);
        useBtn.setEnabled(false);
        depthBtn.setEnabled(false);
        scanBtn.setEnabled(true);
        scanBtn.setText("取消");
        empty.setVisibility(View.GONE);
        adapter.setData(new ArrayList<Group>());
        status.setText("阶段 1/2：扫描目录…");

        final int candidateDepth = depth;
        scanner = new FileScanner(chosenDir, candidateDepth);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder err = new StringBuilder();
                final List<DirInfo> units = new ArrayList<DirInfo>();
                long t0 = System.currentTimeMillis();
                try {
                    List<DirInfo> scanned = scanner.scanTree(new FileScanner.Progress() {
                        @Override
                        public void onProgress(int phase, final int done, int total, final String detail) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    status.setText("阶段 1/2：扫描目录 " + done + " 个"
                                            + (detail.isEmpty() ? "" : " · " + tail(detail)));
                                }
                            });
                        }
                    });
                    units.addAll(scanned);

                    if (!scanner.isCancelled()) {
                        final List<Group> rough = Core.groupPairs(units);
                        final List<DirInfo> toEnrich = new ArrayList<DirInfo>();
                        for (Group g : rough) {
                            if (!Core.needsReview(g.label)) continue;
                            for (DirInfo m : g.members) {
                                if (!toEnrich.contains(m)) toEnrich.add(m);
                            }
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("阶段 2/2：分析封面 0/" + toEnrich.size() + "…");
                            }
                        });
                        scanner.enrich(toEnrich, new FileScanner.Progress() {
                            @Override
                            public void onProgress(int phase, final int done, int total, final String detail) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        status.setText("阶段 2/2：分析封面 " + done + "/" + total
                                                + " · " + tail(detail));
                                    }
                                });
                            }
                        });
                    }
                } catch (Throwable t) {
                    err.append(t);
                }
                long cost = System.currentTimeMillis() - t0;

                final List<Group> groups = err.length() == 0
                        ? Core.groupPairs(units) : new ArrayList<Group>();
                final long timeCost = cost;
                final int unitCount = units.size();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        authBtn.setEnabled(true);
                        browseBtn.setEnabled(true);
                        upBtn.setEnabled(true);
                        useBtn.setEnabled(true);
                        depthBtn.setEnabled(true);
                        scanBtn.setEnabled(true);
                        scanBtn.setText("扫描");
                        if (err.length() > 0) {
                            status.setText("扫描失败：" + err);
                            return;
                        }
                        adapter.setData(groups);
                        int review = 0;
                        long save = 0;
                        for (Group g : groups) {
                            if (g.needsReview()) review++;
                            save += g.saveBytes();
                        }
                        status.setText(groups.size() + " 组 · 需确认 " + review
                                + " · 可省 " + Core.human(save)
                                + " · " + unitCount + " 部 · " + (timeCost / 1000.0) + "s"
                                + " · 层数 " + depth);
                        empty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                        empty.setText("没找到重复\n\n"
                                + "· 当前层数 " + depth + "（可改成 1/2/3 试试）\n"
                                + "· 目录里要有图片（jpg/png/webp）\n"
                                + "· 已带【待删除】的目录会被跳过");
                    }
                });
            }
        }).start();
    }

    private static String tail(String rel) {
        int i = rel.lastIndexOf('/');
        String s = i < 0 ? rel : rel.substring(i + 1);
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }

    // ---------------- UI 小工具 ----------------

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF26282C);
        bg.setCornerRadius(getResources().getDisplayMetrics().density * 18);
        b.setBackground(bg);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setHeight((int) (getResources().getDisplayMetrics().density * 40));
        return b;
    }

    private LinearLayout.LayoutParams lp(float d, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = (int) (d * left);
        p.rightMargin = (int) (d * right);
        return p;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.cancel();
        if (loader != null) loader.shutdown();
    }

    static final class GridSpace extends RecyclerView.ItemDecoration {
        private final int half;

        GridSpace(int half) {
            this.half = half;
        }

        @Override
        public void getItemOffsets(android.graphics.Rect outRect, View view,
                                   RecyclerView parent, RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos < 0) return;
            outRect.left = half;
            outRect.right = half;
            outRect.top = half;
            outRect.bottom = 0;
        }
    }
}
