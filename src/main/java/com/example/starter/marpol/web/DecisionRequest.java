package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 逐条核查结论请求：确认或标注违规。
 */
public record DecisionRequest(
        @NotNull Long recordId,
        @NotNull @Pattern(regexp = "CONFIRMED|VIOLATION") String decision,
        @Size(max = 1024) String reason) {
}
