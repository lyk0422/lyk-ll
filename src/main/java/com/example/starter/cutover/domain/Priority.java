package com.example.starter.cutover.domain;

/**
 * 变更优先级；rank 越大优先级越高，用于同窗口抢占判定。
 */
public enum Priority {

    HIGH(3),
    NORMAL(2),
    LOW(1);

    private final int rank;

    Priority(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }
}
