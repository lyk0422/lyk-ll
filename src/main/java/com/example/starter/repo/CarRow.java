package com.example.starter.repo;

import java.math.BigDecimal;

/**
 * 编组车辆行记录。
 *
 * @param id          车辆主键
 * @param jobId       所属作业ID
 * @param carNo       车辆号（合成数据）
 * @param seqNo       编组顺位
 * @param weightTons  重量，单位吨
 * @param length      换长
 * @param valveClosed 是否关门车
 * @param detached    是否已摘下
 */
public record CarRow(
        long id,
        long jobId,
        String carNo,
        int seqNo,
        BigDecimal weightTons,
        BigDecimal length,
        boolean valveClosed,
        boolean detached) {
}
