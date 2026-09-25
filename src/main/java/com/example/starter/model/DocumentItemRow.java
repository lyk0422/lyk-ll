package com.example.starter.model;

import java.time.Instant;

/**
 * 资料核对表行。
 *
 * @param itemCode    目录项编码
 * @param itemName    目录项名称
 * @param required    是否必交
 * @param submitted   是否已提交
 * @param submittedAt 提交时间（UTC），未提交为null
 */
public record DocumentItemRow(
        String itemCode,
        String itemName,
        boolean required,
        boolean submitted,
        Instant submittedAt) {
}
