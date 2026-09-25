package com.example.starter.pv.web;

/**
 * 关闭个例请求；requestId 仅用于调用链追踪，关闭终态本身天然幂等。
 *
 * @param requestId 可选请求标识
 */
public record CloseRequest(String requestId) {
}
