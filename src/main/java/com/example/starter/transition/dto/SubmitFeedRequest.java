package com.example.starter.transition.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 投料申请请求：引用同一执行中订单、当前掺配版本、总浆量及 1 至 10 个不同回收卷。
 * 回收合计须精确等于 总浆量×该版本目标比例/100。
 *
 * @param requestId    幂等键
 * @param orderNo      改品种单号，投料时须为执行中
 * @param versionNo    掺配比例版本号，须等于订单当前版本，版本已换拒绝
 * @param totalPulpKg  总浆量，单位 kg，正数最多三位小数
 * @param items        回收卷及各自公斤数，1 至 10 个不同卷
 */
public record SubmitFeedRequest(
        @NotBlank String requestId,
        @NotBlank String orderNo,
        @NotNull Integer versionNo,
        @NotNull BigDecimal totalPulpKg,
        @NotNull @Size(min = 1, max = 10) @Valid List<FeedItem> items) {

    /**
     * 单个回收卷投料项。
     *
     * @param rollNo   回收卷号，同一批内不得重复
     * @param weightKg 本卷投料公斤数，单位 kg，正数最多三位小数
     */
    public record FeedItem(
            @NotBlank String rollNo,
            @NotNull BigDecimal weightKg) {
    }
}
