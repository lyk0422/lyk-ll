package com.example.starter.quarantine;

/**
 * 动物种类；决定检疫合格证有效期（自出证时刻起算，UTC）。
 */
public enum Species {

    /** 猪，有效期 24 小时。 */
    PIG(24),
    /** 牛，有效期 24 小时。 */
    CATTLE(24),
    /** 羊，有效期 24 小时。 */
    SHEEP(24),
    /** 禽，有效期 48 小时。 */
    POULTRY(48);

    private final int validityHours;

    Species(int validityHours) {
        this.validityHours = validityHours;
    }

    /**
     * 合格证有效期小时数。
     */
    public int validityHours() {
        return validityHours;
    }
}
