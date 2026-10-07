package com.example.starter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 受控分段执行模式限电指令登记请求（可选模式；旧未启用指令保持原行为）。
 * 最终目标仍由原最低保留、日前基数（可选绑定）与分摊规则决定；
 * 登记冻结原计划基数、最终分配与各机组斜率，生成完整阶梯。
 *
 * @param requestId     幂等键，覆盖全部业务字段与精确时刻
 * @param farmCode      风场编码
 * @param targetPowerKw 限电后全场目标出力（kW，正整数）
 * @param operateAt     调用方给出的精确受理时刻（UTC，ISO-8601），参与结构化指纹
 * @param planDate      可选：绑定日前计划的 UTC 日期；三者（planDate/segmentIndex/planVersionNo）同空或同非空
 * @param segmentIndex  可选：绑定时段序号 0..95
 * @param planVersionNo 可选：绑定的当前计划版本号
 * @param turbines      参与机组登记项（非空；未列入的机组不占用、走旧回执入口）
 */
public record StagedOrderReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @Positive int targetPowerKw,
        @NotNull Instant operateAt,
        LocalDate planDate,
        @Min(0) @Max(95) Integer segmentIndex,
        @Positive Integer planVersionNo,
        @NotEmpty List<StagedTurbineInput> turbines) {

    /** 是否绑定日前计划基数（三字段同非空）。 */
    public boolean planBound() {
        return planDate != null && segmentIndex != null && planVersionNo != null;
    }

    /** 绑定三字段是否部分缺失（非法）。 */
    public boolean planBindingPartial() {
        return !planBound()
                && (planDate != null || segmentIndex != null || planVersionNo != null);
    }
}
