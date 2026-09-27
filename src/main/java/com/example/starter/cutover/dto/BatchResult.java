package com.example.starter.cutover.dto;

/**
 * 批次发布统一返回。replayed=true 表示命中幂等，返回首次冻结结果，未产生新记录。
 */
public record BatchResult(
        boolean replayed,
        String message,
        BatchView batch
) {
}
