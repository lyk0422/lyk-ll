package com.example.starter.lidar.spatial;

import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.SpatialQueryRequest;
import com.example.starter.lidar.model.Vec3;

/**
 * 空间判定形状：点级谓词（闭区间，边界包含）+ 块级粗筛包围盒。
 */
public interface SpatialShape {

    /** 点是否位于形状内（边界包含）。 */
    boolean contains(double x, double y, double z);

    /** 形状的闭区间外包盒，用于块包围盒粗筛。 */
    BBox envelope();

    /** 依据查询请求构造具体形状。 */
    static SpatialShape from(SpatialQueryRequest req) {
        return switch (req.type()) {
            case BOX -> new BoxShape(req.box());
            case RADIUS -> new RadiusShape(req.center(), req.radius());
            case CORRIDOR -> new CorridorShape(req.polyline(), req.width() / 2.0);
        };
    }
}
