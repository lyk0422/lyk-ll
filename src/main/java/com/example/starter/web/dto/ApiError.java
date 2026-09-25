package com.example.starter.web.dto;

import com.example.starter.domain.UnitStatus;

/**
 * 统一错误响应体。
 *
 * @param code          业务错误码
 * @param message       错误描述
 * @param currentStatus 涉及单位时的当前真实状态，无涉及时为 null
 */
public record ApiError(
        String code,
        String message,
        UnitStatus currentStatus) {
}
