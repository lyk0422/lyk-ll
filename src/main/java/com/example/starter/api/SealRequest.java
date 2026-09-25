package com.example.starter.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 日结封存请求：封存为终态；对已封存单重复封存返回幂等成功，封存后的迟到数据只记迟到事件。
 */
public record SealRequest(
        @NotBlank String requestId
) {
}
