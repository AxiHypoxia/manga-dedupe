package com.example.mangadedupe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 编译链路验证用最小界面。
 * 下一步会替换成 QuickPic 风格的 Compose 实现（见 docs/UI设计.md）。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("漫画重复筛选");
        title.setTextSize(22);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("编译链路验证版\n\n"
                + "构建时间：内置在 BuildConfig 之外的常量，仅用于确认 APK 能装上并打开。\n\n"
                + "下一步将替换为：\n"
                + "· 分组网格（2 列，首版本封面）\n"
                + "· 组详情 / 多选\n"
                + "· 页面浏览（左右翻页 + 缩放）\n"
                + "· 同页对比（上下分屏，缩放同步）");
        info.setTextSize(15);
        info.setLineSpacing(0, 1.3f);
        info.setPadding(0, pad, 0, 0);
        root.addView(info);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }
}
