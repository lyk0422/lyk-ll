package com.example.starter.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

/**
 * 受控分段执行限电指令登记请求。
 * 原最低保留、日前基数和分摊规则继续决定最终目标；本请求只追加各机组起始功率、
 * 每分钟升降上限与期限，系统据此冻结完整阶梯。
 *
 * @param requestId     幂等键，覆盖全部业务字段的结构化指纹
 * @param farmCode      风场编码
 * @param targetPowerKw 限电后全场目标出力（kW，正整数）
 * @param planDate      可选：绑定日前计划的 UTC 日期；与 segmentIndex/planVersionNo 必须同时给出或同时缺省
 * @param segmentIndex  可选：绑定时段序号 0..95（UTC 半开区间）
 * @param planVersionNo 可选：绑定的已发布计划版本号，必须为当前版本
 * @param turbines      参与机组参数，须恰好覆盖全场每台机组各一条
 */
public record ControlledOrderReceiveRequest(
        @NotBlank String requestId,
        @NotBlank String farmCode,
        @Positive int targetPowerKw,
        LocalDate planDate,
        @Min(0) @Max(95) Integer segmentIndex,
        @Positive Integer planVersionNo,
        @NotEmpty @Valid List<ControlledTurbineInput> turbines) {

    /** 是否绑定日前计划（三字段同时给出）。 */
    public boolean planBound() {
        return planDate != null;
    }
}
