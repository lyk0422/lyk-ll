package com.example.starter.trajectory.domain;

/**
 * 规则编号。四类规则并行执行：
 * 1 时间连续性；2-4 动力学（速度/加速度/曲率）；5 道路边界；6 碰撞。
 */
public enum RuleNumber {
    TIME_CONTINUITY(1),
    SPEED(2),
    ACCELERATION(3),
    CURVATURE(4),
    ROAD_BOUNDARY(5),
    COLLISION(6);

    private final int code;

    RuleNumber(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
