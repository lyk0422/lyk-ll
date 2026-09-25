package com.example.starter.museum.api;

import com.example.starter.museum.domain.Party;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 挂起恢复复核请求。环境恢复后须借出借入双方在同一复核单确认，
 * 双方齐备后合同方可由 SUSPENDED 恢复为 IN_EXHIBITION。
 */
public record ResumeReviewRequest(
        @NotBlank String requestId,
        @NotBlank String contractNo,
        @NotNull Party party
) {
}
