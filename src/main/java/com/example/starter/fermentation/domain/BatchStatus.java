package com.example.starter.fermentation.domain;

/**
 * 发酵批次状态机：满罐 → 主酵 → 降温 → 后熟 → 判定 → 清酒转出。
 * TRANSFERRED 与 REVIEW 为终态。
 */
public enum BatchStatus {

    /** 满罐，待开始主酵。 */
    FILLED,
    /** 主酵中，按工艺曲线分段控温。 */
    MAIN_FERMENT,
    /** 降温中（双乙酰判定合格后下达降温指令）。 */
    COOLING,
    /** 后熟中，按 -1℃ 保持不少于 7 天。 */
    MATURATION,
    /** 成熟度判定合格，待清酒转出。 */
    JUDGED,
    /** 清酒转出（终态），此后到达的检测数据只记迟到事件。 */
    TRANSFERRED,
    /** 延长后熟一次后判定仍不合格，整批复审（终态）。 */
    REVIEW
}
