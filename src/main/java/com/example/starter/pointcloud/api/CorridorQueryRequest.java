package com.example.starter.pointcloud.api;

import com.example.starter.pointcloud.model.Vec3;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * 轨迹走廊查询请求：点到折线的三维距离不超过半宽即命中（含边界）。
 *
 * @param vertices  折线顶点，至少 2 个
 * @param halfWidthMeters 走廊半宽，单位米
 */
public record CorridorQueryRequest(
        @NotEmpty List<@NotNull @Valid Vec3Holder> vertices,
        @NotNull @PositiveOrZero Double halfWidthMeters) {

    /**
     * 折线顶点传输对象。
     */
    public record Vec3Holder(@NotNull Double x, @NotNull Double y, @NotNull Double z) {

        public Vec3 toVec3() {
            return new Vec3(x, y, z);
        }
    }
}
