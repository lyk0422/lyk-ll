package com.example.starter.tf.persistence;

/**
 * 单条动态边的一次淘汰结果。
 *
 * @param deletedCount  删除样本条数
 * @param maxStampNanos 被删样本中的最高时间戳，epoch 纳秒
 */
public record EvictionOutcome(int deletedCount, long maxStampNanos) {
}
