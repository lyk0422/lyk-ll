package com.example.starter.ros.service;

import com.example.starter.ros.dto.PendingRestartRequest;

import java.util.List;

/** 待重启配置：静态参数变更的唯一合法去向。 */
public interface PendingRestartService {

    /**
     * 登记静态参数待重启值；动态参数混入将被拒绝（409）。
     * 返回登记条目的限定名列表。
     */
    List<String> register(PendingRestartRequest request);
}
