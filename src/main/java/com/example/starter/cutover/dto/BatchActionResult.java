package com.example.starter.cutover.dto;

/**
 * 批次写操作统一返回。replayed=true 表示命中幂等，返回首次冻结结果，未产生新记录。
 */
public record BatchActionResult(
        boolean replayed,
        String message,
        BatchView batch
) {
}
