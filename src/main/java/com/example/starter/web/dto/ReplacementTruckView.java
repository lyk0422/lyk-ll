package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 接替单内热备新车视图。
 *
 * @param truckId    车辆ID
 * @param capacityKw 容量，单位kW
 * @param arrivedAt  到场时刻，空表示未到场
 */
public record ReplacementTruckView(long truckId, int capacityKw, LocalDateTime arrivedAt) {
}
