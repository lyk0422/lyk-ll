package com.example.starter.battery.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API 请求体定义。
 */
public final class Requests {

    private Requests() {
    }

    /** 建档请求。 */
    public record RegisterBatteryRequest(
            @NotBlank(message = "电池编码不能为空") String code,
            @NotBlank(message = "站点不能为空") String stationId) {
    }

    /** 入站请求（幂等）。 */
    public record InboundRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "电池编码不能为空") String code,
            @NotBlank(message = "站点不能为空") String stationId) {
    }

    /** 检测请求（幂等，复检须使用新批次号）。 */
    public record InspectionRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "电池编码不能为空") String code,
            @NotNull(message = "检测批次不能为空") Long batch,
            @NotNull(message = "SOC 不能为空") @Min(0) @Max(100) Integer soc,
            @NotNull(message = "健康度不能为空") @Min(0) @Max(100) Integer health) {
    }

    /** 换出请求（幂等）。 */
    public record SwapOutRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "电池编码不能为空") String code,
            @NotBlank(message = "车辆不能为空") String vehicleId) {
    }

    /** 调拨发起请求（幂等）。 */
    public record TransferRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "电池编码不能为空") String code,
            @NotBlank(message = "调出站点不能为空") String fromStationId,
            @NotBlank(message = "调入站点不能为空") String toStationId) {
    }

    /** 调拨签收请求（幂等）。 */
    public record SignRequest(
            @NotBlank(message = "requestId 不能为空") String requestId) {
    }
}
