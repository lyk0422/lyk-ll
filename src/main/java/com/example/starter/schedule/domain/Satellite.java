package com.example.starter.schedule.domain;

import java.time.Instant;

/**
 * 卫星主数据（合成数据）。
 *
 * @param id 主键，空值表示未持久化
 * @param code 卫星业务编码，全局唯一
 * @param name 卫星名称
 * @param createdAt 创建时刻（UTC）
 */
public record Satellite(Long id, String code, String name, Instant createdAt) {
}
