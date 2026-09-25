package com.example.starter.rebate.api;

/**
 * 携带幂等重放标志的响应。首次结果落库时 replayed=false，
 * 同键同参重放时由幂等执行器重建为 true（不污染首次登记快照）。
 */
public interface ReplayableResponse<T> {

    /** 返回带指定重放标志的等价响应。 */
    T withReplayed(boolean replayed);
}
