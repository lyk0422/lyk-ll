package com.example.starter.model;

import java.time.Instant;

/**
 * 油机档案。
 *
 * @param id        主键
 * @param code      油机编号，全局唯一
 * @param status    油机状态：IDLE 空闲 / ASSIGNED 已分配
 * @param createdAt 创建时间，UTC
 */
public record Generator(long id, String code, GeneratorStatus status, Instant createdAt) {
}
