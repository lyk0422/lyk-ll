package com.example.starter.rt.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建计划版本请求（设计态）。同一 planCode 再次创建即“换版”，旧版本在审核通过并重新批准的同一事务内失效。
 *
 * @param patientId                患者ID
 * @param planCode                 计划业务编码
 * @param templateCode             使用的约束模板编码
 * @param prescribedTotalDoseCgy   处方总剂量(cGy)，须等于分次剂量×次数
 * @param fractionDoseCgy          单次分次剂量(cGy)
 * @param fractionCount            处方分次次数（正整数）
 * @param oarDoses                 申报的危及器官剂量体积数据，须覆盖模板全部约束项
 */
public record CreatePlanRequest(
        @NotBlank(message = "不能为空") String patientId,
        @NotBlank(message = "不能为空") String planCode,
        @NotBlank(message = "不能为空") String templateCode,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Long prescribedTotalDoseCgy,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Long fractionDoseCgy,
        @NotNull(message = "不能为空") @Positive(message = "须为正数") Integer fractionCount,
        @NotEmpty(message = "至少申报一项危及器官剂量") @Valid List<OarDoseRequest> oarDoses) {
}
