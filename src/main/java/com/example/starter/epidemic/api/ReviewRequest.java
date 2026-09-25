package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 初审通过请求。
 *
 * @param reviewer 审核员工号
 * @param comment  审核意见
 */
public record ReviewRequest(@NotBlank String reviewer, String comment) {
}
