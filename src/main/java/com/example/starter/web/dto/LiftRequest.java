package com.example.starter.web.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 解除限功率请求：复测记录与解除在同一事务原子提交，
 * 须运行与监测两名不同人员确认。
 */
public record LiftRequest(
        @NotNull Integer actualPowerW,
        @NotNull OffsetDateTime measuredAt,
        @NotBlank String recordedBy,
        @NotBlank String opsConfirmer,
        @NotBlank String monitorConfirmer) {
}
