package com.example.starter.pv.data;

import java.time.Instant;

/**
 * pv_submission 表行对象（初报/随访幂等记录）。
 * 占位记录（requestId 已占住、业务尚未完成）的 caseNo/versionNo 为 null。
 */
public record SubmissionPo(
        Long id,
        String requestId,
        String requestKind,
        String requestFingerprint,
        String caseNo,
        Integer versionNo,
        boolean mergedDuplicate,
        String responseJson,
        Instant createdAt) {
}
