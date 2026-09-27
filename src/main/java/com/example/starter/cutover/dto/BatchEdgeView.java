package com.example.starter.cutover.dto;

/**
 * 依赖边视图：fromRequestId 为前置，toRequestId 为后继。
 */
public record BatchEdgeView(
        String fromRequestId,
        String toRequestId
) {
}
