package com.example.starter.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * API 请求/响应报文。时间均为 UTC；日期为 UTC 日界。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 新建电梯请求 */
    public record ElevatorRequest(@NotBlank String code, String name, @NotNull LocalDate commissionedDay) {
    }

    /** 新建人员请求 */
    public record StaffRequest(@NotBlank String name, @NotBlank String certNo, boolean certified) {
    }

    /** 接报请求；reportedAt 缺省取服务端 UTC 当前时间 */
    public record ReportRequest(@NotBlank String requestId, @NotNull Long elevatorId, Instant reportedAt) {
    }

    /** 派单请求 */
    public record DispatchRequest(@NotBlank String requestId, @NotEmpty List<Long> staffIds) {
    }

    /** 到场请求；arrivedAt 缺省取服务端 UTC 当前时间 */
    public record ArriveRequest(Instant arrivedAt) {
    }

    /** 施救请求：须恰两名持证人员 */
    public record RescueRequest(@NotEmpty List<Long> staffIds, Instant rescuedAt) {
    }

    /** 复检请求：合格后与恢复运行同一事务完成 */
    public record RecheckRequest(boolean inspectionPassed, Instant recheckedAt) {
    }

    /** 回访结案请求（终态） */
    public record CloseRequest(@NotBlank String requestId, Instant closedAt) {
    }

    /** 维保登记请求；rescueRequestId 指向已结案救援单时仅记迟到事件 */
    public record MaintenanceRequest(@NotBlank String requestId, @NotNull Long elevatorId,
                                     @NotNull LocalDate performedDay, String rescueRequestId, String note) {
    }

    /** 电梯级复检请求（维保锁定/强制停梯复梯） */
    public record ElevatorRecheckRequest(boolean inspectionPassed) {
    }

    /** 履约派发/续派请求：指定两名持证人员与半开作业窗 [windowStart,windowEnd) */
    public record ObligationAllocateRequest(@NotBlank String requestId, @NotNull Long expectedVersion,
                                            @NotEmpty List<@NotNull Long> staffIds,
                                            @NotNull Instant windowStart, @NotNull Instant windowEnd) {
    }

    /** 履约尝试开始请求 */
    public record ObligationStartRequest(@NotBlank String requestId, @NotNull Long expectedVersion,
                                         @NotNull Instant startedAt) {
    }

    /** 尝试清单回报请求（开始/完成共用）；doneItems 为已完成检查项编码集合 */
    public record ObligationItemsRequest(@NotBlank String requestId, @NotNull Long expectedVersion,
                                         @NotEmpty List<@NotBlank String> doneItems,
                                         @NotNull Instant at) {
    }

    /** 履约尝试完成请求：全部清单齐备且双人共同确认 */
    public record ObligationCompleteRequest(@NotBlank String requestId, @NotNull Long expectedVersion,
                                            @NotEmpty List<@NotNull Long> confirmerIds,
                                            @NotEmpty List<@NotBlank String> doneItems,
                                            @NotNull Instant at) {
    }

    /** 义务视图 */
    public record ObligationView(long id, long elevatorId, int periodIndex, LocalDate baseDay, Instant dueAt,
                                 String status, int version, Long activeAttemptId,
                                 Long lastValidRecordId, Long fulfilledRecordId, Instant fulfilledAt,
                                 List<String> checklist, List<ObligationAttemptView> attempts) {
    }

    /** 尝试视图 */
    public record ObligationAttemptView(long id, int seq, String status, int version,
                                        String allocateRequestId, String startRequestId,
                                        String completeRequestId, Instant allocatedAt, Instant startedAt,
                                        Instant suspendedAt, Instant completedAt, Long suspendRescueId,
                                        List<Long> staffIds, Instant windowStart, Instant windowEnd,
                                        List<String> doneItems) {
    }

    /** 轨迹事件视图 */
    public record ObligationEventView(long id, Long attemptId, String event, String detail, Instant createdAt) {
    }

    /** 救援单视图 */
    public record RescueView(long id, long elevatorId, String requestId, String status,
                             Instant reportedAt, Instant dispatchedAt, Instant arrivedAt,
                             Instant rescuedAt, Instant restoredAt, Instant closedAt,
                             boolean timeoutEscalated, Instant escalatedAt) {
    }

    /** 电梯视图 */
    public record ElevatorView(long id, String code, String name, String status,
                               boolean keySupervision, LocalDate lastMaintenanceDay) {
    }

    /** 停复梯链条目 */
    public record ChainEntryView(long id, String action, String reason, Long rescueId, Instant createdAt) {
    }

    /** 维保台账条目 */
    public record MaintenanceView(long id, long elevatorId, LocalDate performedDay, LocalDate dueDay,
                                  String result, String note, Instant createdAt) {
    }

    /** 救援时间线事件 */
    public record EventView(long id, String event, String detail, Instant createdAt) {
    }
}
