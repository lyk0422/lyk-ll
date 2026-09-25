package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 出水观察期体征数据：observedAt 为 UTC 纪元秒；
 * symptomatic=TRUE 启动加压舱预案并冻结结案；结案后提交仅登记迟到事件。
 */
public record VitalsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 40) String operationNo,
        @NotBlank @Size(max = 64) String diverId,
        @NotNull Long observedAt,
        @NotNull Boolean symptomatic,
        @Size(max = 500) String symptoms) {
}
