package com.example.starter.transition;

import java.math.BigDecimal;

/**
 * 合成正品检验门槛（仅用于本合成服务）。各边界值恰达即视为合格。
 *
 * <ul>
 *     <li>灰分 ≤ {@value #ASH_MAX_PCT}%；</li>
 *     <li>水分 ∈ [{@value #MOISTURE_MIN_PCT}%, {@value #MOISTURE_MAX_PCT}%]；</li>
 *     <li>抗张指数 ≥ {@value #TENSILE_MIN} Nm/g；</li>
 *     <li>定量与新品种标称克重偏差 ≤ {@value #BASIS_WEIGHT_TOLERANCE_PCT}%。</li>
 * </ul>
 */
public final class QualityThresholds {

    public static final BigDecimal ASH_MAX_PCT = new BigDecimal("18.000");
    public static final BigDecimal MOISTURE_MIN_PCT = new BigDecimal("4.000");
    public static final BigDecimal MOISTURE_MAX_PCT = new BigDecimal("8.000");
    public static final BigDecimal TENSILE_MIN = new BigDecimal("45.000");
    public static final BigDecimal BASIS_WEIGHT_TOLERANCE_PCT = new BigDecimal("3.00");

    /** 克重差直切阈值（含边界），单位 %。 */
    public static final BigDecimal DIRECT_SWITCH_MAX_PCT = new BigDecimal("10.0000");
    /** 灰分等级差合法直切上限：恰 2 级合法，超过须冲洗网部。 */
    public static final int ASH_FLUSH_LEVEL = 2;

    /** 回抄浆掺配比例合成允许区间下限（含边界），单位 %。 */
    public static final BigDecimal BLEND_RATIO_MIN_PCT = new BigDecimal("5.000");
    /** 回抄浆掺配比例合成允许区间上限（含边界），单位 %。 */
    public static final BigDecimal BLEND_RATIO_MAX_PCT = new BigDecimal("25.000");
    /** 掺配偏离容忍度（含边界），单位 百分点：恰 2% 合格，超过判超差。 */
    public static final BigDecimal BLEND_DEVIATION_TOLERANCE_PCT = new BigDecimal("2.000");
    /** 连续超差采样点判失控阈值。 */
    public static final int BLEND_OUT_OF_CONTROL_STREAK = 3;

    private QualityThresholds() {
    }
}
