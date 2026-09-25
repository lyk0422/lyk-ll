package com.example.starter.slaughter.service;

/**
 * 服务操作输出：业务数据、是否幂等重放以及首次响应的 HTTP 状态。
 *
 * @param data       业务响应
 * @param replayed   true 表示同键同参返回首次结果（含终态重复操作）
 * @param httpStatus 首次执行时记录的 HTTP 状态码，重放时保持一致
 */
public record ServiceOutcome<T>(T data, boolean replayed, int httpStatus) {
}
