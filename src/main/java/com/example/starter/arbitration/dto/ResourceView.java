package com.example.starter.arbitration.dto;

/**
 * 资源当前状态视图。
 *
 * @param resourceId         资源标识
 * @param holderControllerId 当前持有者控制器ID，NULL 表示空闲
 * @param holderPriority     当前持有者优先级，NULL 表示空闲
 * @param safeMode           安全降级模式
 * @param safeApplied        本持有周期内是否已执行安全降级
 * @param lastCommandSeq     最近已接受指令序列号，NULL 表示无
 * @param lastCommandTs      最近已接受指令时戳（epoch毫秒），NULL 表示无
 */
public record ResourceView(
        String resourceId,
        String holderControllerId,
        Integer holderPriority,
        String safeMode,
        boolean safeApplied,
        Long lastCommandSeq,
        Long lastCommandTs) {
}
