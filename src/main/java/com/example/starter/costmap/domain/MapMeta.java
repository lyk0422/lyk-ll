package com.example.starter.costmap.domain;

/**
 * 地图某个不可变版本的元数据。
 *
 * @param mapId                地图业务 ID
 * @param version              版本号，从 1 开始单调递增
 * @param width                格栅列数
 * @param height               格栅行数
 * @param resolution           每格物理边长，单位米
 * @param originX              格栅原点世界坐标 x，单位米
 * @param originY              格栅原点世界坐标 y，单位米
 * @param inflationRadiusCells 膨胀半径，单位格
 * @param costDecay            膨胀代价每格衰减底数，取值 (0,1]
 * @param createdAt            创建时间，Unix 毫秒
 */
public record MapMeta(
        String mapId,
        long version,
        int width,
        int height,
        double resolution,
        double originX,
        double originY,
        int inflationRadiusCells,
        double costDecay,
        long createdAt) {
}
