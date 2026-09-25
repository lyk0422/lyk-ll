package com.example.starter.atm.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 押运打卡请求：两名不同押运员凭各自 requestId 先后打卡；同人重复打卡拒绝。 */
public record CheckinRequest(
        @NotBlank String requestId,
        @NotBlank String guardNo
) {
}
