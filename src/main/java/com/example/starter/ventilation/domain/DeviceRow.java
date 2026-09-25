package com.example.starter.ventilation.domain;

import com.example.starter.ventilation.DeviceType;

/**
 * 设备台账行（风机/风阀）。
 *
 * @param id          主键
 * @param deviceCode  设备编码
 * @param deviceName  设备名称
 * @param deviceType  设备类型
 * @param homeZone    归属分区编码
 * @param shared      是否为共享风阀
 */
public record DeviceRow(long id, String deviceCode, String deviceName, DeviceType deviceType,
                        String homeZone, boolean shared) {
}
