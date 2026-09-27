package com.example.starter.support;

import java.time.Duration;
import java.time.Instant;

/**
 * 反复潜水余量积分的纯合成规则（虚构模型，仅供软件验证，不用于真实潜水减压）。
 *
 * <ul>
 *   <li>每名潜水员首次积分 0；</li>
 *   <li>出水残留积分 = 本次整数水下分钟数 + 下潜前积分，上限 100，超过则方案拒绝；</li>
 *   <li>水面每满 30 分钟消减 5 分，最低 0（不满 30 分钟的零头不消减）；</li>
 *   <li>允许分钟数 = 合成免减压表分钟数 - 当前积分；恰用尽合法，&lt;=0 拒绝新方案；</li>
 *   <li>距上次确认出水不足 60 分钟禁止再潜；恰 60 分钟仅通过间隔门禁。</li>
 * </ul>
 */
public final class ResidualRules {

    /** 出水残留积分上限；下潜前积分加本次水下分钟超过该值则方案拒绝。 */
    public static final int MAX_POINTS = 100;
    /** 水面消减块时长，分钟；每满一块消减一次。 */
    public static final int SURFACE_BLOCK_MIN = 30;
    /** 每个水面消减块消减的积分。 */
    public static final int SURFACE_DECAY_POINTS = 5;
    /** 距上次确认出水的最小再潜间隔，分钟；恰 60 分钟仅通过间隔门禁。 */
    public static final int MIN_SURFACE_INTERVAL_MIN = 60;

    private ResidualRules() {
    }

    /**
     * 方案时刻当前积分：从最近确认出水时刻起，水面每满 30 分钟消减 5 分，最低 0。
     * 不满 30 分钟的零头不消减。{@code surfaceInterval} 不应为负（负间隔由调用方按业务拒绝）。
     */
    public static int decayedPoints(int exitResidualPoints, Duration surfaceInterval) {
        long minutes = Math.max(0, surfaceInterval.toMinutes());
        long blocks = minutes / SURFACE_BLOCK_MIN;
        return Math.max(0, exitResidualPoints - (int) (blocks * SURFACE_DECAY_POINTS));
    }

    /** 出水残留积分 = 下潜前积分 + 本次整数水下分钟，上限 100。 */
    public static int exitResidual(int preDivePoints, int bottomMin) {
        return Math.min(MAX_POINTS, preDivePoints + bottomMin);
    }

    /** 方案拒绝条件一：下潜前积分加计划水下分钟超过上限 100。 */
    public static boolean exceedsCap(int preDivePoints, int plannedBottomMin) {
        return preDivePoints + plannedBottomMin > MAX_POINTS;
    }

    /** 允许分钟数 = 免减压表分钟数 - 当前积分；恰用尽（=计划底时间）合法。 */
    public static int allowedMinutes(int ndlMinutes, int currentPoints) {
        return ndlMinutes - currentPoints;
    }

    /** 方案拒绝条件二：允许分钟数小于等于 0。 */
    public static boolean noAllowedTime(int ndlMinutes, int currentPoints) {
        return allowedMinutes(ndlMinutes, currentPoints) <= 0;
    }

    /**
     * 间隔门禁：返回距最近出水的完整分钟数。恰 60 通过，不足 60（含 59）拒绝。
     * 首潜（无最近出水时刻）由调用方直接放行，不调用本方法。
     */
    public static long surfaceIntervalMinutes(Instant lastSurfacedAt, Instant planAt) {
        return Duration.between(lastSurfacedAt, planAt).toMinutes();
    }
}
