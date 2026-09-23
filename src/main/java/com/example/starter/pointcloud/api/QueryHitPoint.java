package com.example.starter.pointcloud.api;

/**
 * 查询命中的点。
 *
 * @param x                X 坐标，单位米
 * @param y                Y 坐标，单位米
 * @param z                Z 坐标，单位米
 * @param timestampNanos   采集时间戳，纳秒
 * @param chunkId          所属块 ID
 * @param chunkVersion     实际读取的块版本
 * @param source           实际读取的块来源
 * @param pointIndex       块内点序，从 0 开始
 */
public record QueryHitPoint(double x,
                            double y,
                            double z,
                            long timestampNanos,
                            String chunkId,
                            long chunkVersion,
                            String source,
                            int pointIndex) {
}
