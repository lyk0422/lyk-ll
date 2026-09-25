package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 终审归档请求。
 *
 * @param reviewer 终审员工号
 * @param comment  终审意见
 */
public record ArchiveRequest(@NotBlank String reviewer, String comment) {
}
