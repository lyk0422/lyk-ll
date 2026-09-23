package com.example.starter.rights;

/**
 * 授权版本状态。
 */
public enum LicenseStatus {
    /** 当前有效版本。 */
    ACTIVE,
    /** 已被更正产生的新版本替换，历史保留只读。 */
    SUPERSEDED,
    /** 已撤销；阻止未开始排期，不影响已冻结的播出证据。 */
    REVOKED
}
