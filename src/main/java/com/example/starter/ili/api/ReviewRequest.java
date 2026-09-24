package com.example.starter.ili.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 复核结论提交请求：对增长、严重缺陷逐条确认或降级。
 *
 * @param items 复核条目，非空
 */
public record ReviewRequest(@NotEmpty List<@Valid Item> items) {

    /**
     * 复核动作：CONFIRM=确认系统分级，DOWNGRADE=降级。
     */
    public enum Action {
        CONFIRM,
        DOWNGRADE
    }

    /**
     * 单条复核结论。
     *
     * @param matchId          匹配对主键
     * @param action           复核动作
     * @param downgradedGrade  降级目标分级（action=DOWNGRADE 时必填，取 STABLE/GROWING/SEVERE）
     * @param reviewer         复核人标识
     */
    public record Item(
            @NotNull Long matchId,
            @NotNull Action action,
            String downgradedGrade,
            @NotBlank String reviewer) {
    }
}
