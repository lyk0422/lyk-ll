package com.example.starter.cosmetic.domain;

/**
 * 儿童功效合成目录条目。
 *
 * @param code      儿童功效编码
 * @param name      功效名称
 * @param supported 是否允许儿童宣称；不允许的功效宣称拒绝
 */
public record ChildrenEfficacy(String code, String name, boolean supported) {
}
