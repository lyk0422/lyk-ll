package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 放行结果。同键同参重放时 {@code replay=true}，原样返回首次结论；
 * 同键异参由服务端以 409 拒绝，不产生本结果。
 */
public record ReleaseResponse(
        String requestId,
        String barcode,
        String status,
        String rejectReason,
        String unitStatus,
        Instant issuedAt,
        boolean replay) {
}
