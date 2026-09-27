package com.example.starter.web.dto;

/**
 * 发电车视图：登记与可用性变更的响应，version 为变更后的版本号。
 *
 * @param id         车辆ID
 * @param capacityKw 容量，单位kW
 * @param feederId   服务馈线编号
 * @param available  可用状态
 * @param version    当前版本号
 */
public record TruckView(long id, int capacityKw, String feederId, boolean available, int version) {
}
