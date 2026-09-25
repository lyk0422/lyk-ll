package com.example.starter.web.dto;

/**
 * 单个测温/测湿点读数。温度与湿度至少一个非空。
 *
 * @param temperature 粮温，摄氏度，空表示该点未测温
 * @param humidity    相对湿度，百分比，空表示该点未测湿
 */
public record ReadingPoint(
        String pointCode,
        Double temperature,
        Double humidity
) {
}
