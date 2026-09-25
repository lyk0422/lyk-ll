package com.example.starter.ride.dto;

/**
 * 安全装置失效停运请求：压杠/安全带/联锁任一失效整机立即停运。
 */
public record SafetyFaultRequest(String requestId, String rideId, String deviceType) {
}
