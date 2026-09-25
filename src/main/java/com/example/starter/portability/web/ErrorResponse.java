package com.example.starter.portability.web;

/**
 * 统一错误报文：code 为稳定错误码，message 为人读说明。
 */
public record ErrorResponse(String code, String message) {
}
