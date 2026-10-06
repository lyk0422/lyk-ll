package com.example.starter.web.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 线路相关请求与响应DTO。所有写操作请求均携带 requestId 用于幂等。
 */
public final class RouteDtos {

    private RouteDtos() {
    }

    /**
     * 创建线路申请请求。
     *
     * @param requestId        幂等请求ID
     * @param routeCode        线路编码，唯一
     * @param name             线路名称
     * @param vehicleId        候选车辆ID
     * @param stationSpacingM  站点间距，单位米
     * @param roadGrade        道路等级，数值越小等级越高
     * @param requiredSeats    线路所需座位数
     */
    public record CreateRouteRequest(@NotBlank String requestId,
                                     @NotBlank String routeCode,
                                     @NotBlank String name,
                                     @NotNull Long vehicleId,
                                     @Min(1) int stationSpacingM,
                                     @Min(1) @Max(10) int roadGrade,
                                     @Positive int requiredSeats) {
    }

    /**
     * 线路响应。
     */
    public record RouteResponse(long id, String routeCode, String name, long vehicleId,
                                int stationSpacingM, int roadGrade, int requiredSeats,
                                String status) {
    }

    /**
     * 安全评估请求；评估日期取当前 UTC 日期。
     */
    public record AssessmentRequest(@NotBlank String requestId) {
    }

    /**
     * 安全评估响应；通过时附带生成的资格信息。
     */
    public record AssessmentResponse(long assessmentId, long routeId, LocalDate assessmentDate,
                                     boolean spacingOk, boolean roadOk, boolean seatsOk,
                                     String result, String rejectReason,
                                     Long qualificationId, LocalDate validFrom, LocalDate validTo) {
    }

    /**
     * 启动线路执行请求。
     */
    public record StartRouteRequest(@NotBlank String requestId) {
    }

    /**
     * 学生上下车打卡请求。
     *
     * @param checkType BOARD 上车 / ALIGHT 下车
     */
    public record CheckInRequest(@NotBlank String requestId,
                                 @NotNull Long studentId,
                                 @NotBlank String checkType) {
    }

    /**
     * 打卡响应；deduplicated=true 表示命中学生+日期+类型去重，返回既有记录。
     */
    public record CheckInResponse(long checkInId, long studentId, long routeId,
                                  LocalDate checkDate, String checkType, boolean deduplicated) {
    }

    /**
     * 漏乘名单生成请求。
     */
    public record MissedBoardingRequest(@NotBlank String requestId) {
    }

    /**
     * 漏乘名单响应：实时生成合成通知记录后返回漏乘学生与通知ID。
     */
    public record MissedBoardingResponse(long routeId, LocalDate date,
                                         List<Long> missedStudentIds,
                                         List<Long> notificationIds) {
    }
}
