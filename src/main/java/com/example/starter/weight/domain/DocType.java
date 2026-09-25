package com.example.starter.weight.domain;

/**
 * 执法文书类型。
 */
public enum DocType {
    /**
     * 责令卸载放行文书（超限率不足 30%）。
     */
    UNLOAD_RELEASE,
    /**
     * 罚款记分处罚决定书（超限率达到 30% 及以上）。
     */
    PENALTY,
    /**
     * 复检推翻原处罚后生成的撤销文书。
     */
    REVOCATION,
    /**
     * 不超限放行告知（无违法）。
     */
    NO_ACTION
}
