package com.example.starter.ventilation.web;

import java.util.List;

/**
 * 激活/复位/归档统一响应。replay=true 表示命中 requestId 幂等重放。
 */
public record ModeResponse(
        boolean replay,
        String outcome,
        String conflictReason,
        SessionView session,
        List<String> notes
) {
}
