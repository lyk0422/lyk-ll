package com.example.starter.reinsurance.domain;

/**
 * 账单类型。
 */
public enum BillType {

    /** 按季度（UTC 日界）生成的常规分保账单。 */
    QUARTERLY,

    /** 下一期调整单：必须引用原账单号，仅在原账单封存后提出。 */
    ADJUSTMENT
}
