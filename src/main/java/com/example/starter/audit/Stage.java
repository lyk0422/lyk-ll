package com.example.starter.audit;

/**
 * 自动驾驶任务阶段，按声明顺序即依赖执行顺序：感知 → 定位 → 规划 → 控制。
 */
public enum Stage {

    PERCEPTION(0, "感知"),
    LOCALIZATION(1, "定位"),
    PLANNING(2, "规划"),
    CONTROL(3, "控制");

    private final int seqNo;
    private final String displayName;

    Stage(int seqNo, String displayName) {
        this.seqNo = seqNo;
        this.displayName = displayName;
    }

    public int getSeqNo() {
        return seqNo;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 按依赖顺序返回全部阶段，禁止跳过上游阶段执行下游。 */
    public static Stage[] ordered() {
        return new Stage[] {PERCEPTION, LOCALIZATION, PLANNING, CONTROL};
    }
}
