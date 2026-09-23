package com.example.starter.costmap.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性合并决胜顺序测试：优先级 &gt; 时间戳 &gt; 来源 ID &gt; 障碍 ID &gt; 序号。
 */
class MergePolicyTest {

    private final CellRegion region = new CellRegion(0, 0, 1, 1);

    private ObstacleStatement stmt(long seq, String source, String obstacle,
                                   int priority, long eventTime) {
        return new ObstacleStatement(seq, "req-" + seq, source, obstacle, priority,
                StatementKind.OCCUPY, region, eventTime, 10_000L,
                eventTime + 10_000L, eventTime);
    }

    @Test
    void higherPriorityWins() {
        ObstacleStatement low = stmt(1, "sensor-a", "o", 1, 100L);
        ObstacleStatement high = stmt(2, "sensor-b", "o", 5, 50L);
        assertThat(MergePolicy.winner(low, high)).isEqualTo(high);
        // 与物理顺序无关
        assertThat(MergePolicy.winner(high, low)).isEqualTo(high);
    }

    @Test
    void laterEventTimeWinsOnTiedPriority() {
        ObstacleStatement older = stmt(1, "sensor-a", "o", 3, 100L);
        ObstacleStatement newer = stmt(2, "sensor-z", "o", 3, 200L);
        assertThat(MergePolicy.winner(older, newer)).isEqualTo(newer);
    }

    @Test
    void sourceIdLexicographicBreaksTie() {
        ObstacleStatement a = stmt(1, "sensor-a", "o", 3, 100L);
        ObstacleStatement b = stmt(2, "sensor-b", "o", 3, 100L);
        assertThat(MergePolicy.winner(a, b)).isEqualTo(a);
    }

    @Test
    void obstacleIdThenSeqBreakRemainingTies() {
        ObstacleStatement o1 = stmt(1, "sensor-a", "aaa", 3, 100L);
        ObstacleStatement o2 = stmt(2, "sensor-a", "bbb", 3, 100L);
        assertThat(MergePolicy.winner(o1, o2)).isEqualTo(o1);

        ObstacleStatement v1 = stmt(1, "sensor-a", "same", 3, 100L);
        ObstacleStatement v2 = stmt(7, "sensor-a", "same", 3, 100L);
        assertThat(MergePolicy.winner(v1, v2)).isEqualTo(v2);
    }

    @Test
    void nullSafety() {
        ObstacleStatement s = stmt(1, "a", "o", 1, 100L);
        assertThat(MergePolicy.winner(null, s)).isSameAs(s);
        assertThat(MergePolicy.winner(s, null)).isSameAs(s);
    }
}
