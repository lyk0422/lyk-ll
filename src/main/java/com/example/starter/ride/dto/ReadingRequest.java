package com.example.starter.ride.dto;

/**
 * 运行监测读数：载荷单位 kg、速度单位 m/s；恰等阈值合格。
 */
public record ReadingRequest(String requestId, String rideId, Double loadKg, Double speedMps) {
}
