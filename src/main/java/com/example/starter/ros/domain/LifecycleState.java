package com.example.starter.ros.domain;

/**
 * ROS 2 风格生命周期状态。
 *
 * <p>合法相邻迁移：
 * <ul>
 *     <li>{@code UNCONFIGURED -> INACTIVE}（configure）</li>
 *     <li>{@code INACTIVE -> ACTIVE}（activate）</li>
 *     <li>{@code ACTIVE -> INACTIVE}（deactivate）</li>
 *     <li>{@code INACTIVE -> UNCONFIGURED}（cleanup）</li>
 *     <li>任意非终结状态 {@code -> FINALIZED}（shutdown）</li>
 * </ul>
 */
public enum LifecycleState {
    UNCONFIGURED,
    INACTIVE,
    ACTIVE,
    FINALIZED;

    /**
     * 判断目标状态是否为当前状态的合法相邻迁移。
     *
     * @param target 目标状态
     * @return 合法返回 true，跳级/回环等非法迁移返回 false
     */
    public boolean canTransitionTo(LifecycleState target) {
        return switch (this) {
            case UNCONFIGURED -> target == INACTIVE || target == FINALIZED;
            case INACTIVE -> target == ACTIVE || target == UNCONFIGURED || target == FINALIZED;
            case ACTIVE -> target == INACTIVE || target == FINALIZED;
            case FINALIZED -> false;
        };
    }
}
