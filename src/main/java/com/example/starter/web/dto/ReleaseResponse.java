package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 解除限功率响应。
 */
public record ReleaseResponse(
        long directiveId,
        long retestId,
        long releaseConfirmationId,
        Instant releasedAt) {
}
