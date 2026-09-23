package com.example.starter.costmap.dto;

/**
 * 创建地图请求。
 *
 * @param mapId           地图标识；为空时由服务端生成
 * @param width           地图宽度（格），至少为 1
 * @param height          地图高度（格），至少为 1
 * @param resolution      分辨率（米/格），必须为正
 * @param inflationRadius 膨胀半径（米），必须非负
 * @param staticGrid      静态层行主序代价数组（0-255），长度须为 width*height；为空时全部置 0
 */
public record CreateMapRequest(
        String mapId,
        Integer width,
        Integer height,
        Double resolution,
        Double inflationRadius,
        int[] staticGrid) {
}
