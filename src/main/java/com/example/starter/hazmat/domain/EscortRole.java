package com.example.starter.hazmat.domain;

/**
 * 接力打卡角色：交班（OUT）与接班（IN）。
 * 同一接力段每种角色仅一条打卡；双方编号须不同，单人打卡不生效。
 */
public enum EscortRole {

    /** 交班押运员：抵达接力点打卡交班。 */
    OUT,
    /** 接班押运员：打卡接续押运，时刻须严格晚于交班时刻。 */
    IN;

    /** 解析请求中的角色字符串，非法值返回 null 由调用方转 400。 */
    public static EscortRole parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return EscortRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
