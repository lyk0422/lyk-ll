package com.example.starter.costmap.dto;

/**
 * 过期障碍物清理响应。
 *
 * @param mapId   地图标识
 * @param removed 本次清理移除的过期障碍物记录数
 * @param version 清理后的地图版本号；未发生移除时保持当前版本
 */
public record CleanupResponse(String mapId, int removed, long version) {
}
