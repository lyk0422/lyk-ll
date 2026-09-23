package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 入库请求。
 *
 * @param volumeUl 原始体积（微升，最多三位小数字符串）
 */
public record IntakeRequest(
        @NotBlank(message = "不能为空") String specimenId,
        @NotBlank(message = "不能为空") String volumeUl,
        @NotBlank(message = "不能为空") String custodian) {
}
