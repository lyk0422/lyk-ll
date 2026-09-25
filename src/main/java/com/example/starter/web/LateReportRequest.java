package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封存后迟到回报：封存为终态，迟到回报只记迟到事件，不改变工单状态。
 */
public record LateReportRequest(
        @NotBlank String requestId,
        @NotBlank String actorName,
        @NotBlank @Size(max = 512) String detail) {
}
