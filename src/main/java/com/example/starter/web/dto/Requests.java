package com.example.starter.web.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 写操作请求 DTO 集合；每个写操作均携带 requestId 用于幂等。
 */
public final class Requests {

    private Requests() {
    }

    /** 申请引航计划。 */
    public record ApplyRequest(
            @NotBlank String requestId,
            @NotBlank String applicant,
            @NotBlank String shipName,
            @NotNull LocalDateTime planStart,
            @NotNull LocalDateTime planEnd) {
    }

    /** 编排：绑定引航员、拖轮组合与潮水窗口（半开区间）。 */
    public record ScheduleRequest(
            @NotBlank String requestId,
            @NotNull Long planId,
            @NotNull Long pilotId,
            @NotEmpty List<Long> tugIds,
            @NotNull LocalDateTime tideStart,
            @NotNull LocalDateTime tideEnd) {
    }

    /** 确认：同一事务内锁定引航员与拖轮。 */
    public record ConfirmRequest(
            @NotBlank String requestId,
            @NotNull Long planId) {
    }

    /** 开始执行。 */
    public record StartRequest(
            @NotBlank String requestId,
            @NotNull Long planId) {
    }

    /** 核销。 */
    public record SettleRequest(
            @NotBlank String requestId,
            @NotNull Long planId) {
    }

    /** 取消（迟到取消按距执行时刻的提前量判定违约）。 */
    public record CancelRequest(
            @NotBlank String requestId,
            @NotNull Long planId) {
    }

    /** 改期：重新校验工时与拖轮占用，结论与重新锁定同事务。 */
    public record RescheduleRequest(
            @NotBlank String requestId,
            @NotNull Long planId,
            @NotNull LocalDateTime planStart,
            @NotNull LocalDateTime planEnd,
            @NotNull LocalDateTime tideStart,
            @NotNull LocalDateTime tideEnd) {
    }
}
