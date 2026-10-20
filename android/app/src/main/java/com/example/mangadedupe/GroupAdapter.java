package com.example.mangadedupe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 2 列分组网格（QuickPic 风格：深色卡片 + 大封面 + 徽章）。 */
public final class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

    public interface OnGroupClick {
        void onClick(Group g, int position);
    }

    private static final int CARD_BG = 0xFF1A1B1E;
    private static final int TEXT = 0xFFEDEDEF;
    private static final int SUB = 0xFF8A8D94;
    private static final int GREEN = 0xFF4CAF6E;
    private static final int AMBER = 0xFFE6A83C;
    private static final int GRAY = 0xFF8A8D94;

    private final Context ctx;
    private final ImageLoader loader;
    private final OnGroupClick click;
    private final List<Group> data = new ArrayList<Group>();

    public GroupAdapter(Context ctx, ImageLoader loader, OnGroupClick click) {
        this.ctx = ctx;
        this.loader = loader;
        this.click = click;
    }

    public void setData(List<Group> groups) {
        data.clear();
        if (groups != null) data.addAll(groups);
        notifyDataSetChanged();
    }

    public Group getItem(int pos) {
        return data.get(pos);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(d, 10));
        card.setBackground(bg);
        int pad = (int) dp(d, 6);
        card.setPadding(pad, pad, pad, (int) dp(d, 8));

        // 封面 + 角标（FrameLayout 叠放）
        FrameLayout coverWrap = new FrameLayout(ctx);
        card.addView(coverWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(d, 210)));

        ImageView cover = new ImageView(ctx);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF232529);
        coverWrap.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView badge = new TextView(ctx);
        badge.setTextSize(10);
        badge.setTextColor(Color.WHITE);
        badge.setPadding((int) dp(d, 6), (int) dp(d, 2), (int) dp(d, 6), (int) dp(d, 2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.setMargins((int) dp(d, 6), (int) dp(d, 6), 0, 0);
        coverWrap.addView(badge, blp);

        TextView count = new TextView(ctx);
        count.setTextSize(10);
        count.setTextColor(TEXT);
        count.setPadding((int) dp(d, 6), (int) dp(d, 2), (int) dp(d, 6), (int) dp(d, 2));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.END;
        clp.setMargins(0, (int) dp(d, 6), (int) dp(d, 6), 0);
        coverWrap.addView(count, clp);

        TextView name = new TextView(ctx);
        name.setTextSize(12);
        name.setTextColor(TEXT);
        name.setPadding(0, (int) dp(d, 6), 0, 0);
        card.addView(name);

        TextView meta = new TextView(ctx);
        meta.setTextSize(10);
        meta.setTextColor(SUB);
        card.addView(meta);

        VH vh = new VH(card);
        vh.cover = cover;
        vh.badge = badge;
        vh.count = count;
        vh.name = name;
        vh.meta = meta;
        return vh;
    }

    @Override
    public void onBindViewHolder(final VH h, int position) {
        final Group g = data.get(position);
        int dp410 = (int) dp(ctx.getResources().getDisplayMetrics().density, 160);

        String shortLabel;
        int color;
        if (Core.LABEL_DUP.equals(g.label)) {
            shortLabel = "可能重复";
            color = GREEN;
        } else if (Core.LABEL_CONTENT.equals(g.label)) {
            shortLabel = "内容相同";
            color = AMBER;
        } else if (Core.LABEL_FUZZY.equals(g.label)) {
            shortLabel = "疑似";
            color = AMBER;
        } else {
            shortLabel = "不同卷";
            color = GRAY;
        }
        h.badge.setText(shortLabel);
        GradientDrawable bgbg = new GradientDrawable();
        bgbg.setColor(0x99000000);
        bgbg.setCornerRadius(dp(ctx.getResources().getDisplayMetrics().density, 9));
        h.badge.setBackground(bgbg);

        h.count.setText(g.members.size() + " 个");
        GradientDrawable ctbg = new GradientDrawable();
        ctbg.setColor(0x99000000);
        ctbg.setCornerRadius(dp(ctx.getResources().getDisplayMetrics().density, 9));
        h.count.setBackground(ctbg);

        DirInfo first = g.members.isEmpty() ? null : g.members.get(0);
        h.name.setText(first == null ? "(空组)" : first.name);
        long save = g.saveBytes();
        h.meta.setText(save > 0 ? ("可省 " + Core.human(save)) : "无需处理");
        h.meta.setTextColor(save > 0 ? AMBER : SUB);

        h.cover.setImageDrawable(null);
        if (first != null && first.firstImage != null) {
            final String key = first.firstImage;
            h.cover.setTag(key);
            loader.load(key, dp410, new ImageLoader.Callback() {
                @Override
                public void onBitmap(android.graphics.Bitmap bmp) {
                    if (bmp != null && key.equals(h.cover.getTag())) {
                        h.cover.setImageBitmap(bmp);
                    }
                }
            });
        }

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (click != null) click.onClick(g, h.getAdapterPosition());
            }
        });
    }

    static float dp(float density, float v) {
        return density * v;
    }

    static final class VH extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView badge;
        TextView count;
        TextView name;
        TextView meta;

        VH(View v) {
            super(v);
        }
    }
}
