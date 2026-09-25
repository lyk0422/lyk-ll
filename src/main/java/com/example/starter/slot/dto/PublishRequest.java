package com.example.starter.slot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 航季分配表发布请求。
 *
 * @param season 航季标识
 */
public record PublishRequest(@NotBlank String season) {
}
