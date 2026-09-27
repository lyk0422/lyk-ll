package com.example.starter.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 反复潜水积分规则（<b>虚构软件验证模型，不用于实际潜水减压</b>）。
 *
 * <ul>
 *   <li>每名潜水员首次积分 0；</li>
 *   <li>出水残留积分 = 本次整数水下分钟数 + 下潜前积分，上限 {@link #MAX_POINTS}=100，超过则方案拒绝；</li>
 *   <li>水面每满 {@link #SURFACE_STEP_MIN} 分钟消减 {@link #DECAY_PER_STEP} 分，最低 0；</li>
 *   <li>方案时从最近已确认出水时刻计算当前积分，允许分钟数 = 原合成免减压表分钟数 - 当前积分，
 *       恰用尽（允许分钟恰等于计划分钟）合法，结果 &le; 0 时拒绝新方案；</li>
 *   <li>距上次出水不足 {@link #SURFACE_INTERVAL_MIN} 分钟禁止再潜（恰 60 仅通过间隔门禁，
 *       仍须 2 小时观察完成及 24 小时累计门禁）。</li>
 * </ul>
 */
public final class PointsRules {

    /** 出水残留积分上限；恰 100 合法，超过拒绝。 */
    public static final int MAX_POINTS = 100;
    /** 水面消减步长：每满 30 分钟。 */
    public static final int SURFACE_STEP_MIN = 30;
    /** 每个完整步长消减的积分。 */
    public static final int DECAY_PER_STEP = 5;
    /** 两次受控下潜之间距上次出水的最小间隔，分钟；恰 60 仅通过间隔门禁。 */
    public static final int SURFACE_INTERVAL_MIN = 60;

    private PointsRules() {
    }

    /**
     * 按水面停留时长复算当前积分：从锚点（最近已确认出水时刻）起每满 30 分钟减 5，最低 0。
     *
     * @param residualPoints 锚点时刻的出水残留积分
     * @param anchor         锚点出水时刻；为空表示尚无受控出水，当前积分恒为 0
     * @param now            复算时刻（方案/下潜时刻）
     */
    public static int currentPoints(int residualPoints, Instant anchor, Instant now) {
        if (anchor == null || residualPoints <= 0) {
            return 0;
        }
        long elapsedMin = Duration.between(anchor, now).toMinutes();
        if (elapsedMin <= 0) {
            return residualPoints;
        }
        long steps = elapsedMin / SURFACE_STEP_MIN;
        long decayed = residualPoints - steps * DECAY_PER_STEP;
        return (int) Math.max(0, decayed);
    }

    /** 预测本次出水后的残留积分（下限截断到 0，不做上限拒绝；上限由 {@link #checkExitCap} 判定）。 */
    public static int exitPoints(int pointsBeforeDive, int bottomMinutes) {
        return Math.max(0, pointsBeforeDive + bottomMinutes);
    }

    /**
     * 出水积分上限校验：本次水下分钟 + 下潜前积分不得超过 100（恰 100 合法）。
     *
     * @return 出水残留积分；超过上限抛业务拒绝
     */
    public static int checkExitCap(int pointsBeforeDive, int bottomMinutes) {
        int exit = pointsBeforeDive + bottomMinutes;
        if (exit > MAX_POINTS) {
            throw new PointsRejectedException("出水残留积分 " + exit + " 超过上限 " + MAX_POINTS
                    + "（下潜前 " + pointsBeforeDive + " + 水下 " + bottomMinutes + "），方案拒绝");
        }
        return exit;
    }

    /**
     * 间隔门禁：计划下潜时刻距最近已确认出水时刻须 &ge; 60 分钟（恰 60 通过间隔门禁）。
     * 无历史出水（首次）直接通过。
     */
    public static void checkSurfaceInterval(Instant lastSurfacedAt, Instant plannedDescentAt) {
        if (lastSurfacedAt == null) {
            return;
        }
        long gap = Duration.between(lastSurfacedAt, plannedDescentAt).toMinutes();
        if (gap < SURFACE_INTERVAL_MIN) {
            throw new PointsRejectedException("距上次出水 " + gap + " 分钟，不足 "
                    + SURFACE_INTERVAL_MIN + " 分钟，禁止再潜");
        }
    }

    /**
     * 计算方案允许的水下分钟数 = 合成免减压表分钟 - 当前积分；恰用尽合法，&le; 0 拒绝。
     *
     * @param ndlMinutes    原合成免减压表分钟数
     * @param currentPoints 当前积分
     * @return 允许分钟数（正数）
     */
    public static int allowedBottomMinutes(int ndlMinutes, int currentPoints) {
        int allowed = ndlMinutes - currentPoints;
        if (allowed <= 0) {
            throw new PointsRejectedException("当前积分 " + currentPoints
                    + "，允许水下分钟 " + allowed + "（NDL " + ndlMinutes + " - 积分 "
                    + currentPoints + "）小于等于 0，拒绝新方案");
        }
        return allowed;
    }

    /** 计划底时间是否在允许范围内：恰用尽（plan==allowed）合法。 */
    public static boolean withinAllowed(int plannedBottomMin, int allowedBottomMin) {
        return plannedBottomMin <= allowedBottomMin;
    }

    /** 积分业务拒绝（对应 HTTP 422）。 */
    public static class PointsRejectedException extends RuntimeException {
        public PointsRejectedException(String message) {
            super(message);
        }
    }
}
