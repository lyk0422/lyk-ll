package com.example.starter.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 受控分段指令整分钟回执请求。
 * 须按分钟序号给齐全部参与机组功率，且每项等于冻结的该分钟阶梯；
 * 缺一机组则整分钟零更新。
 *
 * @param requestId 幂等键，覆盖指令、分钟序号与全部回执项的结构化指纹
 * @param reports   全部参与机组该分钟功率
 */
public record MinuteConfirmRequest(
        @NotBlank String requestId,
        @NotEmpty @Valid List<MinuteReportItem> reports) {
}
