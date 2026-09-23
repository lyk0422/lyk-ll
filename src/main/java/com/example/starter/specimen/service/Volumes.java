package com.example.starter.specimen.service;

import com.example.starter.specimen.error.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 体积校验与规范化：单位微升，最多三位小数，必须为正，超出精度直接拒绝而非四舍五入。
 */
public final class Volumes {

    /** 最大可表示体积：DECIMAL(14,3) 整数部分 11 位。 */
    private static final BigDecimal MAX = new BigDecimal("99999999999.999");

    private Volumes() {
    }

    /**
     * 校验并规范化为三位小数；非法时抛出 400。
     */
    public static BigDecimal normalize(BigDecimal value, String field) {
        if (value == null) {
            throw ApiException.badRequest("VOLUME_MISSING", field + " 不能为空");
        }
        if (value.signum() <= 0) {
            throw ApiException.badRequest("VOLUME_NOT_POSITIVE", field + " 必须为正数");
        }
        if (value.stripTrailingZeros().scale() > 3) {
            throw ApiException.badRequest("VOLUME_PRECISION", field + " 最多支持三位小数（微升）");
        }
        if (value.compareTo(MAX) > 0) {
            throw ApiException.badRequest("VOLUME_TOO_LARGE", field + " 超出可表示范围");
        }
        return value.setScale(3, RoundingMode.UNNECESSARY);
    }
}
