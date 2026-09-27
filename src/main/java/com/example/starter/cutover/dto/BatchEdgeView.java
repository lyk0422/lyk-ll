package com.example.starter.cutover.dto;

/**
 * 冻结的有向依赖边视图（fromRequestId -> toRequestId，按 (from,to) 稳定排序）。
 */
public record BatchEdgeView(
        String fromRequestId,
        String toRequestId,
        int ord
) {
}
