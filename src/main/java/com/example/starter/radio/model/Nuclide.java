package com.example.starter.radio.model;

/**
 * 合成核素。半衰期单位：秒，为衰变计算唯一参数来源。
 */
public record Nuclide(long id, String code, String name, long halfLifeSeconds) {
}
