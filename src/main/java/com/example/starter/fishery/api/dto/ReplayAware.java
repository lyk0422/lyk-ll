package com.example.starter.fishery.api.dto;

/**
 * 支持标记幂等重放的响应；首次成功结果快照 replayed=false，同键同参重放时置为 true。
 */
public interface ReplayAware {

    /** 返回 replayed 标记被覆盖后的同构响应。 */
    Object withReplayed(boolean replayed);
}
