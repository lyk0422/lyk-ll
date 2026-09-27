package com.example.starter.runway;

/**
 * 例行跑道巡检计划状态机。
 *
 * <p>正常流转：APPROVED（获准）→ STARTED（已开始）→ COMPLETED（正常完成，终态）。</p>
 * <ul>
 *   <li>APPROVED：发布获准，尚未开始；日历变更后转为 NEEDS_REVIEW。</li>
 *   <li>NEEDS_REVIEW：日历版本已变更而巡检尚未开始，待重审，不得直接开始。</li>
 *   <li>STARTED：已开始巡检；紧急关闭将其中止为 ABORTED，已完成检查项保留。</li>
 *   <li>COMPLETED：例行巡检正常完成，仅关闭计划，不取代恢复开放/通告流程（终态）。</li>
 *   <li>ABORTED：已开始后被紧急关闭中止（终态）；迟到回报不得开放跑道。</li>
 *   <li>CANCELLED：尚未开始即被紧急关闭取消（终态）。</li>
 * </ul>
 */
public enum RoutinePlanStatus {
    APPROVED,
    NEEDS_REVIEW,
    STARTED,
    COMPLETED,
    ABORTED,
    CANCELLED;

    /** 是否终态：终态释放同跑道同窗唯一获准占位。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED || this == CANCELLED;
    }
}
