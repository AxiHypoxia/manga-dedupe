package com.example.mangadedupe;

import java.util.ArrayList;
import java.util.List;

/** 一个分组：同一部作品的多个版本。 */
public final class Group {

    /** Core.LABEL_* */
    public String label;
    public final List<DirInfo> members;

    public Group(String label, List<DirInfo> members) {
        this.label = label;
        this.members = members == null ? new ArrayList<DirInfo>() : members;
    }

    public boolean needsReview() {
        return Core.needsReview(label);
    }

    public long totalBytes() {
        long s = 0;
        for (DirInfo d : members) s += d.bytes;
        return s;
    }

    /** 该组可省字节 = 总和 - 最大的一个 */
    public long saveBytes() {
        long total = 0;
        long max = 0;
        for (DirInfo d : members) {
            total += d.bytes;
            if (d.bytes > max) max = d.bytes;
        }
        return Math.max(0, total - max);
    }

    @Override
    public String toString() {
        return label + "(" + members.size() + ")";
    }
}
