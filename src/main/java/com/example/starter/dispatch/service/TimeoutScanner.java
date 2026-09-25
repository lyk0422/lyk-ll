package com.example.starter.dispatch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时升级扫描器：定时把超过执行时限且未收齐回执的令升级为 TIMEOUT。
 * 测试中通过 {@code dispatch.timeout.scan-ms} 放大间隔改为人工调用
 * {@link DispatchService#scanTimeouts()}。
 */
@Component
public class TimeoutScanner {

    private final DispatchService dispatchService;

    public TimeoutScanner(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${dispatch.timeout.scan-ms:200}",
            initialDelayString = "${dispatch.timeout.scan-ms:200}")
    public void scan() {
        dispatchService.scanTimeouts();
    }
}
