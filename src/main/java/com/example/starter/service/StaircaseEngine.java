package com.example.starter.service;

/**
 * 受控分段阶梯生成（纯逻辑，可独立单测）。
 * 规则：每分钟向目标移动不超过升降上限（正整数 kW），最后一步恰到目标（不越过）；
 * 到达目标后的剩余分钟保持目标值。起始、目标均为整数 kW，故每级阶梯均为整数。
 */
public final class StaircaseEngine {

    private StaircaseEngine() {
    }

    /**
     * 从起始功率到达目标所需分钟数：ceil(|target-start| / ramp)；恰等上限整除时恰为该商。
     * 起始已等于目标时为 0。
     */
    public static int neededMinutes(int startKw, int targetKw, int rampKwPerMin) {
        if (rampKwPerMin <= 0) {
            throw new IllegalArgumentException("每分钟升降上限必须为正整数：" + rampKwPerMin);
        }
        long dist = Math.abs((long) targetKw - startKw);
        if (dist == 0) {
            return 0;
        }
        return (int) ((dist + rampKwPerMin - 1) / rampKwPerMin);
    }

    /**
     * 第 minuteNo 分钟（从 1 起）的冻结阶梯功率：向目标移动 min(序号×上限, 总距离)，
     * 到达后保持目标值；最后一步因取 min 而恰到目标。
     */
    public static int powerAt(int startKw, int targetKw, int rampKwPerMin, int minuteNo) {
        if (minuteNo < 1) {
            throw new IllegalArgumentException("分钟序号从 1 起：" + minuteNo);
        }
        if (rampKwPerMin <= 0) {
            throw new IllegalArgumentException("每分钟升降上限必须为正整数：" + rampKwPerMin);
        }
        long dist = Math.abs((long) targetKw - startKw);
        long moved = Math.min((long) minuteNo * rampKwPerMin, dist);
        long value = targetKw > startKw ? startKw + moved : startKw - moved;
        return (int) value;
    }
}
