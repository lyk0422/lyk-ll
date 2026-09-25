package com.example.starter.ticket;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 倒闸操作票接口报文定义。
 */
public final class TicketDtos {

    private TicketDtos() {
    }

    /** 拟票步骤：操作类型、设备与预期状态。 */
    public record StepSpec(
            @NotBlank String operationType,
            @NotBlank String deviceId,
            @NotBlank String expectedState) {
    }

    /** 拟票请求。 */
    public record CreateTicketRequest(
            @NotBlank String requestId,
            @NotBlank String intervalId,
            @NotBlank String title,
            @NotEmpty List<@Valid StepSpec> steps) {
    }

    /** 审核请求。 */
    public record ReviewRequest(
            @NotBlank String requestId,
            @NotBlank String reviewerId) {
    }

    /** 预发请求。 */
    public record IssueRequest(
            @NotBlank String requestId) {
    }

    /** 步骤双人确认请求。 */
    public record ConfirmStepRequest(
            @NotBlank String requestId,
            @NotBlank String operatorId,
            @NotBlank String guardianId) {
    }

    /** 归档请求。 */
    public record ArchiveRequest(
            @NotBlank String requestId) {
    }

    /** 步骤视图。 */
    public record StepView(
            int seq,
            String operationType,
            String deviceId,
            String expectedState,
            String status,
            String operatorId,
            String guardianId,
            Instant executedAt,
            boolean verified) {
    }

    /** 操作票视图。 */
    public record TicketView(
            long ticketId,
            String intervalId,
            String title,
            String status,
            String reviewerId,
            Instant issuedAt,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 闭锁命中视图。 */
    public record HitView(
            long id,
            Integer stepSeq,
            String ruleCode,
            String detail,
            Instant createdAt) {
    }

    /** 操作票详情：票、步序轨迹与闭锁命中，可复算。 */
    public record TicketDetailView(
            TicketView ticket,
            List<StepView> steps,
            List<HitView> interlockHits) {
    }

    /** 步骤确认结果。 */
    public record ConfirmView(
            long ticketId,
            int seq,
            String stepStatus,
            String ticketStatus) {
    }
}
