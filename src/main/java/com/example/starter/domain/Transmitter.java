package com.example.starter.domain;

/**
 * 发射机合成台账。
 *
 * @param id          主键
 * @param code        发射机编号
 * @param fullPowerW  额定满功率，单位 W
 */
public record Transmitter(long id, String code, int fullPowerW) {
}
