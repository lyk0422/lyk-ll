package com.example.starter.tf.api;

/**
 * 缓存淘汰结果。
 *
 * @param deletedSamples 本次淘汰删除的动态样本数
 */
public record EvictResponse(int deletedSamples) {
}
