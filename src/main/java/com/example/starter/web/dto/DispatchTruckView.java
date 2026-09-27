package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 调度单内车辆视图。
 *
 * @param truckId    车辆ID
 * @param capacityKw 容量，单位kW
 * @param arrivedAt  到场时刻，空表示未到场
 */
public record DispatchTruckView(long truckId, int capacityKw, LocalDateTime arrivedAt) {
}
