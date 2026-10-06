package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 路段档案行。
 */
public record SegmentRow(long id, String code, String name, LocalDateTime createdAt) {
}
