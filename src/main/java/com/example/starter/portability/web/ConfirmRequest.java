package com.example.starter.portability.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 携出方确认请求，需携带申请时签发的授权码。
 */
public record ConfirmRequest(@NotBlank String authCode) {
}
