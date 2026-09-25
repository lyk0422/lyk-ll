package com.example.starter.survey.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 登记合成船舶。 */
public record CreateShipRequest(
        @NotBlank String requestId,
        @NotBlank String imo,
        @NotBlank String name,
        @NotNull LocalDate annualAnniversary,
        @NotNull LocalDate deliveryDate
) {
}
