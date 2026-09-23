package com.example.starter.costmap.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态视图增量重算测试：验证只重算受影响区域时，结果与基于全部声明的全量重算一致；
 * 并覆盖重叠障碍决胜、CLEAR 不影响静态层、TTL 过期清除与膨胀边界。
 */
class LiveGridViewTest {

    private static final int W = 12;
    private static final int H = 12;
    private static final double INNER = 1.0d;
    private static final double OUTER = 3.0d;
    private static final double SCALE = 2.0d;

    private ObstacleStatement occupy(long seq, String source, String obstacle,
                                     int priority, long eventTime, long ttl,
                                     int x0, int y0, int x1, int y1) {
        return new ObstacleStatement(seq, "req-" + seq, source, obstacle, priority,
                StatementKind.OCCUPY, new CellRegion(x0, y0, x1, y1),
                eventTime, ttl, eventTime + ttl, eventTime);
    }

    private ObstacleStatement clear(long seq, String source, String obstacle,
                                    int priority, long eventTime, long ttl,
                                    int x0, int y0, int x1, int y1) {
        return new ObstacleStatement(seq, "req-" + seq, source, obstacle, priority,
                StatementKind.CLEAR, new CellRegion(x0, y0, x1, y1),
                eventTime, ttl, eventTime + ttl, eventTime);
    }

    /** 用全部活动声明对空视图做一次全量重算，作为参考实现。 */
    private LiveGridView fullRecompute(int[] staticCells, List<ObstacleStatement> all, long now) {
        LiveGridView view = LiveGridView.initial(W, H, INNER, OUTER, SCALE, staticCells);
        view.recompute(new CellRegion(0, 0, W - 1, H - 1), all, now);
        return view;
    }

    private void assertSameGrid(LiveGridView actual, LiveGridView expected) {
        assertThat(actual.liveCells()).containsExactly(expected.liveCells());
        assertThat(actual.winnerCells()).containsExactly(expected.winnerCells());
    }

    @Test
    void incrementalApplyMatchesFullRecompute() {
        int[] staticCells = new int[W * H];
        long now = 1_000L;

        List<ObstacleStatement> all = List.of(
                occupy(1, "lidar", "box-1", 2, 1_000L, 100_000L, 5, 5, 6, 6),
                occupy(2, "camera", "box-2", 5, 2_000L, 100_000L, 6, 6, 7, 7),
                clear(3, "radar", "ghost", 1, 3_000L, 100_000L, 5, 5, 5, 5));

        LiveGridView incremental = LiveGridView.initial(W, H, INNER, OUTER, SCALE, staticCells);
        List<ObstacleStatement> applied = new ArrayList<>();
        for (ObstacleStatement s : all) {
            applied.add(s);
            incremental.applyStatement(s, applied, now);
        }

        LiveGridView full = fullRecompute(staticCells, all, now);
        assertSameGrid(incremental, full);
    }

    @Test
    void lowerPriorityOverlapCannotOverrideHigherPriority() {
        int[] staticCells = new int[W * H];
        long now = 1_000L;
        List<ObstacleStatement> all = List.of(
                occupy(1, "lidar", "a", 5, 1_000L, 100_000L, 4, 4, 4, 4),
                occupy(2, "sonar", "b", 1, 2_000L, 100_000L, 4, 4, 4, 4));

        LiveGridView incremental = LiveGridView.initial(W, H, INNER, OUTER, SCALE, staticCells);
        incremental.applyStatement(all.get(0), List.of(all.get(0)), now);
        incremental.applyStatement(all.get(1), all, now);

        int idx = 4 * W + 4;
        assertThat(incremental.liveCells()[idx]).isEqualTo(254);
        assertThat(incremental.winnerCells()[idx]).isEqualTo(1L);

        LiveGridView full = fullRecompute(staticCells, all, now);
        assertSameGrid(incremental, full);
    }

    @Test
    void clearDoesNotRemoveStaticLethalOrItsInflation() {
        int[] staticCells = new int[W * H];
        staticCells[3 * W + 3] = 254;
        long now = 1_000L;

        LiveGridView view = LiveGridView.initial(W, H, INNER, OUTER, SCALE, staticCells);
        assertThat(view.costAt(3, 3)).isEqualTo(254);
        assertThat(view.costAt(4, 3)).isEqualTo(253);

        ObstacleStatement clear = clear(1, "lidar", "static-ghost", 9,
                1_000L, 100_000L, 3, 3, 4, 4);
        view.applyStatement(clear, List.of(clear), now);

        // 静态致命格与其内切膨胀保持不变
        assertThat(view.costAt(3, 3)).isEqualTo(254);
        assertThat(view.costAt(4, 3)).isEqualTo(253);
        // CLEAR 覆盖格的获胜者为该 CLEAR 声明，但静态膨胀仍生效（距静态致命格 sqrt(2)），
        // 且该格不被标记为致命障碍
        assertThat(view.winnerCells()[4 * W + 4]).isEqualTo(1L);
        assertThat(view.costAt(4, 4)).isBetween(1, 252);
        assertThat(view.costAt(4, 4)).isNotEqualTo(254);
    }

    @Test
    void expiredRemovalMatchesFullRecompute() {
        int[] staticCells = new int[W * H];
        long t0 = 1_000L;
        ObstacleStatement keep = occupy(1, "lidar", "keep", 2, 1_000L, 1_000_000L, 2, 2, 2, 2);
        ObstacleStatement dying = occupy(2, "lidar", "dying", 2, 1_000L, 5_000L, 8, 8, 8, 8);
        List<ObstacleStatement> initial = List.of(keep, dying);

        LiveGridView incremental = LiveGridView.initial(W, H, INNER, OUTER, SCALE, staticCells);
        incremental.applyStatement(keep, List.of(keep), t0);
        incremental.applyStatement(dying, initial, t0);
        assertThat(incremental.costAt(8, 8)).isEqualTo(254);

        long later = 10_000L;
        LiveGridView.RecomputeResult result =
                incremental.afterRemoval(List.of(dying.region()), List.of(keep), later);
        assertThat(result.region().contains(8, 8)).isTrue();
        assertThat(incremental.costAt(8, 8)).isZero();

        LiveGridView full = fullRecompute(staticCells, List.of(keep), later);
        assertSameGrid(incremental, full);
    }

    @Test
    void inflationBoundaryCellsFollowRadius() {
        int[] staticCells = new int[W * H];
        LiveGridView view = LiveGridView.initial(W, H, INNER, OUTER, SCALE, staticCells);
        ObstacleStatement s = occupy(1, "lidar", "o", 2, 1_000L, 1_000_000L, 6, 6, 6, 6);
        view.applyStatement(s, List.of(s), 1_000L);

        assertThat(view.costAt(6, 6)).isEqualTo(254);
        // 距离恰好 1 的四邻格为内切代价
        assertThat(view.costAt(7, 6)).isEqualTo(253);
        // 膨胀半径外（距离 > 3）无代价
        assertThat(view.costAt(10, 6)).isZero();
        // 半径内斜向格存在正的衰减代价
        assertThat(view.costAt(8, 8)).isBetween(1, 252);
    }

    @Test
    void ttlZeroStatementIsImmediatelyInert() {
        int[] staticCells = new int[W * H];
        long now = 1_000L;
        LiveGridView view = LiveGridView.initial(W, H, INNER, OUTER, SCALE, staticCells);
        ObstacleStatement s = occupy(1, "lidar", "o", 2, now, 0L, 6, 6, 6, 6);
        LiveGridView.RecomputeResult result = view.applyStatement(s, List.of(), now);
        assertThat(result.region().contains(6, 6)).isTrue();
        assertThat(view.costAt(6, 6)).isZero();
        assertThat(view.winnerCells()[6 * W + 6]).isZero();
    }
}
