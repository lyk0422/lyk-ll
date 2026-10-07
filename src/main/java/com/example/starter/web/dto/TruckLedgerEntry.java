package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 车占用台账条目：车辆容量、可用状态与当前未结束占用/热备预占（空表示空闲）。
 *
 * @param id             车辆ID
 * @param capacityKw     容量，单位kW
 * @param feederId       服务馈线编号
 * @param available      可用状态
 * @param version        当前版本号
 * @param occupationType 占用类型：SUPPLY供电占用/STANDBY热备预占，空表示空闲
 * @param dispatchId     供电占用该车的调度单ID，空表示非供电占用
 * @param workOrderId    占用关联的工单ID，空表示空闲
 * @param replacementId  热备预占该车的接替单ID，空表示非热备预占
 * @param occupiedAt     占用或预占开始时间，空表示空闲
 */
public record TruckLedgerEntry(long id, int capacityKw, String feederId, boolean available, int version,
        String occupationType, Long dispatchId, Long workOrderId, Long replacementId,
        LocalDateTime occupiedAt) {
}
