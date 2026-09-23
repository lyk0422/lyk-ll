package com.example.starter.costmap.service;

import com.example.starter.costmap.web.dto.CellRegionResponse;
import com.example.starter.costmap.web.dto.CreateMapRequest;
import com.example.starter.costmap.web.dto.MapResponse;
import com.example.starter.costmap.web.dto.ObstacleRequest;
import com.example.starter.costmap.web.dto.ObstacleResponse;
import com.example.starter.costmap.web.dto.PurgeResponse;
import com.example.starter.costmap.web.dto.RegionDiffResponse;
import com.example.starter.costmap.web.dto.RegionDto;
import com.example.starter.costmap.web.dto.RestoreResponse;
import com.example.starter.costmap.web.dto.SnapshotRequest;
import com.example.starter.costmap.web.dto.SnapshotResponse;

/**
 * 动态代价地图应用服务。
 */
public interface CostmapService {

    /** 创建不可变地图版本（静态格栅与膨胀参数随之固定）。 */
    MapResponse createMap(CreateMapRequest request);

    /** 查询地图元数据。 */
    MapResponse getMap(String mapId);

    /** 查询当前动态视图在给定区域内的格栅代价。 */
    CellRegionResponse queryRegion(String mapId, RegionDto region);

    /** 应用（或幂等重放）一条障碍物更新；仅重算受影响区域及其膨胀边界。 */
    ObstacleResponse applyObstacle(String mapId, ObstacleRequest request);

    /** 清理已过期障碍物版本，只删除同一障碍物的过期旧版本数据。 */
    PurgeResponse purgeExpired(String mapId);

    /** 发布不可变版本快照；快照不受后续更新与 TTL 清理影响。 */
    SnapshotResponse publishSnapshot(String mapId, SnapshotRequest request);

    /** 查询快照元数据。 */
    SnapshotResponse getSnapshot(String mapId, String snapshotId);

    /** 查询快照发布时刻固化视图的区域格栅。 */
    CellRegionResponse querySnapshotRegion(String mapId, String snapshotId, RegionDto region);

    /** 查询快照固化视图与当前动态视图在给定区域内的差异。 */
    RegionDiffResponse diffRegion(String mapId, String snapshotId, RegionDto region);

    /** 从 H2 中保存的快照恢复当前查询视图（同一 JVM 内）。 */
    RestoreResponse restoreSnapshot(String mapId, String snapshotId);
}
