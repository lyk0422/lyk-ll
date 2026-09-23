package com.example.starter.trajectory.dto;

/**
 * 二维平面点，坐标单位为米。
 *
 * @param x 纵向/东向坐标，单位米
 * @param y 横向/北向坐标，单位米
 */
public record Point(double x, double y) {
}
