package com.example.starter.dto;

/**
 * 可重放响应：同 requestId 同参重放时返回 replayed=true 的等价副本。
 *
 * @param <T> 响应自身类型
 */
public interface ReplayableResponse<T> {

    T asReplay();
}
