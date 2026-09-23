package com.example.starter.rosbridge.config;

import com.example.starter.rosbridge.gateway.GatewayService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后启动网关周期维护线程（Service 超时扫描、上游宽限释放、限速发送）。
 */
@Component
public class MaintenanceStarter {

    private final GatewayService gateway;

    public MaintenanceStarter(GatewayService gateway) {
        this.gateway = gateway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        gateway.startMaintenance();
    }
}
