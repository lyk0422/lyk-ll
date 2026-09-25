package com.example.starter.tailings.domain;

/**
 * 调洪演算判定结论。severity 用于库容口径与安全口径取最重：
 * SAFE 安全 &lt; CRITICAL 临界 &lt; MUST_DISCHARGE 须泄流 &lt; DANGER 险。
 */
public enum Verdict {

    SAFE(0),
    CRITICAL(1),
    MUST_DISCHARGE(2),
    DANGER(3);

    private final int severity;

    Verdict(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }

    /**
     * 取两个口径中更重的判定。
     */
    public static Verdict worst(Verdict a, Verdict b) {
        return a.severity >= b.severity ? a : b;
    }
}
