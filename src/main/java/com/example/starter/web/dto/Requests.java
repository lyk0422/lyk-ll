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

    /** 交班申请：绑定执行中计划、当前引航员、不同的接班员与潮窗内部UTC时刻。 */
    public record HandoverApplyRequest(
            @NotBlank String requestId,
            @NotNull Long planId,
            @NotNull Long fromPilotId,
            @NotNull Long toPilotId,
            @NotNull LocalDateTime handoverAt) {
    }

    /** 交班签认：出班/接班引航员分别签认，身份须对应，expectedVersion 过期返回 409。 */
    public record HandoverConfirmRequest(
            @NotBlank String requestId,
            @NotNull Long handoverId,
            @NotNull Long pilotId,
            String comment,
            @NotNull Long expectedVersion) {
    }

    /** 交班完成：双方签认且当前时刻达到约定交班时刻后原子切换。 */
    public record HandoverCompleteRequest(
            @NotBlank String requestId,
            @NotNull Long handoverId,
            @NotNull Long expectedVersion) {
    }

    /** 交班撤回：切换前允许，释放接班预占、保留原引航员；已切换不可撤回。 */
    public record HandoverWithdrawRequest(
            @NotBlank String requestId,
            @NotNull Long handoverId,
            @NotNull Long pilotId,
            @NotNull Long expectedVersion) {
    }
}
