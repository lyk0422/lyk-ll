package com.example.starter.audit.domain;

import java.util.Map;

/**
 * 单个阶段的确定性产物：关键指标、合成产物字节与可重放命令。
 * 产物相对路径与 SHA-256 由编排层统一计算并落库。
 */
public record StageProduct(
        Map<String, Double> metrics,
        byte[] artifactBytes,
        String replayCommand
) {
}
