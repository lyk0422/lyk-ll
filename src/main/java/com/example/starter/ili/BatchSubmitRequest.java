package com.example.starter.ili;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 对齐批次提交请求：一次导入两个检测回合的缺陷清单并执行对齐与分级。
 *
 * @param requestId   客户端幂等请求标识
 * @param segmentId   管段标识
 * @param oldRun      旧检测回合
 * @param newRun      新检测回合
 */
public record BatchSubmitRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String segmentId,
        @NotNull @Valid RunDefects oldRun,
        @NotNull @Valid RunDefects newRun) {

    /**
     * 单个检测回合的标识、日期与缺陷读数清单。
     *
     * @param runId    回合标识
     * @param runDate  检测日期
     * @param defects  缺陷读数清单（合成数据）
     */
    public record RunDefects(
            @NotBlank @Size(max = 64) String runId,
            @NotNull LocalDate runDate,
            @NotEmpty List<@Valid DefectReading> defects) {
    }

    /**
     * 单条缺陷读数。
     *
     * @param defectRef 客户端缺陷编号，批次内单侧唯一
     * @param mileageM  绝对里程，单位米
     * @param depthPct  缺陷深度，占壁厚百分比（0-100）
     */
    public record DefectReading(
            @NotBlank @Size(max = 64) String defectRef,
            @NotNull BigDecimal mileageM,
            @NotNull BigDecimal depthPct) {
    }
}
