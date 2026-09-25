package com.example.starter.ledger.web;

/**
 * 操作结果：业务视图 + 是否为首请求（false 表示同键同参重放返回的首次结果）。
 *
 * @param <T> 业务视图类型
 */
public class OperationResult<T> {

    private final T data;
    private final boolean replayed;

    public OperationResult(T data, boolean replayed) {
        this.data = data;
        this.replayed = replayed;
    }

    public T getData() {
        return data;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
