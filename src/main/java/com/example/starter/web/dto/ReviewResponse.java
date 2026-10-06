package com.example.starter.web.dto;

import java.util.List;

/**
 * 初查处置响应：事件状态与本次确认干扰后处于执行中的限功率指令 id。
 */
public record ReviewResponse(
        long eventId,
        String status,
        List<Long> executingDirectiveIds) {
}
