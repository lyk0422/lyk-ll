package com.example.starter.ros.dto;

/**
 * 参数事件流中的一条事件，游标在全库范围内单调递增。
 *
 * @param cursor        单调游标，从 1 开始
 * @param transactionId 产生该事件的事务 ID
 * @param type          COMMITTED / REJECTED_PRECHECK / ROLLBACK 等
 * @param node          关联节点（事务级事件可含汇总信息，此处为主要目标节点）
 * @param payload       事件内容（JSON 字符串）
 * @param createdAtMs   事件创建时间（毫秒，Asia/Shanghai 业务时区）
 */
public record ParamEvent(
        long cursor,
        String transactionId,
        String type,
        String node,
        String payload,
        long createdAtMs
) {
    public static final String TYPE_COMMITTED = "COMMITTED";
}
