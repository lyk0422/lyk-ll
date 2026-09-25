package com.example.starter.pension.domain;

/**
 * 分期方式。
 */
public enum InstallmentType {

    /** 一次性支付。 */
    LUMP_SUM(1),
    /** 按月 12 期。 */
    MONTHLY_12(12),
    /** 按月 24 期。 */
    MONTHLY_24(24);

    private final int count;

    InstallmentType(int count) {
        this.count = count;
    }

    /**
     * @return 分期总期数；一次性为 1。
     */
    public int count() {
        return count;
    }
}
