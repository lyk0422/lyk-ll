package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单个阀门步进结果。
 *
 * @param unitCode    单元编码
 * @param valveCode   阀门编码
 * @param action      调节方向 OPEN/CLOSE
 * @param fromOpening 步进前开度，单位:%
 * @param toOpening   步进后开度，单位:%
 * @param delta       步进幅度，单位:%
 * @param versionNo   步进后该阀门的版本号
 */
public record StepResult(
        String unitCode,
        String valveCode,
        String action,
        BigDecimal fromOpening,
        BigDecimal toOpening,
        BigDecimal delta,
        int versionNo
) {
}
