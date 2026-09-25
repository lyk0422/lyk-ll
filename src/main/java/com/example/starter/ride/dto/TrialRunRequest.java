package com.example.starter.ride.dto;

/**
 * 空载试运行请求：上报三项安全装置状态及是否无异常。
 */
public record TrialRunRequest(String requestId, String rideId, Boolean pressureBarOk,
                              Boolean seatBeltOk, Boolean interlockOk, Boolean noAnomaly) {
}
