package com.example.starter.signing.web;

/**
 * 取消请求；原因为可选合成信息。
 *
 * @param reason 取消原因，可为空
 */
public record CancelRequest(String reason) {
}
