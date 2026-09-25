package com.example.starter.slot.dto;

import java.util.List;

/**
 * 统一错误响应体。
 *
 * @param code      错误码，如 SLOT_CONFLICT / IDEMPOTENT_MISMATCH / PERIOD_FROZEN / NOT_FOUND / VALIDATION_FAILED
 * @param message   错误描述
 * @param conflicts 冲突清单（仅 SLOT_CONFLICT 时非空）
 */
public record ErrorView(String code, String message, List<SlotConflictView> conflicts) {

    public static ErrorView of(String code, String message) {
        return new ErrorView(code, message, List.of());
    }
}
