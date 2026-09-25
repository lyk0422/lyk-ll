package com.example.starter.marpol.model;

import java.util.List;

/**
 * 特殊区域（合成多边形）。
 *
 * @param polygon 环点，每点为 [经度, 纬度]；恰在边界线上视为区域内
 */
public record SpecialArea(Long id, String name, List<double[]> polygon) {
}
