package com.example.starter.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定时维护服务：统一驱动回执超时升级与传感器失联判定，供调度或手动触发。
 */
@Service
public class MaintenanceService {

    private final DisposalService disposalService;
    private final SensorService sensorService;

    public MaintenanceService(DisposalService disposalService, SensorService sensorService) {
        this.disposalService = disposalService;
        this.sensorService = sensorService;
    }

    /**
     * 维护节拍结果。
     *
     * @param escalatedCommands 本次升级的回执超时指令数
     * @param missingSensors    本次新标记失联的传感器数
     */
    public record TickResult(int escalatedCommands, int missingSensors) {
    }

    /**
     * 执行一次维护节拍：先处理回执超时升级，再判定传感器失联。
     */
    @Transactional
    public TickResult tick(LocalDateTime now) {
        int escalated = disposalService.processTimeouts(now);
        int missing = sensorService.checkLoss(now);
        return new TickResult(escalated, missing);
    }
}
