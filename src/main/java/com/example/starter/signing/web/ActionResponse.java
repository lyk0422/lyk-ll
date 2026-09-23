package com.example.starter.signing.web;

import java.time.Instant;

/**
 * 签名提交、修订、取消等写操作的通用结果。
 *
 * @param packageId      签署包 ID（建包时生成并返回）
 * @param requestId      幂等请求键（仅签名提交返回），其他操作可能为 null
 * @param accepted       操作是否生效（签名提交成功 / 修订成功 / 取消成功）
 * @param revision       操作后当前修订号
 * @param completed      操作后流程是否已完成冻结
 * @param evidenceDigest 完成冻结后的证据包摘要，未冻结为 null
 * @param at             操作生效时间（UTC）
 */
public record ActionResponse(
        String packageId,
        String requestId,
        boolean accepted,
        int revision,
        boolean completed,
        String evidenceDigest,
        Instant at) {
}
