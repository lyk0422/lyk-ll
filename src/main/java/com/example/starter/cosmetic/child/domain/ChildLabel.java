package com.example.starter.cosmetic.child.domain;

/**
 * 适用年龄标识（按档位独立，受理时冻结文本摘要）。
 *
 * @param bandCode  标识适用档位编码（标识适用范围即该档位年龄区间）
 * @param labelText 标识文本
 */
public record ChildLabel(String bandCode, String labelText) {
}
