package com.example.starter.cosmetic.domain;

/**
 * 儿童分龄档定义（合成规则，按版本管理）。
 *
 * @param ruleVersion 分龄规则版本号
 * @param bandCode    分龄档编码（BAND_0_36 / BAND_37_144）
 * @param lowerMonths 档下限（整数月，含）
 * @param upperMonths 档上限（整数月，含）
 */
public record AgeBandRule(String ruleVersion,
                          String bandCode,
                          int lowerMonths,
                          int upperMonths) {

    /** 申请年龄区间 [lower, upper] 是否落入（ intersect ）本档。 */
    public boolean intersects(int lower, int upper) {
        return lower <= upperMonths && upper >= lowerMonths;
    }

    /** 申请年龄区间在本档内的覆盖下界（整数月，含）。 */
    public int coveredLower(int appliedLower) {
        return Math.max(appliedLower, lowerMonths);
    }

    /** 申请年龄区间在本档内的覆盖上界（整数月，含）。 */
    public int coveredUpper(int appliedUpper) {
        return Math.min(appliedUpper, upperMonths);
    }
}
