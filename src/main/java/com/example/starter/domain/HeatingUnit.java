package com.example.starter.domain;

/**
 * 换热站下属供热单元。
 *
 * @param id        主键
 * @param stationId 所属换热站 ID
 * @param code      单元编码，站内唯一
 * @param name      单元名称
 */
public record HeatingUnit(long id, long stationId, String code, String name) {
}
