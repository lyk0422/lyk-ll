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

    /** 维保义务扫描请求；now 缺省取服务端 UTC 当前时间 */
    public record PlanScanRequest(@NotBlank String requestId, @NotNull Long elevatorId, Instant now) {
    }

    /** 维保尝试派发请求：恰两名持证人员 + 半开作业窗 [windowStart, windowEnd) */
    public record AttemptDispatchRequest(@NotBlank String requestId, @NotNull Long expectedVersion,
                                         @NotEmpty List<Long> staffIds,
                                         @NotNull Instant windowStart, @NotNull Instant windowEnd) {
    }

    /** 清单项回报请求；doneAt 缺省取服务端 UTC 当前时间 */
    public record ChecklistRequest(@NotBlank String requestId, @NotNull Long expectedVersion,
                                   @NotBlank String item, Instant doneAt) {
    }

    /** 完成确认请求：须双人共同确认；confirmedAt 缺省取服务端 UTC 当前时间 */
    public record ConfirmRequest(@NotBlank String requestId, @NotNull Long expectedVersion,
                                 @NotNull Long staffId, Instant confirmedAt) {
    }

    /** 维保履约义务视图 */
    public record ObligationView(long id, long elevatorId, Long baseMaintenanceId, Instant dueAt,
                                 String status, long version, Instant createdAt, Instant fulfilledAt) {
    }

    /** 维保履约尝试视图 */
    public record AttemptView(long id, long obligationId, int attemptNo, String status,
                              Instant windowStart, Instant windowEnd,
                              int checklistTotal, int checklistDone,
                              Instant suspendedAt, Long suspendRescueId, Instant completedAt,
                              long version, Instant createdAt) {
    }

    /** 尝试人员确认条目 */
    public record AttemptStaffView(long staffId, boolean confirmed, Instant confirmedAt) {
    }

    /** 清单项条目 */
    public record ChecklistItemView(String item, boolean done, Instant doneAt) {
    }

    /** 尝试详情：含人员确认与清单轨迹 */
    public record AttemptDetailView(AttemptView attempt, List<AttemptStaffView> staff,
                                    List<ChecklistItemView> checklist) {
    }

    /** 扫描结果：created=false 表示尚未进入派发窗口（到期前 24 小时）未创建义务 */
    public record ScanResultView(Long obligationId, long elevatorId, Instant dueAt, String status,
                                 boolean created, Long version) {
    }
}
