package com.example.starter.web.dto;

/**
 * 风压读数响应。
 *
 * @param readingId 读数ID；迟到数据仅记事件时为 null
 * @param recorded  是否记入试验曲线
 * @param late      是否销记后迟到数据
 * @param message   说明
 */
public record PressureReadingResponse(
        Long readingId,
        boolean recorded,
        boolean late,
        String message) {
}
