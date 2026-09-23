package com.example.starter.playout.web;

/**
 * 统一错误响应体；同键请求的首次失败结果也以该结构冻结。
 *
 * @param status  HTTP 状态码
 * @param error   状态码短语
 * @param message 错误描述
 */
public record ErrorBody(int status, String error, String message) {
}
