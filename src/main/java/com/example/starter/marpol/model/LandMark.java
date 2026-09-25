package com.example.starter.marpol.model;

/**
 * 陆地参照点（合成）；“距最近陆地”取排放点到全部参照点大圆距离的最小值（海里）。
 */
public record LandMark(Long id, String name, double longitude, double latitude) {
}
