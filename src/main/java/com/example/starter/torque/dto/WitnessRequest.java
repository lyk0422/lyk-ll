package com.example.starter.torque.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * H 点见证请求：present=true 表示见证人到场完成见证；
 * present=false 表示见证缺席，工序转入等待。
 */
public record WitnessRequest(
        @NotBlank String requestId,
        @NotBlank String pointCode,
        boolean present,
        String witness
) {
}
