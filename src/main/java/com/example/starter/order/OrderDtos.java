package com.example.starter.order;

import java.time.Instant;
import java.util.List;

/**
 * 倒闸操作票 API 请求/响应报文定义。
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** 拟票请求：requestId 为幂等键，指纹覆盖全部业务字段。 */
    public record DraftRequest(String requestId, String intervalId, String title,
                               String createdBy, List<StepSpec> steps) {
    }

    /** 拟票步骤定义。 */
    public record StepSpec(int seq, String deviceId, String action, String expectedState) {
    }

    /** 审核请求。 */
    public record ReviewRequest(String reviewedBy) {
    }

    /** 预发请求：requestId 为幂等键。 */
    public record IssueRequest(String requestId) {
    }

    /** 步确认请求：requestId 为幂等键，操作人与监护人须为不同两人。 */
    public record ConfirmRequest(String requestId, String operator, String guardian) {
    }

    /** 紧急解锁请求：须双人授权。 */
    public record UnlockRequest(String authorizedBy1, String authorizedBy2,
                                String deviceId, String reason) {
    }

    /** 操作票概要响应。 */
    public record OrderView(long orderId, String orderNo, String intervalId, String title,
                            String status, String createdBy, String reviewedBy,
                            Instant issuedAt, Instant createdAt) {
    }

    /** 步骤视图。 */
    public record StepView(int seq, String deviceId, String action, String expectedState,
                           String status, String operator, String guardian, Instant executedAt) {
    }

    /** 操作票详情响应。 */
    public record OrderDetail(OrderView order, List<StepView> steps) {
    }

    /** 步确认响应。 */
    public record ConfirmView(long orderId, int seq, String stepStatus, String orderStatus,
                              String ruleCode, String message) {
    }

    /** 解锁响应。 */
    public record UnlockView(long unlockId, long orderId, String deviceId,
                             Instant unlockedAt, Instant restoredAt) {
    }

    /** 轨迹事件视图。 */
    public record EventView(String eventType, String actor, String detail, Instant eventAt) {
    }

    /** 闭锁命中视图。 */
    public record HitView(int stepSeq, String ruleCode, String detail, Instant hitAt) {
    }

    /** 解锁记录视图。 */
    public record UnlockRecordView(String deviceId, String authorizedBy1, String authorizedBy2,
                                   String reason, Instant unlockedAt, Instant restoredAt) {
    }

    /** 轨迹响应：事件、闭锁命中与解锁记录均可复算。 */
    public record TraceView(List<EventView> events, List<HitView> interlockHits,
                            List<UnlockRecordView> unlocks) {
    }
}
