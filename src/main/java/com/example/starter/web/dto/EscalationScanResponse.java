package com.example.starter.web.dto;

import java.util.List;

/**
 * 督办扫描响应：本次被升级督办的事件 id。
 */
public record EscalationScanResponse(
        List<Long> escalatedEventIds) {
}
