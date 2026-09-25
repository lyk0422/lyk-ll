package com.example.starter.transition.domain;

/**
 * 切换方式：克重差 ≤10%（恰 10% 含）可直接切换，超出须停机清洗。
 */
public enum SwitchMode {
    /** 直接切换。 */
    DIRECT,
    /** 停机清洗（占用清洗窗口）。 */
    STOP_CLEAN
}
