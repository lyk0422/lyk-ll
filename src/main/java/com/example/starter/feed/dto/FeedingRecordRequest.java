package com.example.starter.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 含药饲喂记录请求。用于登记批次的含药饲喂事件；
 * 出栏后迟到的饲喂仅记录为迟到事件并产生合规告警，不修改出栏终态。
 *
 * @param formulaCode 已投产配方编码
 * @param version     已投产版本号
 * @param batchNo     养殖批次号
 * @param feedAtUtc   本次含药饲喂时刻（UTC）
 */
public record FeedingRecordRequest(
        @NotBlank(message = "配方编码不能为空")
        @Size(max = 64)
        String formulaCode,

        @Positive(message = "版本号必须为正数")
        int version,

        @NotBlank(message = "批次号不能为空")
        @Size(max = 64)
        String batchNo,

        @NotNull(message = "含药饲喂时刻不能为空")
        Instant feedAtUtc
) {
}
