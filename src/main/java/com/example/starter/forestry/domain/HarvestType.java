package com.example.starter.forestry.domain;

/**
 * 采伐类型。主伐有林龄门槛（不低于成熟龄）。
 */
public enum HarvestType {
    /** 主伐：林龄须不低于成熟龄 */
    MAIN,
    /** 抚育伐 */
    TENDING,
    /** 低产（低效）林改造 */
    REFORMATION
}
