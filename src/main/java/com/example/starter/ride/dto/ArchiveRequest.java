package com.example.starter.ride.dto;

/**
 * 闭园归档请求；归档为终态，重复归档返回幂等成功。
 */
public record ArchiveRequest(String requestId, String rideId) {
}
