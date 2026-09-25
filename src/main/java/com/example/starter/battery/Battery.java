package com.example.starter.battery;

/**
 * 电池资产台账记录。
 *
 * @param id 主键
 * @param code 电池唯一编码
 * @param status 当前状态
 * @param stationId 当前所在站点，空表示在车辆侧或调拨在途
 * @param vehicleId 当前绑定车辆，空表示未绑定
 * @param soc 最近检测 SOC（百分比），空表示未检测
 * @param health 最近检测健康度（百分比），空表示未检测
 * @param inspectionBatch 最近检测批次号，空表示未检测
 * @param createdAt 建档时间（UTC epoch 毫秒）
 * @param updatedAt 最近更新时间（UTC epoch 毫秒）
 */
public record Battery(
        long id,
        String code,
        BatteryStatus status,
        String stationId,
        String vehicleId,
        Integer soc,
        Integer health,
        Long inspectionBatch,
        long createdAt,
        long updatedAt) {
}
