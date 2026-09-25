package com.example.starter.atm.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 日结封存请求：封存为终态；重复封存幂等成功，迟到数据仅登记迟到事件。 */
public record SealRequest(@NotBlank String requestId) {
}
