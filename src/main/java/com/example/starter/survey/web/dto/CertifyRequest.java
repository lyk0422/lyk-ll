package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 签发/换发证书；签发为终态，重复签发幂等成功。 */
public record CertifyRequest(
        @NotBlank String requestId,
        @NotNull Long surveyId
) {
}
