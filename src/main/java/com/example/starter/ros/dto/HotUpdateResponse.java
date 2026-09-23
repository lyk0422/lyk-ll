package com.example.starter.ros.dto;

import java.util.List;

/**
 * 热更新事务结果。
 *
 * @param transactionId 事务 ID
 * @param committed     是否原子提交
 * @param idempotentReplay true 表示该事务此前已完成，本次直接返回原结果
 * @param nodeResults   各节点校验/应用结果
 */
public record HotUpdateResponse(
        String transactionId,
        boolean committed,
        boolean idempotentReplay,
        List<NodeResult> nodeResults
) {
    /**
     * 单个节点的处理结果。
     *
     * @param node    节点名
     * @param status  ACCEPTED / REJECTED / VERSION_CONFLICT / TIMEOUT / ERROR
     * @param reason  拒绝或失败原因（成功时为 null）
     * @param version 提交后该节点的新版本（未提交时为当前/期望版本信息，可能为 null）
     */
    public record NodeResult(
            String node,
            String status,
            String reason,
            Long version
    ) {
        public static final String ACCEPTED = "ACCEPTED";
        public static final String REJECTED = "REJECTED";
        public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
        public static final String TIMEOUT = "TIMEOUT";
        public static final String ERROR = "ERROR";
    }
}
