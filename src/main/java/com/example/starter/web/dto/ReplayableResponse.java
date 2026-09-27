package com.example.starter.web.dto;

/**
 * 可重放响应：幂等重放时返回首次冻结结果并将 replayed 置为 true。
 *
 * @param <T> 响应自身类型
 */
public interface ReplayableResponse<T> {

    /**
     * 是否幂等重放。
     */
    boolean replayed();

    /**
     * 返回 replayed=true 的副本。
     */
    T asReplayed();
}
