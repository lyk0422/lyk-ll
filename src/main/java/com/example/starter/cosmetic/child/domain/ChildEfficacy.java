package com.example.starter.cosmetic.child.domain;

/**
 * 儿童允许功效目录条目（合成目录）。
 *
 * @param code      儿童功效编码
 * @param name      儿童功效名称
 * @param supported 是否允许儿童宣称；不允许的宣称拒绝
 */
public record ChildEfficacy(String code, String name, boolean supported) {
}
