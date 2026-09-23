package com.example.starter.lidar.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 空间查询请求；按 {@link #type} 使用对应字段，时间范围可空（不限制）。
 *
 * @param type     查询类型 BOX / RADIUS / CORRIDOR
 * @param box      BOX 查询的闭区间包围盒
 * @param center   RADIUS 查询球心
 * @param radius   RADIUS 查询半径（正数），球面点包含
 * @param polyline CORRIDOR 查询折线顶点（至少 2 个）
 * @param width    CORRIDOR 走廊宽度（正数），半宽为 width/2，边界点包含
 * @param startTs  时间下界（epoch 毫秒，包含），null 表示不限
 * @param endTs    时间上界（epoch 毫秒，包含），null 表示不限
 */
public record SpatialQueryRequest(
        @NotNull QueryType type,
        @Valid BBox box,
        @Valid Vec3 center,
        @Positive Double radius,
        @Valid List<@NotNull Vec3> polyline,
        @Positive Double width,
        Long startTs,
        Long endTs
) {

    /** 各查询类型所需字段必须齐备且自洽。 */
    @AssertTrue(message = "查询参数与类型不匹配")
    public boolean isShapeValid() {
        boolean timeOk = startTs == null || endTs == null || startTs <= endTs;
        if (!timeOk) {
            return false;
        }
        return switch (type) {
            case BOX -> box != null && box.isValid();
            case RADIUS -> center != null && radius != null && radius > 0;
            case CORRIDOR -> polyline != null && polyline.size() >= 2
                    && width != null && width > 0;
        };
    }
}
