package com.example.starter.gas.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封账请求：争议期结束后封账为终态；重复封账幂等成功。
 */
public record SealRequest(@NotBlank String requestId) {
}
