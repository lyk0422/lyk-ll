package com.example.starter.wbb.domain;

/**
 * 业载明细类别。
 */
public enum PayloadCategory {
    /** 旅客（重量为人数 × 标准折算重量）。 */
    PAX,
    /** 行李。 */
    BAGGAGE,
    /** 货物。 */
    CARGO,
    /** 邮件。 */
    MAIL
}
