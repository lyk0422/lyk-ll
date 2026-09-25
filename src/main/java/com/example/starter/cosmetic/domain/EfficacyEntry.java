package com.example.starter.cosmetic.domain;

/**
 * 功效目录条目。
 */
public record EfficacyEntry(String code, String name, boolean special, boolean supported) {
}
