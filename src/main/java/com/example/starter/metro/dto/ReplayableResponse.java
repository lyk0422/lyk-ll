package com.example.starter.metro.dto;

/**
 * 可幂等重放的响应：重放时返回首份结果并标记 replayed。
 *
 * @param <T> 响应类型
 */
public interface ReplayableResponse<T> {

    /**
     * 返回标记为重放的副本。
     */
    T asReplayed();
}
