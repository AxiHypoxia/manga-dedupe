package com.example.mangadedupe;

/** 一对目录的判定结果。 */
public final class Pair {

    public DirInfo a;
    public DirInfo b;

    /** Core.LVL_* */
    public int level;

    /** 人类可读的原因，UI 直接显示 */
    public String why = "";

    @Override
    public String toString() {
        return "Pair{" + level + ", " + why + "}";
    }
}
