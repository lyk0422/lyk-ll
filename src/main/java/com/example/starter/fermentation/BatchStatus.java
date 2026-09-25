package com.example.starter.fermentation;

/**
 * 发酵批次状态：满罐→主酵→降温→后熟→判定→清酒转出，不合格进入复审。
 */
public enum BatchStatus {
    /** 满罐（入罐受理成功，主酵控温可开始）。 */
    FILLED,
    /** 主酵：按工艺曲线分段控温。 */
    PRIMARY,
    /** 降温：降温指令与曲线版本切换同事务完成后所处的过渡阶段。 */
    COOLING,
    /** 后熟：按 -1℃ 保温不少于 7 天。 */
    MATURING,
    /** 复审：延长一次后仍综合判定不合格。 */
    REVIEW,
    /** 清酒转出（终态）。 */
    TRANSFERRED
}
