package com.example.starter.cutover.dto;

/**
 * 写操作统一返回。replayed=true 表示命中幂等，返回首次结果快照，未产生新记录。
 */
public record ActionResult(
        boolean replayed,
        String message,
        RequestView request
) {
}
