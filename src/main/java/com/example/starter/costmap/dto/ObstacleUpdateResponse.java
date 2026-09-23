package com.example.starter.costmap.dto;

import com.example.starter.costmap.model.Region;

/**
 * 障碍物更新响应。
 *
 * @param mapId          地图标识
 * @param requestId      请求标识
 * @param version        应用后的地图版本号；重放时为首次应用产生的版本号
 * @param affectedRegion 实际重算区域（更新区域外扩膨胀半径并裁剪到地图边界）
 * @param replayed       是否为幂等重放（true 表示未重复应用）
 */
public record ObstacleUpdateResponse(
        String mapId,
        String requestId,
        long version,
        Region affectedRegion,
        boolean replayed) {
}
