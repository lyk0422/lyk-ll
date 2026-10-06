package com.example.starter.dto;

import java.util.List;

/**
 * 逾期升级扫描响应：本次新增的升级告警列表（重复执行幂等，不重复告警）。
 */
public record EscalateResponse(
        int escalatedCount,
        List<AlertView> alerts) {
}
