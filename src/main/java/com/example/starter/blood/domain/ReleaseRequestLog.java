package com.example.starter.blood.domain;

import java.time.Instant;

/**
 * 放行请求幂等台账记录。
 *
 * @param requestId   放行请求幂等键
 * @param fingerprint 幂等指纹（requestId+条码+时间窗的 SHA-256）
 * @param barcode     目标成分血条码
 * @param outcome     处理结果：RELEASED 放行成功 / REJECTED 拒绝
 * @param unitStatus  响应时单位真实状态
 * @param message     结果说明，null 表示无
 * @param createdAt   首次处理时刻（UTC）
 */
public record ReleaseRequestLog(
        String requestId,
        String fingerprint,
        String barcode,
        String outcome,
        UnitStatus unitStatus,
        String message,
        Instant createdAt) {
}
