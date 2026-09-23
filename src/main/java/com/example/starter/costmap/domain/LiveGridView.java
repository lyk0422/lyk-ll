package com.example.starter.costmap.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态查询视图工作副本：静态格栅 + 当前获胜声明栅格 + 最终代价栅格。
 *
 * <p>所有更新均为区域增量：仅重算声明矩形向外扩展膨胀半径（再加 1 单元格安全边界）
 * 后覆盖的单元格，绝不整图重建。区域内单元格的获胜声明由全部候选声明按
 * {@link MergePolicy} 重新决胜；区域外单元格的获胜声明保持不变，仅在计算膨胀最近
 * 致命障碍距离时按既有获胜序号读取。</p>
 *
 * <p>致命障碍（膨胀源）= 静态格栅代价 254 的格，或获胜声明为 OCCUPY 的格。
 * CLEAR 声明只清除动态占据，不影响静态层及其膨胀。最终代价取
 * {@code max(静态代价, 动态原始代价, 膨胀代价)}。</p>
 */
public final class LiveGridView {

    private final int width;
    private final int height;
    private final double inscribedRadius;
    private final double inflationRadius;
    private final double costScalingFactor;
    private final int radiusCells;

    private final int[] staticCells;
    private final int[] liveCells;
    private final long[] winnerCells;

    /**
     * 直接装配视图（快照恢复或从持久化行构建时使用）。
     */
    public LiveGridView(int width, int height,
                        double inscribedRadius, double inflationRadius, double costScalingFactor,
                        int[] staticCells, int[] liveCells, long[] winnerCells) {
        this.width = width;
        this.height = height;
        this.inscribedRadius = inscribedRadius;
        this.inflationRadius = inflationRadius;
        this.costScalingFactor = costScalingFactor;
        this.radiusCells = (int) Math.ceil(inflationRadius) + 1;
        this.staticCells = staticCells;
        this.liveCells = liveCells;
        this.winnerCells = winnerCells;
    }

    /**
     * 创建初始视图：无动态声明，仅对全图静态致命障碍计算一次膨胀。
     */
    public static LiveGridView initial(int width, int height,
                                       double inscribedRadius, double inflationRadius,
                                       double costScalingFactor, int[] staticCells) {
        int[] live = new int[width * height];
        long[] winners = new long[width * height];
        LiveGridView view = new LiveGridView(width, height, inscribedRadius, inflationRadius,
                costScalingFactor, staticCells.clone(), live, winners);
        CellRegion all = new CellRegion(0, 0, width - 1, height - 1);
        view.recompute(all, List.of(), 0L);
        return view;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int[] liveCells() {
        return liveCells;
    }

    public long[] winnerCells() {
        return winnerCells;
    }

    public int[] staticCells() {
        return staticCells;
    }

    /** 取单元格代价（越界由调用方保证）。 */
    public int costAt(int x, int y) {
        return liveCells[y * width + x];
    }

    /**
     * 应用一条新声明后的增量重算。
     *
     * @param statement  新声明（可能已因 TTL 立即过期）
     * @param active     当前全部活动声明（含新声明；按 seq 升序），seq -&gt; 声明可由其索引
     * @param nowMillis  当前时刻，expire_at &lt;= now 的声明不参与决胜
     * @return 重算区域与发生代价变化的单元格坐标
     */
    public RecomputeResult applyStatement(ObstacleStatement statement,
                                          List<ObstacleStatement> active,
                                          long nowMillis) {
        CellRegion affected = statement.region().expandAndClip(radiusCells, width, height);
        return recompute(affected, active, nowMillis);
    }

    /**
     * 删除若干声明后的增量重算。
     *
     * @param removedRegions 被删声明覆盖区域的并集
     * @param remaining      删除后剩余的全部活动声明
     */
    public RecomputeResult afterRemoval(List<CellRegion> removedRegions,
                                        List<ObstacleStatement> remaining,
                                        long nowMillis) {
        if (removedRegions.isEmpty()) {
            return new RecomputeResult(null, List.of(), false);
        }
        CellRegion affected = removedRegions.get(0);
        for (int i = 1; i < removedRegions.size(); i++) {
            affected = affected.union(removedRegions.get(i));
        }
        affected = affected.expandAndClip(radiusCells, width, height);
        return recompute(affected, remaining, nowMillis);
    }

    /**
     * 区域增量重算核心：
     * <ol>
     *   <li>在区域内逐格按 {@link MergePolicy} 选出获胜声明（候选=活动且矩形覆盖该格）；</li>
     *   <li>区域内逐格搜索最近致命障碍（静态 254 或 OCCUPY 获胜格），仅扫描膨胀半径窗口，
     *       区域内用新获胜、区域外用既有获胜序号，保证区域外既有膨胀影响不丢；</li>
     *   <li>合并静态/动态/膨胀代价并仅回写变化格。</li>
     * </ol>
     */
    public RecomputeResult recompute(CellRegion region,
                                     List<ObstacleStatement> candidates,
                                     long nowMillis) {
        int localWidth = region.x1() - region.x0() + 1;
        int localHeight = region.y1() - region.y0() + 1;
        long[] localWinner = new long[localWidth * localHeight];

        List<ObstacleStatement> covering = new ArrayList<>();
        for (int y = region.y0(); y <= region.y1(); y++) {
            for (int x = region.x0(); x <= region.x1(); x++) {
                covering.clear();
                for (ObstacleStatement s : candidates) {
                    if (s.expireAt() > nowMillis && s.region().contains(x, y)) {
                        covering.add(s);
                    }
                }
                ObstacleStatement win = null;
                for (ObstacleStatement s : covering) {
                    win = MergePolicy.winner(win, s);
                }
                localWinner[(y - region.y0()) * localWidth + (x - region.x0())] =
                        win == null ? 0L : win.seq();
            }
        }

        java.util.HashMap<Long, ObstacleStatement> bySeq = new java.util.HashMap<>();
        for (ObstacleStatement s : candidates) {
            bySeq.put(s.seq(), s);
        }

        List<int[]> changed = new ArrayList<>();
        int r = radiusCells - 1;
        double r2 = inflationRadius * inflationRadius;
        for (int y = region.y0(); y <= region.y1(); y++) {
            for (int x = region.x0(); x <= region.x1(); x++) {
                int idx = y * width + x;
                long winSeq = localWinner[(y - region.y0()) * localWidth + (x - region.x0())];
                ObstacleStatement win = winSeq == 0L ? null : bySeq.get(winSeq);

                int dynamicRaw = (win != null && win.kind() == StatementKind.OCCUPY)
                        ? Inflation.LETHAL : 0;

                double bestD2 = Double.POSITIVE_INFINITY;
                int sy0 = Math.max(0, y - r);
                int sy1 = Math.min(height - 1, y + r);
                int sx0 = Math.max(0, x - r);
                int sx1 = Math.min(width - 1, x + r);
                for (int sy = sy0; sy <= sy1; sy++) {
                    for (int sx = sx0; sx <= sx1; sx++) {
                        double dx = sx - x;
                        double dy = sy - y;
                        double d2 = dx * dx + dy * dy;
                        if (d2 > r2 || d2 >= bestD2) {
                            continue;
                        }
                        if (isLethalSource(sx, sy, region, localWinner, localWidth, bySeq)) {
                            bestD2 = d2;
                        }
                    }
                }

                int inflationCost = Double.isInfinite(bestD2)
                        ? 0
                        : Inflation.inflateCost(Math.sqrt(bestD2), inscribedRadius,
                                inflationRadius, costScalingFactor);
                int newCost = Math.max(staticCells[idx], Math.max(dynamicRaw, inflationCost));

                if (newCost != liveCells[idx] || winSeq != winnerCells[idx]) {
                    changed.add(new int[] {x, y, liveCells[idx], newCost});
                }
                liveCells[idx] = newCost;
                winnerCells[idx] = winSeq;
            }
        }
        boolean changedView = !changed.isEmpty();
        return new RecomputeResult(region, changed, changedView);
    }

    /**
     * 判断格是否为致命膨胀源：静态 254，或该格获胜声明为 OCCUPY。
     * 区域内取新获胜序号，区域外取既有持久获胜序号（其声明仍活动）。
     */
    private boolean isLethalSource(int x, int y,
                                   CellRegion region, long[] localWinner, int localWidth,
                                   Map<Long, ObstacleStatement> bySeq) {
        int idx = y * width + x;
        if (staticCells[idx] == Inflation.LETHAL) {
            return true;
        }
        long seq;
        if (region.contains(x, y)) {
            seq = localWinner[(y - region.y0()) * localWidth + (x - region.x0())];
        } else {
            seq = winnerCells[idx];
        }
        if (seq == 0L) {
            return false;
        }
        ObstacleStatement s = bySeq.get(seq);
        return s != null && s.kind() == StatementKind.OCCUPY;
    }

    /**
     * 增量重算结果。
     *
     * @param region  实际重算区域（已裁剪到地图）
     * @param changed 变化格 {@code [x, y, oldCost, newCost]}；无变化为空列表
     * @param viewChanged 是否至少一格代价发生变化
     */
    public record RecomputeResult(CellRegion region, List<int[]> changed, boolean viewChanged) {
    }
}
