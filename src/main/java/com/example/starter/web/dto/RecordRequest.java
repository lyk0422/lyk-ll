package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 水下记录提交请求（单人一条，两人同阶段成对后生效）。
 *
 * @param requestId     本次记录操作的幂等键（每名潜水员每个阶段一条）
 * @param diveRequestId 所属潜次 requestId
 * @param diverCode     提交潜水员工号，必须是本潜次组员
 * @param phase         DESCENT/BOTTOM/ASCENT
 * @param fromDepthM    起始深度，米
 * @param toDepthM      目标深度，米
 * @param durationMin   该段历时，分钟（正整数）
 * @param eventTime     该段作业时刻（UTC），用于速率、台账与 24 小时窗口复算
 */
public record RecordRequest(
        @NotBlank String requestId,
        @NotBlank String diveRequestId,
        @NotBlank String diverCode,
        @NotBlank String phase,
        @NotNull Integer fromDepthM,
        @NotNull Integer toDepthM,
        @Positive int durationMin,
        @NotNull Instant eventTime) {

    /** 校验注解无法表达的跨字段规则。 */
    public void validate() {
        if (!"DESCENT".equals(phase) && !"BOTTOM".equals(phase) && !"ASCENT".equals(phase)) {
            throw new IllegalArgumentException("phase 必须为 DESCENT/BOTTOM/ASCENT");
        }
        if (fromDepthM < 0 || toDepthM < 0) {
            throw new IllegalArgumentException("深度不能为负");
        }
    }
}
