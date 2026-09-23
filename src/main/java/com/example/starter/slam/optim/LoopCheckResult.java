package com.example.starter.slam.optim;

/**
 * 回环校验结果。
 */
public final class LoopCheckResult {

    private final boolean trusted;
    private final String reason;

    private LoopCheckResult(boolean trusted, String reason) {
        this.trusted = trusted;
        this.reason = reason;
    }

    public static LoopCheckResult trusted() {
        return new LoopCheckResult(true, null);
    }

    public static LoopCheckResult rejected(String reason) {
        return new LoopCheckResult(false, reason);
    }

    public boolean isTrusted() {
        return trusted;
    }

    public String getReason() {
        return reason;
    }
}
