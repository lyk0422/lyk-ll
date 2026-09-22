package com.example.starter.route;

/**
 * 匹配输入点：平面坐标 + 航向角。
 *
 * @param x       坐标 X，单位米
 * @param y       坐标 Y，单位米
 * @param heading 航向角，单位度，范围 [0,360)
 */
public record MatchPoint(double x, double y, double heading) {
}
