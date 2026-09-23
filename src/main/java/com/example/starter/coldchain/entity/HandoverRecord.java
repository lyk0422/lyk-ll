package com.example.starter.coldchain.entity;

import com.example.starter.coldchain.domain.SegmentStatus;

import java.time.LocalDateTime;

/**
 * 交接记录持久化对象：按 requestId 保存首次冻结结果与请求指纹。
 *
 * @param requestId        交接请求幂等键
 * @param shipmentId       货件标识
 * @param expectedVersion  请求携带的期望版本号
 * @param receiver         接收保管方
 * @param handoverAt       交接时间（Asia/Shanghai）
 * @param lastSequence     本段末个设备序号
 * @param fingerprint      请求参数指纹（SHA-256），同键异参判 409
 * @param segmentStatus    首次冻结的旧段判定结果；{@code null}=尚未完成
 * @param newVersion       首次冻结后打开的新段版本号；{@code null}=尚未完成
 * @param createdAt        记录创建时间（Asia/Shanghai）
 */
public record HandoverRecord(
        String requestId,
        String shipmentId,
        int expectedVersion,
        String receiver,
        LocalDateTime handoverAt,
        long lastSequence,
        String fingerprint,
        SegmentStatus segmentStatus,
        Integer newVersion,
        LocalDateTime createdAt
) {
}
