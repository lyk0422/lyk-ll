package com.example.starter.runway.dto;

/**
 * 统一错误报文。
 *
 * @param code     稳定错误码
 * @param message  错误说明
 * @param evidence 结构化冲突证据（JSON 文本）；无证据时为 null
 */
public record ErrorResponse(String code, String message, String evidence) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
