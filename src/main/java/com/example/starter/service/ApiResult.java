package com.example.starter.service;

/**
 * 幂等端点的业务受理结果(状态码+响应体), 将被存入幂等记录以支持同键重放。
 */
public record ApiResult(int status, Object body) {
}
