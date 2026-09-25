package com.example.starter.forestry.domain;

/**
 * 采伐许可证状态：
 * ISSUED（有效）-> HARVESTED（已完成采伐确认，唯一业务终态）/ VOID（逾期作废，终态）。
 */
public enum LicenseStatus {
    ISSUED,
    HARVESTED,
    VOID
}
