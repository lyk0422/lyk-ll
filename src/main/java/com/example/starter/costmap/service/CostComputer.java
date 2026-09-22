package com.example.starter.costmap.service;

import com.example.starter.costmap.model.CellWinner;
import com.example.starter.costmap.model.GridRegion;
import com.example.starter.costmap.model.MapVersion;
import com.example.starter.costmap.repo.DynamicCostRepository;
import com.example.starter.costmap.repo.ObstacleRepository;
import com.example.starter.costmap.support.CostMath;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 局部代价重算：只读取并重写受影响区域（障碍物区域 + 膨胀边界），
 * 行级合并按公开决胜顺序（priority 降序、timestampMs 降序、sourceId 升序）确定每格赢家。
 */
@Component
public class CostComputer {

    /** 行级赢家比较器：priority 降序、timestampMs 降序、sourceId 字典序升序。 */
    public static final Comparator<CellWinner> WINNER_ORDER =
            Comparator.comparingInt(CellWinner::priority).reversed()
                    .thenComparing(Comparator.comparingLong(CellWinner::timestampMs).reversed())
                    .thenComparing(CellWinner::sourceId);

    private final ObstacleRepository obstacleRepository;
    private final DynamicCostRepository dynamicCostRepository;

    public CostComputer(ObstacleRepository obstacleRepository,
                        DynamicCostRepository dynamicCostRepository) {
        this.obstacleRepository = obstacleRepository;
        this.dynamicCostRepository = dynamicCostRepository;
    }

    /**
     * 对 box 内所有格点做一次全量（但仅限本区域）重算并 upsert 缓存。
     * 读取时在 box 外再扩 radiusCells，保证边界格点的膨胀来源完整。
     * 过期行由 cleanup 显式删除，因此这里读取现存全部行，无需时间过滤。
     *
     * @return 重算的格点数量与每格中标来源（key 为 y*width+x，仅被占据格点）
     */
    public RecomputeResult recomputeBox(MapVersion version, GridRegion box, long seq) {
        int width = version.width();
        int radius = CostMath.radiusCells(version.inflationRadius(), version.resolution());
        GridRegion readBox = box.dilate(radius, width, version.height());

        List<Object[]> rows = obstacleRepository.findRowsInBox(version.versionId(), readBox);
        // 每个被占据格点的当前中标来源
        Map<Long, CellWinner> occupied = new HashMap<>();
        for (Object[] row : rows) {
            int x = (Integer) row[0];
            int y = (Integer) row[1];
            int priority = (Integer) row[2];
            long ts = (Long) row[3];
            String source = (String) row[4];
            String requestId = (String) row[5];
            long key = (long) y * width + x;
            CellWinner candidate = new CellWinner(source, priority, ts, requestId);
            CellWinner current = occupied.get(key);
            if (current == null || WINNER_ORDER.compare(candidate, current) < 0) {
                occupied.put(key, candidate);
            }
        }

        byte[] base = version.staticInflated();
        List<int[]> writes = new ArrayList<>(box.area());
        for (int y = box.yMin(); y <= box.yMax(); y++) {
            for (int x = box.xMin(); x <= box.xMax(); x++) {
                int baseCost = base[CostMath.index(x, y, width)] & 0xFF;
                int cost = baseCost;
                if (!occupied.isEmpty()) {
                    double bestDist = Double.POSITIVE_INFINITY;
                    for (Map.Entry<Long, CellWinner> e : occupied.entrySet()) {
                        int ox = (int) (e.getKey() % width);
                        int oy = (int) (e.getKey() / width);
                        double dist = CostMath.cellDistance(x, y, ox, oy);
                        if (dist < bestDist) {
                            bestDist = dist;
                        }
                    }
                    int dynamicCost;
                    if (bestDist == 0) {
                        // 被障碍物直接占据：致命代价
                        dynamicCost = CostMath.LETHAL;
                    } else {
                        dynamicCost = CostMath.inflationCost(bestDist, version.resolution(),
                                version.inflationRadius(), version.costDecay());
                    }
                    cost = Math.max(baseCost, dynamicCost);
                }
                writes.add(new int[]{x, y, cost});
            }
        }
        dynamicCostRepository.upsertCosts(version.versionId(), writes, seq);
        return new RecomputeResult(writes.size(), occupied);
    }

    /** 重算结果。 */
    public record RecomputeResult(int cellCount, Map<Long, CellWinner> owners) {
    }

    /**
     * 查询 region 内每格当前中标障碍物来源（仅被直接占据的格点）。
     * key 为 y*width+x。过期行由 cleanup 显式删除，此处读取现存全部行。
     */
    public Map<Integer, String> ownersInRegion(MapVersion version, GridRegion region) {
        List<Object[]> rows = obstacleRepository.findRowsInBox(version.versionId(), region);
        int width = version.width();
        Map<Integer, CellWinner> winners = new HashMap<>();
        for (Object[] row : rows) {
            int x = (Integer) row[0];
            int y = (Integer) row[1];
            int priority = (Integer) row[2];
            long ts = (Long) row[3];
            String source = (String) row[4];
            String requestId = (String) row[5];
            int key = y * width + x;
            CellWinner candidate = new CellWinner(source, priority, ts, requestId);
            CellWinner current = winners.get(key);
            if (current == null || WINNER_ORDER.compare(candidate, current) < 0) {
                winners.put(key, candidate);
            }
        }
        Map<Integer, String> owners = new HashMap<>();
        winners.forEach((key, winner) -> owners.put(key, winner.sourceId()));
        return owners;
    }
}
