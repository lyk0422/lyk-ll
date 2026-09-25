package com.example.starter.domain;

/**
 * 进场批次状态。
 * REGISTERED 已报备（可抽样、可快检）；SUSPECTED 疑似超标冻结（等待定量复检）；
 * RELEASED 放行（终态）；DISPOSED 监督处置结案（终态）。
 */
public enum BatchStatus {
    REGISTERED,
    SUSPECTED,
    RELEASED,
    DISPOSED;

    /**
     * 是否为终态（放行/处置结案），终态后只允许记录迟到事件。
     */
    public boolean isTerminal() {
        return this == RELEASED || this == DISPOSED;
    }
}
