package com.example.starter.web.dto;

/**
 * 热备切换双确认响应。switched 为 true 表示本次确认使两名不同操作员齐备并已原子切换供电组合。
 *
 * @param replacementId 接替单ID
 * @param switched      本次是否完成原子切换
 * @param status        接替单当前状态
 * @param version       接替单当前版本号（仅切换成功加1；单人登记不变更版本）
 * @param evidence      切换前后容量证据，未切换为空
 */
public record ReplacementSwitchResponse(long replacementId, boolean switched, String status, int version,
        ReplacementSwitchEvidence evidence) {
}
