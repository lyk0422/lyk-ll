package com.example.starter.idempotent;

/**
 * 响应对象支持按重放标记重建。
 */
public interface Replayable<T> {

    /**
     * 返回带指定重放标记的响应副本。
     */
    T withReplay(boolean replay);
}
