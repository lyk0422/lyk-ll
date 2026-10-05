package com.example.starter.cutover.dto;

/**
 * 批次内前置依赖边视图。
 */
public record BatchEdgeView(
        String predecessor,
        String successor
) {
}
