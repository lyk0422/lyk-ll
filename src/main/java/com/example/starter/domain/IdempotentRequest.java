package com.example.starter.domain;

import java.time.Instant;

/**
 * 幂等请求记录行。
 *
 * @param requestId      幂等键
 * @param requestType    请求类型
 * @param fingerprint    requestId 与全部业务字段的 SHA-256 指纹
 * @param lotId          关联批次；申报前为空
 * @param resultRef      首次成功结果引用
 * @param resultSnapshot 首次成功响应快照 JSON；同键同参重放时原样返回；空表示首次业务尚未完成
 * @param createdAt      入库时间（UTC）
 */
public record IdempotentRequest(
        String requestId,
        RequestType requestType,
        String fingerprint,
        Long lotId,
        String resultRef,
        String resultSnapshot,
        Instant createdAt) {
}
