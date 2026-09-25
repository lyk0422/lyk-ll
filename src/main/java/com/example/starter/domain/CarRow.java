package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 编组车辆行(对应 operation_car 表), 每个编组版本保存完整快照。
 *
 * @param id             车辆行主键
 * @param operationId    所属作业ID
 * @param consistVersion 所属编组版本号
 * @param carNo          车辆号(合成数据)
 * @param lengthCoeff    换长
 * @param weightTons     总重(吨)
 * @param brakeCutout    是否关门车
 */
public record CarRow(long id, long operationId, int consistVersion, String carNo,
                     BigDecimal lengthCoeff, BigDecimal weightTons, boolean brakeCutout) {
}
