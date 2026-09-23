package com.example.starter.costmap.domain;

import java.util.Comparator;

/**
 * 多来源同格主张的确定性合并规则（公开决胜顺序）：
 * <ol>
 *   <li>priority 数值大者胜；</li>
 *   <li>priority 相同，观测时间戳 eventTime 晚者胜（更新的观测胜）；</li>
 *   <li>仍相同，sourceId 字典序小者胜；</li>
 *   <li>仍相同，obstacleId 字典序小者胜；</li>
 *   <li>仍相同，应用序号 seq 大者胜（同来源同障碍的新版本胜）。</li>
 * </ol>
 *
 * <p>该顺序公开且确定：同一批声明无论以何种物理顺序并发到达，最终同格获胜者一致。</p>
 */
public final class MergePolicy {

    /** 获胜声明比较器：compare 结果大于 0 表示第一个声明获胜。 */
    public static final Comparator<ObstacleStatement> WINNER =
            Comparator.comparingInt(ObstacleStatement::priority)
                    .thenComparingLong(ObstacleStatement::eventTime)
                    .thenComparing(s -> s.sourceId(), Comparator.reverseOrder())
                    .thenComparing(s -> s.obstacleId(), Comparator.reverseOrder())
                    .thenComparingLong(ObstacleStatement::seq);

    private MergePolicy() {
    }

    /** 返回两个声明中的获胜者（第一个为 null 时返回第二个，反之亦然）。 */
    public static ObstacleStatement winner(ObstacleStatement a, ObstacleStatement b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return WINNER.compare(a, b) >= 0 ? a : b;
    }
}
