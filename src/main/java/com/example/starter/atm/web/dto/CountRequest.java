package com.example.starter.atm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 双人清点请求：两名不同押运员共同清点并登记清点数，单位：分。 */
public record CountRequest(
        @NotBlank String requestId,
        @NotBlank String guard1No,
        @NotBlank String guard2No,
        @NotNull @Positive Long countedAmount
) {
}
