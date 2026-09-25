package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 告警/事件视图。
 */
public record AlarmView(long id, Long turbineId, String type, String message,
                        LocalDateTime createdAt) {
}
