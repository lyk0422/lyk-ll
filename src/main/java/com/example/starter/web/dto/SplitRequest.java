package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 分装请求：携带幂等键与样本期望版本，一次生成多个 aliquot。
 *
 * @param expectedVersion 样本当前版本，必填，不匹配返回 409；纳入幂等指纹
 * @param custodian 请求级默认保管人，aliquot 未单独指定时使用
 */
public record SplitRequest(
        @NotBlank(message = "不能为空") String specimenId,
        @NotBlank(message = "不能为空") String requestId,
        @NotNull(message = "不能为空") Integer expectedVersion,
        String custodian,
        @NotEmpty(message = "至少包含一个分装条目") @Valid List<SplitItemRequest> aliquots) {
}
