package com.example.starter.invoice.domain;

/**
 * 红冲类型：全额红冲或按行项目的部分红冲。
 */
public enum RedType {
    /** 全额红冲 */
    FULL,
    /** 部分红冲，仅允许按行项目拆分 */
    PARTIAL
}
