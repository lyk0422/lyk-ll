package com.example.starter.lot.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转换单持久化对象（历史不可变）。
 *
 * @param id             转换单主键
 * @param requestId      幂等请求键
 * @param fingerprint    完整请求指纹
 * @param requestJson    首次完整请求快照
 * @param responseJson   首次完整响应快照
 * @param totalInputQty  总消费量
 * @param totalOutputQty 总产出量
 * @param allowedLoss    允许损耗
 * @param unit           计量单位
 * @param createdAt      提交时间（Asia/Shanghai）
 */
public record ConversionRecord(
        Long id,
        String requestId,
        String fingerprint,
        String requestJson,
        String responseJson,
        BigDecimal totalInputQty,
        BigDecimal totalOutputQty,
        BigDecimal allowedLoss,
        String unit,
        LocalDateTime createdAt
) {
}
