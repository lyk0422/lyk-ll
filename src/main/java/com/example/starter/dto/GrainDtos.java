package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.example.starter.model.JobStatus;
import com.example.starter.model.RecheckDecision;
import com.example.starter.model.SiloStatus;
import com.example.starter.model.VentilationPurpose;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 粮情测控服务请求/响应对象集合。
 */
public final class GrainDtos {

    private GrainDtos() {
    }

    /** 创建粮仓请求；安全温度单位℃，平衡湿度单位%RH。 */
    public record CreateSiloRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull BigDecimal safeTemperature,
            @NotNull BigDecimal equilibriumHumidity) {
    }

    /** 粮仓视图。 */
    public record SiloView(
            long id,
            String code,
            String name,
            BigDecimal safeTemperature,
            BigDecimal equilibriumHumidity,
            SiloStatus status) {
    }

    /** 创建风机请求。 */
    public record CreateFanRequest(
            @NotBlank String code,
            @NotBlank String name) {
    }

    /** 风机视图；lockedJobId 为通风作业锁定，lockedFumigationId 为熏蒸散气锁定。 */
    public record FanView(
            long id,
            String code,
            String name,
            String status,
            Long lockedJobId,
            Long lockedFumigationId) {
    }

    /**
     * 温湿度上报请求；requestId 幂等，指纹覆盖 requestId 与全部业务字段。
     * 温度单位℃，湿度单位%RH，measuredAt 为 Asia/Shanghai 本地时间。
     */
    public record ReadingRequest(
            @NotBlank String requestId,
            @NotNull Integer pointNo,
            @NotNull BigDecimal temperature,
            BigDecimal outsideHumidity,
            @NotNull LocalDateTime measuredAt) {
    }

    /**
     * 温湿度上报结果：NORMAL 正常（含恰等安全温度）、ALARM 已告警、LATE_RECORDED 归档后迟到仅记事件。
     */
    public record ReadingResponse(
            String result,
            Long alarmId,
            SiloStatus siloStatus,
            boolean replayed) {
    }

    /**
     * 通风作业下达请求；requestId 幂等。作业窗为半开区间 [windowStart, windowEnd)。
     */
    public record CreateJobRequest(
            @NotBlank String requestId,
            @NotNull Long fanId,
            @NotNull VentilationPurpose purpose,
            @NotNull LocalDateTime windowStart,
            @NotNull LocalDateTime windowEnd,
            @NotNull BigDecimal outsideHumidity) {
    }

    /** 通风作业视图。 */
    public record JobView(
            long id,
            long siloId,
            long fanId,
            VentilationPurpose purpose,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            BigDecimal outsideHumidity,
            int roundNo,
            JobStatus status,
            boolean replayed) {
    }

    /**
     * 复测请求；梯度与限值单位℃，超过限值判超标，恰等判合格。
     */
    public record RecheckRequest(
            @NotBlank String requestId,
            @NotNull BigDecimal gradient,
            @NotNull BigDecimal gradientLimit,
            @NotNull LocalDateTime measuredAt) {
    }

    /** 复测视图。 */
    public record RecheckView(
            long id,
            long jobId,
            long siloId,
            int roundNo,
            BigDecimal gradient,
            BigDecimal gradientLimit,
            boolean exceeded,
            RecheckDecision decision,
            boolean replayed) {
    }

    /** 温度曲线点。 */
    public record TemperaturePoint(
            int pointNo,
            BigDecimal temperature,
            LocalDateTime measuredAt) {
    }

    /** 迟到事件视图。 */
    public record LateEventView(
            long id,
            long siloId,
            String requestId,
            String eventType,
            String payload,
            LocalDateTime recordedAt) {
    }
}
