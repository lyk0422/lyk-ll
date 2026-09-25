package com.example.starter.fishery.db;

/**
 * 鱼种台账行（合成数据）；{@code protectedSpecies=true} 表示兼捕保护鱼种。
 */
public record SpeciesRow(String code, String name, boolean protectedSpecies) {
}
