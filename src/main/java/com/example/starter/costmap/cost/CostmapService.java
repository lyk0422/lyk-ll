package com.example.starter.costmap.cost;

import com.example.starter.costmap.domain.CellCost;
import com.example.starter.costmap.domain.CellView;
import com.example.starter.costmap.domain.MapMeta;
import com.example.starter.costmap.domain.ObstacleInput;
import com.example.starter.costmap.domain.Region;
import com.example.starter.costmap.domain.UpdateResult;

import java.util.List;

/**
 * 动态 Costmap 业务服务：管理不可变地图版本、动态障碍增量更新、
 * 膨胀层局部重算、TTL 清理、快照发布/恢复与区域差异查询。
 * 骨架阶段仅声明调用边界。
 */
public interface CostmapService {

    /**
     * 创建地图首个不可变版本（静态格栅 + 膨胀参数）。
     */
    MapMeta createMap(CreateMapCommand command);

    /**
     * 应用一次障碍物更新（幂等、局部重算）。
     */
    UpdateResult updateObstacle(String mapId, ObstacleInput input);

    /**
     * 查询实时视图某区域（静态 + 动态获胜层 + 膨胀）。
     */
    CellView viewRegion(String mapId, Region region);

    /**
     * 主动执行一次 TTL 过期清理，返回物理删除条数。
     */
    int cleanupExpired(String mapId);

    /**
     * 发布当前视图的不可变快照。
     */
    SnapshotInfo publishSnapshot(String mapId, String snapshotId);

    /**
     * 从 H2 中已发布的快照恢复查询视图（只读）。
     */
    CellView viewSnapshotRegion(String snapshotId, Region region);

    /**
     * 区域差异：对比两个视图（快照-快照，或快照-当前实时），返回最终代价不同的格。
     *
     * @param mapId        地图 ID（实时视图与校验使用）
     * @param fromSnapshot 基准快照 ID
     * @param toSnapshot   目标快照 ID；null 表示与当前实时视图比较
     */
    RegionDiff diffRegion(String mapId, String fromSnapshot, String toSnapshot, Region region);

    /** 创建地图命令。 */
    record CreateMapCommand(
            String mapId,
            int width,
            int height,
            double resolution,
            double originX,
            double originY,
            int inflationRadiusCells,
            double costDecay,
            List<CellCost> staticCells) {
    }

    /** 快照元信息。 */
    record SnapshotInfo(String snapshotId, String mapId, long mapVersion, long createdAt) {
    }

    /** 区域差异结果。 */
    record RegionDiff(
            String mapId,
            Region region,
            String fromSnapshot,
            String toSnapshot,
            List<DiffCell> changedCells) {

        /** 发生变化的格。 */
        public record DiffCell(int x, int y, int fromCost, int toCost) {
        }
    }
}
