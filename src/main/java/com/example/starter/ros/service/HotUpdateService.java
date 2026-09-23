package com.example.starter.ros.service;

import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.HotUpdateResponse;

/**
 * 事务式配置热更新编排。
 */
public interface HotUpdateService {

    /**
     * 执行跨节点热更新：全节点校验候选配置 -> 原子提交版本/值/事务事件。
     * 同 transactionId 同参返回原结果；同键异参返回 409。
     */
    HotUpdateResponse update(HotUpdateRequest request);
}
