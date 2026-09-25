package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 指令签收/执行动作请求（同一操作人记录留痕）。
 *
 * @param operator 签收人/执行人
 */
public record OrderActionRequest(@NotBlank String operator) {
}
