package com.example.starter.transition.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 投料申请请求：引用同一执行中订单、当前掺配版本、总浆量以及 1 至 10 个不同回收卷。
 * 回收合计须精确等于总浆量乘该版本目标比例（按百分数精确计算，不做展示舍入）。
 *
 * @param requestId       幂等键
 * @param orderNo         执行中改品种单号
 * @param blendVersionNo  投料引用的掺配比例版本号，须为当前生效版本
 * @param totalPulpKg     总浆量，单位 kg，正数最多三位小数
 * @param items           回收卷及各自公斤数，1 至 10 个不同卷
 */
public record FeedRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo,
        @NotNull Integer blendVersionNo,
        @NotNull BigDecimal totalPulpKg,
        @NotEmpty @Size(max = 10) @Valid List<FeedItem> items) {

    /**
     * 单个回收卷投料项。
     *
     * @param rollNo 回收卷号
     * @param kg     本卷投料公斤数，单位 kg，正数最多三位小数
     */
    public record FeedItem(
            @NotBlank String rollNo,
            @NotNull BigDecimal kg) {
    }
}
