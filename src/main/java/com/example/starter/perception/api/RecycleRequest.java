package com.example.starter.perception.api;

/**
 * 回收轨迹请求体。
 *
 * @param expectedVersion 可选乐观锁版本，不匹配返回 409
 */
public record RecycleRequest(Long expectedVersion) {
}
