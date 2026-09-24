package com.example.starter.ili;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 复核结论提交请求：对增长、严重缺陷逐条确认或降级。
 *
 * @param action          复核动作：CONFIRM=确认原分级，DOWNGRADE=降级
 * @param downgradedGrade 降级目标等级（DOWNGRADE 时必填，仅允许低于原分级）
 * @param reviewer        复核人标识
 */
public record ReviewRequest(
        @NotNull ReviewAction action,
        Grade downgradedGrade,
        @NotBlank @Size(max = 64) String reviewer) {

    /**
     * 复核动作。
     */
    public enum ReviewAction {
        /** 确认系统分级。 */
        CONFIRM,
        /** 将分级降级到较低等级。 */
        DOWNGRADE
    }
}
