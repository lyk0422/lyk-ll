package com.example.starter.web.dto;

/**
 * 发电车台账视图：容量、服务馈线、可用状态与当前占用。
 *
 * @param id              发电车ID
 * @param code            发电车编号
 * @param capacityKw      额定容量，单位kW
 * @param feederId        服务馈线编号
 * @param available       可用状态
 * @param occupied        是否被未结束调度占用
 * @param occupiedOrderId 当前占用调度单ID，空表示空闲
 */
public record VehicleView(
        Long id,
        String code,
        int capacityKw,
        String feederId,
        boolean available,
        boolean occupied,
        Long occupiedOrderId) {
}
