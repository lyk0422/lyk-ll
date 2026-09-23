package com.example.starter.costmap;

import com.example.starter.costmap.model.CostmapState;
import com.example.starter.costmap.model.ObstacleRecord;
import com.example.starter.costmap.model.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 代价地图引擎纯单元测试：合并决胜规则、膨胀衰减、增量重算与全量重算等价性。
 */
class CostmapEngineTest {

    private static final int W = 10;
    private static final int H = 10;

    private CostmapState newState(double inflationRadius) {
        return new CostmapState("m", W, H, 1.0, inflationRadius, new byte[W * H]);
    }

    private ObstacleRecord obstacle(String source, int priority, long ts,
                                    int x, int y, int w, int h, int... cells) {
        byte[] bytes = new byte[cells.length];
        for (int i = 0; i < cells.length; i++) {
            bytes[i] = (byte) cells[i];
        }
        return new ObstacleRecord("id-" + source + "-" + ts, "req-" + source + "-" + ts,
                source, priority, null, new Region(x, y, w, h), bytes, ts, null);
    }

    private int at(CostmapState state, int x, int y) {
        return state.masterGrid()[y * W + x] & 0xFF;
    }

    @Test
    void inflationCostDecaysLinearlyWithDistance() {
        // R=2：d=1 -> round(253*2/3)=169，d=2 -> round(253*1/3)=84，d>R -> 0
        assertEquals(169, CostmapEngine.inflationCost(1.0, 2));
        assertEquals(84, CostmapEngine.inflationCost(2.0, 2));
        assertEquals(0, CostmapEngine.inflationCost(2.5, 2));
        assertEquals(CostmapEngine.LETHAL, CostmapEngine.inflationCost(0.0, 2));
    }

    @Test
    void inflationCellsRoundsUp() {
        assertEquals(0, CostmapEngine.inflationCells(0, 1.0));
        assertEquals(2, CostmapEngine.inflationCells(2.0, 1.0));
        assertEquals(3, CostmapEngine.inflationCells(2.1, 1.0));
    }

    @Test
    void lethalObstacleInflatesNeighborhood() {
        CostmapState state = newState(2.0);
        state.obstacles().add(obstacle("lidar", 0, 100, 4, 4, 1, 1, 254));
        CostmapEngine.recomputeRegion(state, new Region(0, 0, W, H));

        assertEquals(254, at(state, 4, 4));
        assertEquals(169, at(state, 4, 5));
        assertEquals(84, at(state, 4, 6));
        assertEquals(134, at(state, 5, 5)); // d=sqrt(2)
        assertEquals(0, at(state, 6, 6));   // d=2*sqrt(2) > R
    }

    @Test
    void higherPriorityWinsOnSameCell() {
        CostmapState state = newState(0);
        state.obstacles().add(obstacle("lidar", 1, 100, 4, 4, 1, 1, 254));
        state.obstacles().add(obstacle("camera", 2, 50, 4, 4, 1, 1, 100));
        CostmapEngine.recomputeRegion(state, new Region(0, 0, W, H));
        assertEquals(100, at(state, 4, 4));
    }

    @Test
    void newerTimestampWinsAtEqualPriority() {
        CostmapState state = newState(0);
        state.obstacles().add(obstacle("lidar", 1, 100, 4, 4, 1, 1, 254));
        state.obstacles().add(obstacle("camera", 1, 200, 4, 4, 1, 1, 100));
        CostmapEngine.recomputeRegion(state, new Region(0, 0, W, H));
        assertEquals(100, at(state, 4, 4));
    }

    @Test
    void lexicographicallySmallerSourceWinsAtEqualPriorityAndTimestamp() {
        CostmapState state = newState(0);
        state.obstacles().add(obstacle("lidar", 1, 100, 4, 4, 1, 1, 254));
        state.obstacles().add(obstacle("camera", 1, 100, 4, 4, 1, 1, 100));
        CostmapEngine.recomputeRegion(state, new Region(0, 0, W, H));
        // "camera" 字典序小于 "lidar"，camera 胜出
        assertEquals(100, at(state, 4, 4));
    }

    @Test
    void incrementalRecomputeMatchesFullRecompute() {
        // 全量重算基准
        CostmapState full = newState(2.0);
        full.obstacles().add(obstacle("lidar", 1, 100, 2, 2, 2, 2, 254, 0, 0, 254));
        full.obstacles().add(obstacle("camera", 2, 50, 3, 3, 2, 2, 100, 100, 100, 100));
        CostmapEngine.recomputeRegion(full, new Region(0, 0, W, H));

        // 增量重算：先应用第一个障碍物，再只重算第二个障碍物的受影响区域
        CostmapState inc = newState(2.0);
        inc.obstacles().add(obstacle("lidar", 1, 100, 2, 2, 2, 2, 254, 0, 0, 254));
        CostmapEngine.recomputeRegion(inc, new Region(2, 2, 2, 2).expand(2, W, H));
        inc.obstacles().add(obstacle("camera", 2, 50, 3, 3, 2, 2, 100, 100, 100, 100));
        CostmapEngine.recomputeRegion(inc, new Region(3, 3, 2, 2).expand(2, W, H));

        assertArrayEquals(full.masterGrid(), inc.masterGrid());
        assertArrayEquals(full.baseGrid(), inc.baseGrid());
    }

    @Test
    void removalRecomputeRestoresStaticLayer() {
        CostmapState state = newState(2.0);
        ObstacleRecord o = obstacle("lidar", 0, 100, 4, 4, 1, 1, 254);
        state.obstacles().add(o);
        CostmapEngine.recomputeRegion(state, new Region(0, 0, W, H));
        assertEquals(254, at(state, 4, 4));

        state.obstacles().remove(o);
        CostmapEngine.recomputeRegion(state, new Region(4, 4, 1, 1).expand(2, W, H));
        assertEquals(0, at(state, 4, 4));
        assertEquals(0, at(state, 4, 5));
        assertEquals(0, at(state, 5, 5));
    }

    @Test
    void recomputeClipsRegionAtMapBorder() {
        CostmapState state = newState(2.0);
        state.obstacles().add(obstacle("lidar", 0, 100, 0, 0, 1, 1, 254));
        Region clipped = CostmapEngine.recomputeRegion(state, new Region(0, 0, 1, 1).expand(2, W, H));
        assertEquals(new Region(0, 0, 3, 3), clipped);
        assertEquals(254, at(state, 0, 0));
        assertEquals(169, at(state, 1, 0));
    }
}
