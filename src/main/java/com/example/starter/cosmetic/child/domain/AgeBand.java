package com.example.starter.cosmetic.child.domain;

/**
 * 分龄档位（整数月闭区间）。
 *
 * @param bandCode    档位编码：BAND_0_36 / BAND_37_144
 * @param lowerMonths 档位下限（整数月，含）
 * @param upperMonths 档位上限（整数月，含）
 */
public record AgeBand(String bandCode, int lowerMonths, int upperMonths) {

    /** 低龄档（0~36月）档位编码。 */
    public static final String BAND_LOW = "BAND_0_36";
    /** 高龄档（37~144月）档位编码。 */
    public static final String BAND_HIGH = "BAND_37_144";

    /** 申请年龄范围 [lower, upper] 是否落入/触及本档。 */
    public boolean intersects(int lower, int upper) {
        return lower <= upperMonths && upper >= lowerMonths;
    }

    /** 申请年龄范围与本档的交集下界（整数月，含）。 */
    public int segmentLower(int lower) {
        return Math.max(lower, lowerMonths);
    }

    /** 申请年龄范围与本档的交集上界（整数月，含）。 */
    public int segmentUpper(int upper) {
        return Math.min(upper, upperMonths);
    }
}
