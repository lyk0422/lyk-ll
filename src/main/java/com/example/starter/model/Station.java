package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 基站档案。
 *
 * @param id                  主键
 * @param code                基站编码，全局唯一
 * @param loadKw              平均负载功率，单位千瓦
 * @param batteryCapacityKwh  电池组额定容量，单位千瓦时
 * @param createdAt           创建时间，UTC
 */
public record Station(long id, String code, BigDecimal loadKw, BigDecimal batteryCapacityKwh,
                      Instant createdAt) {
}
