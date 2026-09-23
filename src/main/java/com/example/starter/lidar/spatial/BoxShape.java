package com.example.starter.lidar.spatial;

import com.example.starter.lidar.model.BBox;

/** 闭区间轴对齐盒判定。 */
class BoxShape implements SpatialShape {

    private final BBox box;

    BoxShape(BBox box) {
        this.box = box;
    }

    @Override
    public boolean contains(double x, double y, double z) {
        return box.contains(x, y, z);
    }

    @Override
    public BBox envelope() {
        return box;
    }
}
