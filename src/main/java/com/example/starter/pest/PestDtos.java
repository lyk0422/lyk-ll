package com.example.starter.pest;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 虫情处置链接口报文。
 */
public final class PestDtos {

    private PestDtos() {
    }

    /**
     * 虫情上报请求。
     *
     * @param requestId 幂等请求标识
     * @param reporter 上报人
     * @param description 虫情描述，可空
     */
    public record ReportRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "上报人不能为空") String reporter,
            String description) {
    }

    /**
     * 通用操作请求（确认处置链等）。
     *
     * @param requestId 幂等请求标识
     * @param operator 操作人
     */
    public record OperatorRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "操作人不能为空") String operator) {
    }

    /**
     * 新增虫迹登记请求。
     *
     * @param requestId 幂等请求标识
     * @param description 虫迹描述，可空
     */
    public record TraceRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            String description) {
    }

    /**
     * 解除确认请求。
     *
     * @param requestId 幂等请求标识
     * @param side 确认方：DISINFECTION 消杀方 / STORAGE 库房方
     */
    public record SideRequest(
            @NotBlank(message = "requestId 不能为空") String requestId,
            @NotBlank(message = "确认方不能为空") String side) {
    }

    /**
     * 处置链状态响应。
     *
     * @param caseId 处置链主键
     * @param zoneCode 库区编码
     * @param status 当前状态
     */
    public record CaseResponse(long caseId, String zoneCode, String status) {
    }

    /**
     * 虫迹登记响应。
     *
     * @param traceId 虫迹记录主键
     * @param caseId 所属处置链
     */
    public record TraceResponse(long traceId, long caseId) {
    }

    /**
     * 整体预防性消杀评估响应。
     *
     * @param zoneCodes 涉及相邻库区编码，逗号分隔
     * @param triggerCaseId 触发的处置链
     */
    public record EvaluationResponse(long id, String zoneCodes, long triggerCaseId, String reason,
            LocalDateTime createdAt) {
    }

    /**
     * 处置链详情响应；时间字段均为 UTC，空表示该阶段尚未发生。
     */
    public record CaseDetailResponse(long caseId, String zoneCode, String status, String reporter,
            String description, LocalDateTime reportedAt, LocalDateTime confirmedAt,
            LocalDateTime treatedAt, LocalDateTime releaseAppliedAt,
            LocalDateTime disinfectConfirmedAt, LocalDateTime storageConfirmedAt,
            LocalDateTime releasedAt) {
    }
}
