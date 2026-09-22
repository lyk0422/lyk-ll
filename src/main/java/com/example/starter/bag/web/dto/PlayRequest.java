package com.example.starter.bag.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 回放请求。范围边界均可空，空表示不限制；playKey 相同的重复请求不重复创建回放记录。
 */
public record PlayRequest(
        @NotBlank String playKey,
        @NotBlank String topic,
        Long startNs,
        Long endNs,
        Long startSeq,
        Long endSeq) {
}
