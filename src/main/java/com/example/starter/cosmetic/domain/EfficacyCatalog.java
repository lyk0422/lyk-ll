package com.example.starter.cosmetic.domain;

/**
 * 功效目录条目（合成目录）。
 *
 * @param code         功效编码
 * @param name         功效名称
 * @param special      是否特殊功效（特殊功效须上传对应功效评价试验报告）
 * @param supported    是否允许宣称；不支持的功效宣称拒绝
 * @param childAllowed 是否儿童合成目录允许宣称；儿童备案只允许本标记允许的功效
 */
public record EfficacyCatalog(String code, String name, boolean special, boolean supported,
                              boolean childAllowed) {
}
