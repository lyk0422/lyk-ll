package com.example.starter.schedule;

import java.util.List;

/**
 * 合成宽度档位表（毫米，按宽到窄排列）。
 * 相邻宽度跳档不得超过一级，恰跳一级合法。
 */
public final class WidthGradeTable {

    /** 合成档位，宽 → 窄。 */
    private static final List<Integer> GRADES = List.of(
            2000, 1800, 1600, 1500, 1400, 1300, 1200, 1100, 1000, 900, 800);

    private WidthGradeTable() {
    }

    /**
     * 返回宽度在档位表中的下标（0 最宽）；不在表内返回 null。
     */
    public static Integer indexOf(int widthMm) {
        int idx = GRADES.indexOf(widthMm);
        return idx < 0 ? null : idx;
    }

    /**
     * 档位表只读视图，用于错误提示。
     */
    public static List<Integer> grades() {
        return GRADES;
    }
}
