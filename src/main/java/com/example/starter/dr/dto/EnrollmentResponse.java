package com.example.starter.dr.dto;

import java.math.BigDecimal;

/**
 * 电表登记视图。
 *
 * @param id              登记记录 ID
 * @param meterId         电表业务编码
 * @param committedValue  承诺单区间最大交付量
 * @param baselineVersion 登记时声明的基线版本标签
 */
public record EnrollmentResponse(
        Long id,
        String meterId,
        BigDecimal committedValue,
        String baselineVersion) {
}
