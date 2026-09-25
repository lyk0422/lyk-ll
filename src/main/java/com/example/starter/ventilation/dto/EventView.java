package com.example.starter.ventilation.dto;

import java.time.LocalDateTime;

/**
 * 有序事件视图：指令、反馈、状态迁移与告警升级均可按 id 顺序追溯。
 */
public record EventView(
        Long id,
        Long modeId,
        String zoneCode,
        String requestId,
        String eventType,
        String detail,
        LocalDateTime eventTime) {
}
