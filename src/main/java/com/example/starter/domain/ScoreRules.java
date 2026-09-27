package com.example.starter.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 反复潜水虚构积分规则（软件验证模型，<b>不用于真实潜水减压</b>）。
 *
 * <ul>
 *   <li>每名潜水员首次积分为 0；</li>
 *   <li>出水残留积分 = 本次整数水下分钟数 + 下潜前积分，上限 {@value #MAX_SCORE}，超过则方案拒绝；</li>
 *   <li>水面每满 {@value #DECAY_UNIT_MINUTES} 分钟消减 {@value #DECAY_PER_UNIT} 分，最低 0；</li>
 *   <li>当前积分从最近已确认出水时刻起按水面时长派生；</li>
 *   <li>允许分钟数 = 原合成免减压表分钟数 - 当前积分，恰用尽合法，&lt;=0 拒绝；</li>
 *   <li>距上次出水不足 {@value #MIN_SURFACE_INTERVAL_MINUTES} 分钟禁止再潜，恰 60 分钟仅通过间隔门禁。</li>
 * </ul>
 */
public final class ScoreRules {

    /** 出水残留积分上限；方案预判超过即拒绝。 */
    public static final int MAX_SCORE = 100;
    /** 水面消减单元：每满 30 分钟消减一次。 */
    public static final int DECAY_UNIT_MINUTES = 30;
    /** 每个水面消减单元消减的积分。 */
    public static final int DECAY_PER_UNIT = 5;
    /** 距最近出水的最小水面间隔，分钟；恰 60 通过间隔门禁。 */
    public static final int MIN_SURFACE_INTERVAL_MINUTES = 60;

    private ScoreRules() {
    }

    /** 水面经历的完整消减单元数（每满 30 分钟一个，不足不计）。 */
    public static int decaySteps(long surfaceMinutes) {
        if (surfaceMinutes <= 0) {
            return 0;
        }
        return (int) (surfaceMinutes / DECAY_UNIT_MINUTES);
    }

    /** 从出水残留积分与水面时长派生当前积分：每满 30 分钟减 5，最低 0。 */
    public static int currentScore(int residualScore, long surfaceMinutes) {
        int current = residualScore - decaySteps(surfaceMinutes) * DECAY_PER_UNIT;
        return Math.max(0, current);
    }

    /** 从出水时刻到方案/查询时刻的水面整分钟数（负数归 0）。 */
    public static long surfaceMinutes(Instant lastSurfacedAt, Instant at) {
        if (lastSurfacedAt == null) {
            return 0;
        }
        long minutes = Duration.between(lastSurfacedAt, at).toMinutes();
        return Math.max(0, minutes);
    }

    /** 间隔门禁：水面分钟数恰 60 合法，不足拒绝。 */
    public static boolean intervalPassed(long surfaceMinutes) {
        return surfaceMinutes >= MIN_SURFACE_INTERVAL_MINUTES;
    }

    /** 单人允许分钟数 = 合成免减压表分钟 - 当前积分；为 0 恰可潜 0 分钟（业务上 &lt;=0 拒绝新方案）。 */
    public static int allowedMinutes(int ndlMinutes, int currentScore) {
        return ndlMinutes - currentScore;
    }

    /** 方案/出水预判的出水残留积分；超过 100 由 {@link #residualWithinCap} 判为拒绝。 */
    public static int residualAfterDive(int currentScore, int bottomMinutes) {
        return currentScore + bottomMinutes;
    }

    /** 出水残留积分是否在上限内（恰 100 合法）。 */
    public static boolean residualWithinCap(int residual) {
        return residual <= MAX_SCORE;
    }
}
