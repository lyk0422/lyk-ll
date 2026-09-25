package com.example.starter.marpol.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 签发后补充证据的复查请求，必须引用原核查号。
 */
public record ReviewRequest(
        @NotBlank String inspectionNo,
        Long itemId,
        @NotBlank @Size(max = 2048) String evidence) {
}
