package com.example.starter.ctlog.dto;

import java.util.List;

/**
 * 分裂视图告警列表响应。
 */
public record AlertsResponse(List<AlertView> alerts) {
}
