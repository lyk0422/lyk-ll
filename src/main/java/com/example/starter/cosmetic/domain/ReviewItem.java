package com.example.starter.cosmetic.domain;

/**
 * 单项形式审查结果（内存对象，落库为 review_record）。
 */
public record ReviewItem(String item, boolean passed, String detail) {
}
