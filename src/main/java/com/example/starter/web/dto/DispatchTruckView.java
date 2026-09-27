package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 调度单内车辆视图。
 *
 * @param truckId    发电车ID
 * @param truckCode  发电车编号
 * @param capacityKw 冻结的车辆容量快照，单位kW
 * @param arrivedAt  到场时间，空表示未到场
 */
public record DispatchTruckView(
        long truckId,
        String truckCode,
        int capacityKw,
        LocalDateTime arrivedAt) {
}
