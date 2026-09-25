package com.example.starter.legality.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 排班整组校验请求：对批次内每个机组成员独立判定，在同一事务内给出整组结果。
 *
 * @param batchId 批次ID
 * @param members 待校验机组成员值勤条目，同一批次内机组成员不得重复
 */
public record BatchCheckRequest(
        @NotBlank String batchId,
        @NotEmpty @Valid List<MemberDuty> members) {

    /**
     * 批次内单个成员的值勤条目。
     *
     * @param requestId        幂等请求ID
     * @param crewId           机组成员ID
     * @param flightNo         航班号
     * @param reportTime       报到时刻
     * @param scheduledEndTime 计划执勤结束时刻
     */
    public record MemberDuty(
            @NotBlank String requestId,
            @NotBlank String crewId,
            @NotBlank String flightNo,
            @NotNull OffsetDateTime reportTime,
            @NotNull OffsetDateTime scheduledEndTime) {
    }
}
