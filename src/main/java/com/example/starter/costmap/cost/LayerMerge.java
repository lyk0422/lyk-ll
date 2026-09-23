package com.example.starter.costmap.cost;

import com.example.starter.costmap.domain.ObstacleRow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态层多来源合并逻辑。
 *
 * <p>同一格存在多个来源时，按公开且确定性的顺序决胜：</p>
 * <ol>
 *   <li>来源类型固定优先级（SAFETY &gt; MANUAL &gt; SENSOR &gt; GENERIC）；</li>
 *   <li>优先级相同则事件时间戳 eventTime 更大者胜；</li>
 *   <li>仍相同则来源 ID 字典序更小者胜。</li>
 * </ol>
 */
public final class LayerMerge {

    private LayerMerge() {
    }

    /**
     * 从一批动态障碍记录中选出每格唯一获胜记录。
     *
     * @param rows 未过期、未取代的动态障碍记录
     * @return 格坐标键 -> 获胜记录
     */
    public static Map<CellKey, ObstacleRow> selectWinners(List<ObstacleRow> rows) {
        Map<CellKey, ObstacleRow> winners = new HashMap<>();
        for (ObstacleRow row : rows) {
            CellKey key = new CellKey(row.x(), row.y());
            ObstacleRow current = winners.get(key);
            if (current == null || dominates(row, current)) {
                winners.put(key, row);
            }
        }
        return winners;
    }

    /**
     * 判断候选记录是否在确定性决胜规则下优于在位记录。
     */
    static boolean dominates(ObstacleRow candidate, ObstacleRow incumbent) {
        int cmp = Integer.compare(candidate.sourceType().priority(),
                incumbent.sourceType().priority());
        if (cmp != 0) {
            return cmp < 0;
        }
        int timeCmp = Long.compare(candidate.eventTime(), incumbent.eventTime());
        if (timeCmp != 0) {
            return timeCmp > 0;
        }
        return candidate.sourceId().compareTo(incumbent.sourceId()) < 0;
    }

    /**
     * 合并静态层、动态获胜层与膨胀层得到最终代价（0-100 取最大）。
     */
    public static int combine(int staticCost, Integer obstacleCost, int inflatedCost) {
        int value = Math.max(staticCost, inflatedCost);
        if (obstacleCost != null) {
            value = Math.max(value, obstacleCost);
        }
        return Math.min(100, value);
    }

    /** 格坐标键。 */
    public record CellKey(int x, int y) {
    }
}
