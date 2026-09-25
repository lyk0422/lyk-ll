package com.example.starter.dto;

import java.util.List;

/**
 * API 请求/响应载体。金额字段以元字符串传输（如 "129.00"），服务端精确到分。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 创建账户请求。 */
    public record CreateAccountRequest(String accountNo, String currency) {
    }

    /** 账户视图。金额单位：元字符串；ratioBp 单位：基点，无负债为 null。 */
    public record AccountView(long id, String accountNo, String currency,
                              String totalAssets, String totalLiabilities,
                              Integer ratioBp, String status,
                              MarginCallView openMarginCall, List<Long> pendingLiquidationOrders) {
    }

    /** 追保通知视图。 */
    public record MarginCallView(long id, int ratioBp, String triggerAt, String deadline,
                                 String status, int mergeCount) {
    }

    /** 确认快照请求：总资产与负债明细（元字符串）。 */
    public record SnapshotRequest(String totalAssets, List<String> liabilities) {
    }

    /** 快照确认响应：action 为 NONE / MARGIN_CALL / DIRECT_LIQUIDATION / RECOVERED。 */
    public record SnapshotResponse(long snapshotId, Integer ratioBp, String action,
                                   Long marginCallId, Long liquidationOrderId) {
    }

    /** 追加保证金入账请求。 */
    public record DepositRequest(String requestId, String amount, String currency) {
    }

    /** 入账响应；duplicated=true 表示幂等重放，返回的是首次入账结果。 */
    public record DepositResponse(long depositId, String requestId, boolean duplicated,
                                  String amount, String currency, Integer ratioBpAfter,
                                  Long resolvedMarginCallId, List<Long> cancelledOrderIds,
                                  String createdAt) {
    }

    /** 逾期扫描响应：新生成的强平指令ID。 */
    public record ProcessDueResponse(List<Long> createdOrderIds) {
    }

    /** 强平执行响应。 */
    public record LiquidationView(long orderId, long accountId, String reason, String status,
                                  List<ItemView> items, Integer ratioBpAfter) {
    }

    /** 平仓明细视图。 */
    public record ItemView(long liabilityId, String amount) {
    }

    /** 错误响应。 */
    public record ErrorResponse(String message) {
    }
}
