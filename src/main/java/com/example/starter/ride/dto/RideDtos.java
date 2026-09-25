package com.example.starter.ride.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * API 请求与响应 DTO 集合。所有写操作请求均携带 requestId 用于幂等。
 */
public final class RideDtos {

    private RideDtos() {
    }

    /** 日检清单项。 */
    public record CheckItem(
            @NotBlank(message = "检查项名称不能为空") String itemName,
            @NotNull(message = "检查项合格标记不能为空") Boolean passed) {
    }

    /** 创建设备请求（合成数据）。 */
    public record CreateRideRequest(
            @NotBlank(message = "设备名称不能为空") String name,
            @Positive(message = "载荷阈值必须为正数") int maxLoad,
            @Positive(message = "速度阈值必须为正数") int maxSpeed) {
    }

    /** 日检请求：清单逐项合格方可试运行。 */
    public record DailyCheckRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotEmpty(message = "日检清单不能为空") List<@Valid CheckItem> items) {
    }

    /** 试运行请求：normal=true 表示空载无异常。 */
    public record TrialRunRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotNull(message = "试运行结果不能为空") Boolean normal) {
    }

    /** 开放请求。 */
    public record OpenRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId) {
    }

    /** 安全装置失效上报：压杠、安全带与联锁装置任一失效整机立即停运。 */
    public record SafetyFaultRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotBlank(message = "安全装置名称不能为空") String device) {
    }

    /** 复检请求：故障停机后复检合格方可复开。 */
    public record RecheckRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId,
            @NotEmpty(message = "复检清单不能为空") List<@Valid CheckItem> items) {
    }

    /** 复开请求：同日复开最多2次。 */
    public record ReopenRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId) {
    }

    /** 闭园归档请求：归档为终态，重复归档返回幂等成功。 */
    public record ArchiveRequest(
            @NotBlank(message = "requestId不能为空") @Size(max = 64) String requestId) {
    }

    /** 运行监测读数（合成数据）。 */
    public record ReadingRequest(
            @NotNull(message = "载荷读数不能为空") Integer loadValue,
            @NotNull(message = "速度读数不能为空") Integer speedValue) {
    }

    /** 统一错误响应体。 */
    public record ErrorBody(String error, String message) {
    }
}
