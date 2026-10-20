package com.example.mangadedupe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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
 * 轮次 A 的界面：选目录 → SAF 扫描 → 2 列分组网格。
 * 全部用代码搭 UI（不引 layout XML，减少构建风险）。
 */
public class MainActivity extends Activity {

    private static final int REQ_TREE = 1001;

    private static final int BG = 0xFF0F0F11;
    private static final int TEXT = 0xFFEDEDEF;
    private static final int SUB = 0xFF8A8D94;
    private static final int ACCENT = 0xFF4C8DFF;

    private TextView title;
    private TextView status;
    private TextView empty;
    private Button pickBtn;
    private Button depthBtn;
    private Button scanBtn;
    private RecyclerView rv;
    private GroupAdapter adapter;
    private ImageLoader loader;

    private Uri treeUri;
    private int depth = 1;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = (int) (d * 14);
        root.setPadding(pad, (int) (d * 10), pad, 0);

        // ---- 顶部标题 ----
        title = new TextView(this);
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

        // ---- 按钮行 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(bar);

        pickBtn = button("选目录");
        bar.addView(pickBtn, lp(d, 0, 8));

        depthBtn = button("层数 1");
        bar.addView(depthBtn, lp(d, 0, 8));

        scanBtn = button("扫描");
        bar.addView(scanBtn, lp(d, 0, 0));

        // ---- 列表 ----
        rv = new RecyclerView(this);
        LinearLayout.LayoutParams rvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rvlp.topMargin = (int) (d * 10);
        root.addView(rv, rvlp);

        GridLayoutManager glm = new GridLayoutManager(this, 2);
        rv.setLayoutManager(glm);
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
                startScan();
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

    // ---------------- 扫描 ----------------

    private void startScan() {
        if (busy) return;
        if (treeUri == null) {
            Toast.makeText(this, "先点「选目录」", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        pickBtn.setEnabled(false);
        depthBtn.setEnabled(false);
        scanBtn.setEnabled(false);
        scanBtn.setText("扫描中…");
        empty.setVisibility(View.GONE);
        adapter.setData(new ArrayList<Group>());
        status.setText("正在扫描…");

        final int candidateDepth = depth;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Group> groups = new ArrayList<Group>();
                String err = null;
                try {
                    SafScanner scanner = new SafScanner(MainActivity.this, treeUri, candidateDepth);
                    List<DirInfo> all = scanner.scan(new SafScanner.Progress() {
                        @Override
                        public void onProgress(final int dirs, final String rel) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    status.setText("扫描中… 已扫 " + dirs + " 个目录"
                                            + (rel.isEmpty() ? "" : " · " + tail(rel)));
                                }
                            });
                        }
                    });
                    List<DirInfo> units = new ArrayList<DirInfo>();
                    for (DirInfo di : all) {
                        if (di.depth != candidateDepth) continue;
                        if (Core.isMarked(di.name)) continue;
                        if (di.pages <= 0) continue;
                        units.add(di);
                    }
                    groups.addAll(Core.groupPairs(units));
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }

                final String ferr = err;
                final int unitCount = groups.size();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        pickBtn.setEnabled(true);
                        depthBtn.setEnabled(true);
                        scanBtn.setEnabled(true);
                        scanBtn.setText("扫描");
                        if (ferr != null) {
                            status.setText("扫描失败：" + ferr);
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
                                + " · 可省 " + Core.human(save));
                        empty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                        if (groups.isEmpty()) {
                            empty.setText("没找到重复\n\n可能原因：\n· 层数选错了（试试 2 层）\n· 目录里是压缩包（本轮按 1 包 1 页算，下一轮支持精确页数）");
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
        if (loader != null) loader.shutdown();
    }

    /** 网格间距 */
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
