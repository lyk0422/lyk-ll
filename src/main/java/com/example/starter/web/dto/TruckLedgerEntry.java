package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 车占用台账条目：车辆容量、可用状态与当前未结束占用（空表示空闲）。
 * 占用区分供电占用（SUPPLY）与热备预占（STANDBY）：前者 dispatchId 非空，
 * 后者 replacementId 非空，查询须区分两种占用。
 *
 * @param id             车辆ID
 * @param capacityKw     容量，单位kW
 * @param feederId       服务馈线编号
 * @param available      可用状态
 * @param version        当前版本号
 * @param dispatchId     供电占用该车的调度单ID，空表示无供电占用
 * @param workOrderId    供电占用关联的工单ID，空表示无供电占用
 * @param replacementId  热备预占的接替单ID，空表示无热备预占
 * @param occupationType 占用类型：SUPPLY供电占用/STANDBY热备预占，空表示空闲
 * @param occupiedAt     占用开始时间，空表示空闲
 */
public record TruckLedgerEntry(long id, int capacityKw, String feederId, boolean available, int version,
        Long dispatchId, Long workOrderId, Long replacementId, String occupationType,
        LocalDateTime occupiedAt) {
}
