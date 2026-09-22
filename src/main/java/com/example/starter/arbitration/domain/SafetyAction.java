package com.example.starter.arbitration.domain;

/**
 * 资源失去有效写权限持有者时一次性进入的安全状态。
 */
public enum SafetyAction {
    /** 保持当前位置/速度指令。 */
    HOLD,
    /** 令目标速度为零（刹车停车）。 */
    ZERO_VELOCITY,
    /** 运动到预设位置。 */
    PRESET_POSITION
}
