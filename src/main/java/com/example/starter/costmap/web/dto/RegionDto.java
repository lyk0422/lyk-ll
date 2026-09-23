package com.example.starter.costmap.web.dto;

/**
 * 栅格矩形区域（含边界，单元格坐标）。
 */
public record RegionDto(int x0, int y0, int x1, int y1) {
}
