package com.example.starter.costmap.model;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单张地图的 JVM 内运行状态（非线程安全，由服务层按地图加锁保护）。
 * <p>
 * baseGrid 为静态层与动态障碍物合并后的基础代价（不含膨胀）；
 * masterGrid 为叠加膨胀层后的对外代价；versions 保存每个版本的 masterGrid 快照。
 */
public class CostmapState {

    private final String mapId;
    private final int width;
    private final int height;
    private final double resolution;
    private final double inflationRadius;
    private final byte[] staticGrid;
    private final byte[] baseGrid;
    private final byte[] masterGrid;
    private final List<ObstacleRecord> obstacles = new ArrayList<>();
    private final NavigableMap<Long, byte[]> versions = new TreeMap<>();
    private long currentVersion = -1;

    public CostmapState(String mapId, int width, int height, double resolution,
                        double inflationRadius, byte[] staticGrid) {
        this.mapId = mapId;
        this.width = width;
        this.height = height;
        this.resolution = resolution;
        this.inflationRadius = inflationRadius;
        this.staticGrid = staticGrid;
        this.baseGrid = new byte[width * height];
        this.masterGrid = new byte[width * height];
    }

    public String mapId() {
        return mapId;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double resolution() {
        return resolution;
    }

    public double inflationRadius() {
        return inflationRadius;
    }

    public byte[] staticGrid() {
        return staticGrid;
    }

    public byte[] baseGrid() {
        return baseGrid;
    }

    public byte[] masterGrid() {
        return masterGrid;
    }

    public List<ObstacleRecord> obstacles() {
        return obstacles;
    }

    public NavigableMap<Long, byte[]> versions() {
        return versions;
    }

    public long currentVersion() {
        return currentVersion;
    }

    public void currentVersion(long version) {
        this.currentVersion = version;
    }
}
