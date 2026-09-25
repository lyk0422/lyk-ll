package com.example.starter.transition.domain;

/**
 * 过渡纸卷终态等级；登记即 DOWNGRADED，仅一次复议改判可达 QUALIFIED。
 */
public enum FinalGrade {
    /** 强制降级，不得按正品入库。 */
    DOWNGRADED,
    /** 复议改判正品（原降级判定记录保留）。 */
    QUALIFIED
}
