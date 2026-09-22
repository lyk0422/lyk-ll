package com.example.starter.ros.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * ROS 2 受管节点生命周期状态。
 * 合法迁移(相邻迁移):
 * UNCONFIGURED -> INACTIVE(configure)
 * INACTIVE -> UNCONFIGURED(cleanup)
 * INACTIVE -> ACTIVE(activate)
 * ACTIVE -> INACTIVE(deactivate)
 * 三种主状态均可 -> FINALIZED(shutdown)。
 */
public enum LifecycleState {
    UNCONFIGURED,
    INACTIVE,
    ACTIVE,
    FINALIZED;

    private static final Set<LifecycleState> TERMINAL = EnumSet.of(FINALIZED);

    /**
     * 判断从当前状态迁移到目标状态是否为合法的相邻生命周期迁移。
     */
    public boolean canTransitionTo(LifecycleState target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case UNCONFIGURED -> target == INACTIVE || target == FINALIZED;
            case INACTIVE -> target == UNCONFIGURED || target == ACTIVE || target == FINALIZED;
            case ACTIVE -> target == INACTIVE || target == FINALIZED;
            case FINALIZED -> false;
        };
    }

    public boolean isFinal() {
        return TERMINAL.contains(this);
    }

    /**
     * 按名称解析状态,大小写不敏感;非法值返回 null 由调用方按 400 处理。
     */
    public static LifecycleState valueOfSafe(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
