package com.example.starter.wbb.domain;

/**
 * 双岗复核岗位：载重平衡岗与签派放行岗，须由不同人员先后签字，单人签字不生效。
 */
public enum ReviewRole {
    /** 载重平衡岗（Weight & Balance Officer）。 */
    WBO,
    /** 签派放行岗（Dispatcher）。 */
    DSP
}
