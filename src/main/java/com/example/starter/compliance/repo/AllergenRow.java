package com.example.starter.compliance.repo;

/**
 * 过敏原合成目录行（仅合成规则，不用于生产）。
 */
public record AllergenRow(String allergenCode, String allergenName) {
}
