package com.example.starter.ventilation.domain;

/**
 * 防火分区主数据行。
 *
 * @param id       主键
 * @param zoneCode 分区编码
 * @param zoneName 分区名称
 */
public record FireZoneRow(long id, String zoneCode, String zoneName) {
}
