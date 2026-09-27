package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 容量及车辆占用台账条目。
 *
 * @param truckId               发电车ID
 * @param truckCode             发电车编号
 * @param capacityKw            额定容量，单位kW
 * @param feederId              服务馈线编号
 * @param available             是否可用
 * @param version               车辆版本
 * @param occupiedByDispatchId  占用中的调度单ID，空表示空闲
 * @param occupiedByWorkOrderId 占用关联的工单ID，空表示空闲
 * @param occupiedSince         占用开始时间（调度单下发时间），空表示空闲
 */
public record TruckLedgerEntry(
        long truckId,
        String truckCode,
        int capacityKw,
        String feederId,
        boolean available,
        int version,
        Long occupiedByDispatchId,
        Long occupiedByWorkOrderId,
        LocalDateTime occupiedSince) {
}
