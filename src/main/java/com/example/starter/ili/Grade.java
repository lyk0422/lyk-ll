package com.example.starter.ili;

/**
 * 缺陷深度增长率分级口径：
 * 增长率 &lt; 5%/年为稳定；&ge; 5%/年为增长；&ge; 15%/年为严重。
 */
public enum Grade {
    /** 稳定：年增长率小于 5%。 */
    STABLE,
    /** 增长：年增长率达到或超过 5%，且小于 15%。 */
    GROWING,
    /** 严重：年增长率达到或超过 15%。 */
    SEVERE;

    /**
     * 按年增长率（壁厚百分比/年）判定分级。
     */
    public static Grade of(double growthRatePerYear) {
        if (growthRatePerYear >= 15.0) {
            return SEVERE;
        }
        if (growthRatePerYear >= 5.0) {
            return GROWING;
        }
        return STABLE;
    }
}
