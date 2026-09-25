package com.example.starter.museum.api;

import jakarta.validation.constraints.NotBlank;

/** 结案请求。结案为终态，重复结案按 requestId/合同返回幂等成功。 */
public record CloseRequest(
        @NotBlank String requestId,
        @NotBlank String contractNo
) {
}
