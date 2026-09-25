package com.example.starter.lc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 拒付退单指令：拒付后申请人未放弃（或放弃无效），银行退单结案。
 */
public record RefundRequest(
        @NotBlank String requestId,
        @NotBlank String presentationNo) {
}
