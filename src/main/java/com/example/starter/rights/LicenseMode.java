package com.example.starter.rights;

/**
 * 授权模式。
 */
public enum LicenseMode {
    /** 独占：与任何存在正长度时间交集的授权不得共存。 */
    EXCLUSIVE,
    /** 非独占：仅与独占授权冲突。 */
    NON_EXCLUSIVE
}
