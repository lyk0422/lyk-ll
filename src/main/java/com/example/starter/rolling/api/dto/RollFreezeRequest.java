package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 轧辊台账日冻结请求：冻结后迟到实绩只记迟到事件，不改已冻结里程。
 */
public record RollFreezeRequest(@NotBlank String requestId) {
}
