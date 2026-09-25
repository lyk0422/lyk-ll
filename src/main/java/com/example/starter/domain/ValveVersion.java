package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 阀门开度版本链记录，与开度写入同事务提交。
 *
 * @param id         主键
 * @param valveId    阀门 ID
 * @param taskId     产生本版本的调节任务 ID
 * @param versionNo  版本号，同一阀门从 1 递增
 * @param oldOpening 变更前开度，单位 %
 * @param newOpening 变更后开度，单位 %
 * @param createdAt  版本生成时间（UTC）
 */
public record ValveVersion(long id, long valveId, long taskId, int versionNo,
                           BigDecimal oldOpening, BigDecimal newOpening, Instant createdAt) {
}
