package com.example.starter.ili.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 对齐批次提交请求：一次双回合缺陷清单导入，按 requestId 幂等。
 *
 * @param requestId   客户端幂等请求标识
 * @param segmentId   管段标识
 * @param runOldId    上一检测回合标识
 * @param runNewId    本检测回合标识
 * @param runOldDate  上一回合检测日期
 * @param runNewDate  本回合检测日期，必须晚于上一回合
 * @param oldDefects  上一回合缺陷清单
 * @param newDefects  本回合缺陷清单
 */
public record SubmitBatchRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String segmentId,
        @NotBlank @Size(max = 64) String runOldId,
        @NotBlank @Size(max = 64) String runNewId,
        @NotNull LocalDate runOldDate,
        @NotNull LocalDate runNewDate,
        @NotEmpty List<@Valid DefectInput> oldDefects,
        @NotEmpty List<@Valid DefectInput> newDefects) {
}
