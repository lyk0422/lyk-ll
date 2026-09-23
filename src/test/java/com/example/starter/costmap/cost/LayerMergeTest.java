package com.example.starter.costmap.cost;

import com.example.starter.costmap.cost.LayerMerge.CellKey;
import com.example.starter.costmap.domain.ObstacleRow;
import com.example.starter.costmap.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多来源确定性合并规则单元测试。
 */
class LayerMergeTest {

    private ObstacleRow row(String source, SourceType type, int x, int y,
                            int cost, long eventTime) {
        return new ObstacleRow("m", "req-" + source + "-" + eventTime, source, type,
                x, y, cost, 1L, eventTime, null, 100L, false);
    }

    @Test
    void fixedSourceTypePriorityBeatsNewerTimestamp() {
        ObstacleRow safety = row("safe-1", SourceType.SAFETY, 1, 1, 90, 1_000L);
        ObstacleRow sensor = row("sensor-1", SourceType.SENSOR, 1, 1, 100, 9_999L);

        Map<CellKey, ObstacleRow> winners = LayerMerge.selectWinners(List.of(sensor, safety));

        assertEquals("safe-1", winners.get(new CellKey(1, 1)).sourceId(),
                "SAFETY 优先级高于 SENSOR，即使时间戳更旧");
    }

    @Test
    void sameTypeResolvesByNewerEventTime() {
        ObstacleRow older = row("a", SourceType.SENSOR, 0, 0, 10, 100L);
        ObstacleRow newer = row("b", SourceType.SENSOR, 0, 0, 20, 200L);

        Map<CellKey, ObstacleRow> winners = LayerMerge.selectWinners(List.of(older, newer));

        assertEquals("b", winners.get(new CellKey(0, 0)).sourceId());
        assertEquals(20, winners.get(new CellKey(0, 0)).cost());
    }

    @Test
    void samePriorityAndTimeResolvesBySourceIdLexicographic() {
        ObstacleRow z = row("zzz", SourceType.GENERIC, 5, 5, 100, 500L);
        ObstacleRow a = row("aaa", SourceType.GENERIC, 5, 5, 100, 500L);

        Map<CellKey, ObstacleRow> winners = LayerMerge.selectWinners(List.of(z, a));

        assertEquals("aaa", winners.get(new CellKey(5, 5)).sourceId(),
                "完全同优先级同时间戳时 sourceId 字典序更小者胜");
    }

    @Test
    void winnersAreKeptPerCellIndependently() {
        ObstacleRow c1 = row("a", SourceType.SENSOR, 0, 0, 10, 100L);
        ObstacleRow c2 = row("b", SourceType.MANUAL, 1, 0, 20, 100L);

        Map<CellKey, ObstacleRow> winners = LayerMerge.selectWinners(List.of(c1, c2));

        assertEquals(2, winners.size());
        assertEquals("a", winners.get(new CellKey(0, 0)).sourceId());
        assertEquals("b", winners.get(new CellKey(1, 0)).sourceId());
    }

    @Test
    void combineTakesMaxAndClampsTo100() {
        assertEquals(80, LayerMerge.combine(80, null, 10));
        assertEquals(95, LayerMerge.combine(10, 95, 60));
        assertEquals(100, LayerMerge.combine(0, 200, 0),
                "异常越界输入时最终代价仍截断到 100");
    }

    @Test
    void dominatesOrderingIsAsymmetric() {
        ObstacleRow safety = row("s", SourceType.SAFETY, 0, 0, 1, 1L);
        ObstacleRow generic = row("g", SourceType.GENERIC, 0, 0, 1, 999L);

        assertTrue(LayerMerge.dominates(safety, generic));
        assertFalse(LayerMerge.dominates(generic, safety));
    }
}
