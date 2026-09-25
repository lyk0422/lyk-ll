package com.example.starter.ventilation.domain;

/**
 * 分区风阀编组行。
 *
 * @param id         主键
 * @param zoneCode   分区编码
 * @param damperCode 风阀编码
 * @param bias       方向偏置：PLUS / MINUS
 */
public record ZoneDamperRefRow(long id, String zoneCode, String damperCode, String bias) {
}
