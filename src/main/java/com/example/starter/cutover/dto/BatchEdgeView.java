package com.example.starter.cutover.dto;

/**
 * 批次依赖边视图（按 predecessor、successor 稳定升序）。
 */
public record BatchEdgeView(
        String predecessor,
        String successor
) {
}
