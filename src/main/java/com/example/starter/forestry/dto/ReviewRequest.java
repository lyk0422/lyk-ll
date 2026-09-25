package com.example.starter.forestry.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 审核请求。
 *
 * @param approved 是否审核通过
 * @param comment  审核意见，拒绝时必填拒绝原因
 */
public record ReviewRequest(@NotNull Boolean approved, String comment) {
}
