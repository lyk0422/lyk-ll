package com.example.starter.pointcloud.api;

import jakarta.validation.constraints.NotNull;

/**
 * 闭区间 Box 查询请求；边界上的点必须包含。
 */
public record BoxQueryRequest(
        @NotNull Double minX,
        @NotNull Double minY,
        @NotNull Double minZ,
        @NotNull Double maxX,
        @NotNull Double maxY,
        @NotNull Double maxZ) {
}
