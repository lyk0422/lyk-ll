package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 作业视图响应。
 *
 * @param id             作业ID
 * @param trainNo        车次
 * @param track          股道号
 * @param status         作业状态
 * @param consistVersion 当前编组版本
 * @param carCount       在列辆数
 * @param totalLength    总换长
 * @param totalWeight    总重，单位吨
 * @param closedCarCount 关门车辆数
 * @param closedCarRatio 关门车比例（0~1）
 */
public record JobResponse(
        long id,
        String trainNo,
        String track,
        String status,
        int consistVersion,
        int carCount,
        BigDecimal totalLength,
        BigDecimal totalWeight,
        int closedCarCount,
        double closedCarRatio) {
}
