package com.example.starter.domain;

/**
 * 塔机回转重叠区，按圆形区域建模（场地平面坐标系，单位米）。
 */
public record OverlapZone(String id, String name, double centerXM, double centerYM, double radiusM) {

    /**
     * 判断吊钩坐标是否进入该重叠区（含边界）。
     */
    public boolean contains(double hookXM, double hookYM) {
        double dx = hookXM - centerXM;
        double dy = hookYM - centerYM;
        return dx * dx + dy * dy <= radiusM * radiusM;
    }
}
