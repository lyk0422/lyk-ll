package com.example.starter.lc.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 统一错误响应。
 */
public record ErrorResponse(String code, String message, OffsetDateTime timestamp, List<String> details) {
}
