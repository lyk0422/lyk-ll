package com.example.starter.costmap.domain;

/**
 * 一条动态障碍物记录（某个来源在某格的某个 revision）。
 *
 * @param mapId      地图 ID
 * @param requestId  写入该记录的幂等请求 ID
 * @param sourceId   来源 ID
 * @param sourceType 来源类型
 * @param x          格坐标 x
 * @param y          格坐标 y
 * @param cost       上报代价 0-100
 * @param revision   同一障碍（map+source+cell）的更新序号
 * @param eventTime  事件时间戳，Unix 毫秒
 * @param expiresAt  过期时刻，Unix 毫秒；null 不过期
 * @param appliedAt  服务端应用时间，Unix 毫秒
 * @param superseded 是否已被同一障碍更新版本取代
 */
public record ObstacleRow(
        String mapId,
        String requestId,
        String sourceId,
        SourceType sourceType,
        int x,
        int y,
        int cost,
        long revision,
        long eventTime,
        Long expiresAt,
        long appliedAt,
        boolean superseded) {
}
