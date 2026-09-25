package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 编组版本复算响应。
 *
 * @param jobId          作业ID
 * @param consistVersion 当前编组版本
 * @param carCount       在列辆数
 * @param totalLength    总换长
 * @param totalWeight    总重，单位吨
 * @param cars           在列车辆清单
 */
public record ConsistResponse(
        long jobId,
        int consistVersion,
        int carCount,
        BigDecimal totalLength,
        BigDecimal totalWeight,
        List<CarView> cars) {

    /**
     * 在列车辆视图。
     *
     * @param carNo       车辆号
     * @param seqNo       顺位
     * @param weightTons  重量，单位吨
     * @param length      换长
     * @param valveClosed 是否关门车
     */
    public record CarView(String carNo, int seqNo, BigDecimal weightTons,
                          BigDecimal length, boolean valveClosed) {
    }
}
