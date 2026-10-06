package com.example.starter.domain;

/**
 * 发射机台账行。功率单位 W；可用频率见 transmitter_channel。
 */
public record TransmitterRow(
        long id,
        String code,
        int fullPowerWatts) {
}
