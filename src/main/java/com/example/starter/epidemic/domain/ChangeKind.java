package com.example.starter.epidemic.domain;

/**
 * 版本来源。
 */
public enum ChangeKind {
    /** 初报版本。 */
    INITIAL,
    /** 审核通过后、归档前的普通订正。 */
    CORRECTION,
    /** 归档后经两名不同审核员确认生效的特殊订正。 */
    SPECIAL
}
