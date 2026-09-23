package com.example.starter.ctlog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 观察者树头上报请求。
 *
 * @param observerId 观察者标识
 * @param treeSize   观察者观测到的树大小
 * @param rootHash   观察者观测到的根哈希（十六进制）
 * @param timestamp  观测时间（epoch 毫秒，UTC）
 * @param signature  对规范化上报消息的 Ed25519 签名（十六进制）
 */
public record ObserveRequest(
        @NotBlank String observerId,
        @PositiveOrZero long treeSize,
        @NotBlank String rootHash,
        @NotNull Long timestamp,
        @NotBlank String signature) {
}
