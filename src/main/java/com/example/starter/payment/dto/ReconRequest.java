package com.example.starter.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 渠道对账文件提交请求：指定批次日与渠道侧流水集合（合成数据）。
 */
public record ReconRequest(
        @NotBlank(message = "batchDate 不能为空")
        String batchDate,
        @NotEmpty(message = "entries 不能为空")
        @Valid
        List<ChannelEntry> entries) {
}
