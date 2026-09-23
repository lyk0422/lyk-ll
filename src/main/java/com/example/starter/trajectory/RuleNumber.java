package com.example.starter.trajectory;

/**
 * 安全规则编号，固定编号决定失败项的稳定排序顺序。
 * 时间连续性、动力学（速度/加速度/曲率）、道路、碰撞四组规则可并行执行，
 * 但输出统一按本枚举序号排列。
 */
public enum RuleNumber {

    /** R1 时间连续性：相邻轨迹点时间必须严格递增，间隔超过最大阈值即失败。 */
    TIME_CONTINUITY(1, "时间连续性"),

    /** R2 速度：分段速度不得超过最大速度，等于阈值合法。 */
    SPEED(2, "速度"),

    /** R3 加速度：分段加速度绝对值不得超过阈值，等于阈值合法。 */
    ACCELERATION(3, "加速度"),

    /** R4 曲率：三点外接圆曲率绝对值不得超过阈值，等于阈值合法。 */
    CURVATURE(4, "曲率"),

    /** R5 道路边界：点在边界上合法，越界且超过道路容差才失败。 */
    ROAD_BOUNDARY(5, "道路边界"),

    /** R6 碰撞：车与障碍物距离小于或等于安全距离即碰撞风险。 */
    COLLISION(6, "碰撞");

    private final int code;
    private final String displayName;

    RuleNumber(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public int getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 规则编号前缀，如 {@code "R2"}。 */
    public String id() {
        return "R" + code;
    }
}
