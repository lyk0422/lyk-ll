package com.example.starter.epidemic.api;

import com.example.starter.epidemic.domain.ClusterStatus;

/**
 * 聚集事件关闭响应：关闭为终态，重复关闭幂等成功。
 */
public record ClusterCloseResponse(long id, ClusterStatus status, String message) {
}
