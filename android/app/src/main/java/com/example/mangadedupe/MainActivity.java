package com.example.mangadedupe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面：选目录 → 两阶段扫描 → 2 列分组网格。
 *
 * 两阶段的目的（大库优化）：
 *   1) 先只查目录名/页数（不打开图片）→ 先分组
 *   2) 只对"疑似重复组"的成员打开首图 1 次 → 解析宽高 + 哈希 → 重算分组
 */
public class MainActivity extends Activity {

    private static final int REQ_TREE = 1001;

    private static final int BG = 0xFF0F0F11;
    private static final int TEXT = 0xFFEDEDEF;
    private static final int SUB = 0xFF8A8D94;

    private TextView status;
    private TextView empty;
    private Button pickBtn;
    private Button depthBtn;
    private Button scanBtn;
    private GroupAdapter adapter;
    private ImageLoader loader;

    private Uri treeUri;
    private int depth = 1;
    private boolean busy;
    private SafScanner scanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = (int) (d * 14);
        root.setPadding(pad, (int) (d * 10), pad, 0);

        TextView title = new TextView(this);
        title.setText("漫画重复筛选");
        title.setTextSize(19);
        title.setTextColor(TEXT);
        root.addView(title);

        status = new TextView(this);
        status.setText("选一个漫画根目录（会请求读写授权，用于改名标记）");
        status.setTextSize(11);
        status.setTextColor(SUB);
        status.setPadding(0, (int) (d * 4), 0, (int) (d * 8));
        root.addView(status);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(bar);

        pickBtn = button("选目录");
        bar.addView(pickBtn, lp(d, 0, 8));

        depthBtn = button("层数 1");
        bar.addView(depthBtn, lp(d, 0, 8));

        scanBtn = button("扫描");
        bar.addView(scanBtn, lp(d, 0, 0));

        RecyclerView rv = new RecyclerView(this);
        LinearLayout.LayoutParams rvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rvlp.topMargin = (int) (d * 10);
        root.addView(rv, rvlp);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.addItemDecoration(new GridSpace((int) (d * 6)));

        empty = new TextView(this);
        empty.setText("还没有结果\n\n1. 点「选目录」选择漫画根目录\n2. 选「层数」（第几层文件夹算一部作品）\n3. 点「扫描」");
        empty.setTextSize(12);
        empty.setTextColor(SUB);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(pad, (int) (d * 40), pad, 0);
        root.addView(empty);

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

        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree();
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

    // ---------------- SAF ----------------

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "打不开目录选择器：" + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        treeUri = uri;
        status.setText("已选目录：" + uri.getLastPathSegment());
    }

    // ---------------- 两阶段扫描 ----------------

    private void startScan() {
        if (treeUri == null) {
            Toast.makeText(this, "先点「选目录」", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        pickBtn.setEnabled(false);
        depthBtn.setEnabled(false);
        scanBtn.setEnabled(true);
        scanBtn.setText("取消");
        empty.setVisibility(View.GONE);
        adapter.setData(new ArrayList<Group>());
        status.setText("阶段 1/2：扫描目录…");

        final int candidateDepth = depth;
        scanner = new SafScanner(this, treeUri, candidateDepth);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder err = new StringBuilder();
                final List<DirInfo> units = new ArrayList<DirInfo>();
                long t0 = System.currentTimeMillis();
                try {
                    List<DirInfo> scanned = scanner.scanTree(new SafScanner.Progress() {
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
                        // 先用"名字 + 页数"分一次组
                        final List<Group> rough = Core.groupPairs(units);
                        // 需要精确判定的组 → 只对这些成员做首图分析
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
                        scanner.enrich(toEnrich, new SafScanner.Progress() {
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

                // 用补全后的数据重算分组（纯 CPU，毫秒级）
                final List<Group> groups = err.length() == 0
                        ? Core.groupPairs(units) : new ArrayList<Group>();
                final long timeCost = cost;
                final int unitCount = units.size();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        pickBtn.setEnabled(true);
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
                                + " · " + unitCount + " 部 · " + (timeCost / 1000.0) + "s");
                        empty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                        if (groups.isEmpty()) {
                            empty.setText("没找到重复\n\n可能原因：\n"
                                    + "· 层数选错了（当前 " + depth + " 层，试试其他）\n"
                                    + "· 这个目录下没有图片\n"
                                    + "· 都是压缩包（本轮按 1 包 1 页算）");
                        }
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
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF26282C);
        bg.setCornerRadius(getResources().getDisplayMetrics().density * 20);
        b.setBackground(bg);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding((int) (getResources().getDisplayMetrics().density * 14), 0,
                (int) (getResources().getDisplayMetrics().density * 14), 0);
        b.setHeight((int) (getResources().getDisplayMetrics().density * 42));
        return b;
    }

    private LinearLayout.LayoutParams lp(float d, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
