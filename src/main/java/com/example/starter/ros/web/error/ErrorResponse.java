package com.example.starter.ros.web.error;

import java.time.OffsetDateTime;

/**
 * 统一错误响应体。errorCode 用于区分具体失败语义(如版本冲突/非法迁移/已终结)。
 */
public record ErrorResponse(
        String errorCode,
        String message,
        OffsetDateTime timestamp
) {
}
