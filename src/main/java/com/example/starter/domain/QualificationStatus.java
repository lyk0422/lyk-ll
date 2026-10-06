package com.example.starter.domain;

/**
 * 资格状态：ACTIVE 有效，EXPIRED 到期失效，RENEWED 已续期（被新资格接替）。
 */
public enum QualificationStatus {
    ACTIVE,
    EXPIRED,
    RENEWED
}
