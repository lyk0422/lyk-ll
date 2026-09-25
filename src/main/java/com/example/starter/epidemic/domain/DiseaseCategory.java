package com.example.starter.epidemic.domain;

/**
 * 传染病病种分类及报告时限。
 *
 * <p>报告时限自诊断时刻（UTC）起算：甲类 {@code A} 为 2 小时，乙类 {@code B} 为 24 小时；
 * 恰在时限终点提交判为及时。</p>
 */
public enum DiseaseCategory {
    /** 甲类传染病，报告时限 2 小时。 */
    A(2),
    /** 乙类传染病，报告时限 24 小时。 */
    B(24);

    private final int deadlineHours;

    DiseaseCategory(int deadlineHours) {
        this.deadlineHours = deadlineHours;
    }

    /** 报告时限（小时）。 */
    public int deadlineHours() {
        return deadlineHours;
    }
}
