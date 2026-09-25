package com.example.starter.ride.dto;

/**
 * 停运后复检请求：逐项检查三项安全装置等，全部合格方可复开。
 */
public record RecheckRequest(String requestId, String rideId, Boolean pressureBarOk,
                             Boolean seatBeltOk, Boolean interlockOk) {
}
