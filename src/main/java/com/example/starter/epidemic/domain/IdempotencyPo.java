package com.example.starter.epidemic.domain;

import java.time.Instant;

/**
 * 幂等记录行：指纹覆盖 requestId 与全部业务字段。
 *
 * @param requestId    幂等键（初报/订正/特殊订正发起）
 * @param scope        场景：SUBMIT 初报 / CORRECTION 订正 / SPECIAL 特殊订正
 * @param fingerprint   请求参数指纹（含 requestId）
 * @param cardNo       首次成功请求关联的卡片编号
 * @param versionNo    首次成功请求产生的版本号（特殊订正发起时为发起时版本）
 * @param responseJson 首次成功响应快照，重放原样返回
 * @param createdAt    首次请求时刻（UTC）
 */
public record IdempotencyPo(
        String requestId,
        String scope,
        String fingerprint,
        String cardNo,
        Integer versionNo,
        String responseJson,
        Instant createdAt
) {
}
