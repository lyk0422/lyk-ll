package com.example.starter.pump.service;

import com.example.starter.pump.domain.AlertLevel;
import com.example.starter.pump.domain.PumpStation;
import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.web.dto.WarningRequest;
import com.example.starter.pump.web.dto.WarningResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 气象预警决策：橙色及以上预警启动预降。
 */
@Service
public class WarningService {

    private final CatalogRepository catalogRepository;
    private final PumpdownEventRepository eventRepository;

    public WarningService(CatalogRepository catalogRepository,
                          PumpdownEventRepository eventRepository) {
        this.catalogRepository = catalogRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 处理预警（首次受理）。
     * <ul>
     *   <li>蓝/黄：不触发预降，返回 NOT_TRIGGERED；</li>
     *   <li>橙/红：分区无活动事件时创建 ACTIVE 预降事件；分区已有活动事件返回 409。</li>
     * </ul>
     */
    public WarningResult process(WarningRequest request) {
        AlertLevel level = catalogRepository.findAlertLevel(request.alertLevel())
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_ALERT_LEVEL")
                        .raise("未知预警级别: " + request.alertLevel()));
        if (!catalogRepository.zoneExists(request.zoneCode())) {
            throw ApiException.unprocessable("UNKNOWN_ZONE")
                    .raise("未知流域分区: " + request.zoneCode());
        }

        if (!level.triggerPumpdown()) {
            return new WarningResult(null, request.zoneCode(), level.code(), false,
                    "NOT_TRIGGERED", null, null, false);
        }

        if (eventRepository.findActiveByZone(request.zoneCode()).isPresent()) {
            throw ApiException.concurrentConflict("ZONE_PUMPDOWN_ACTIVE",
                    "分区 " + request.zoneCode() + " 已存在进行中的预降事件，请勿重复启动");
        }

        BigDecimal targetLevel = resolveZoneTargetLevel(request.zoneCode());
        PumpdownEvent event = eventRepository.create(request.requestId(), request.zoneCode(),
                level.code(), targetLevel, request.alertTime());
        return new WarningResult(event.id(), event.zoneCode(), event.alertLevel(), true,
                event.status(), event.startedAt(), event.targetLevel(), false);
    }

    /** 分区目标水位取区内泵站最严格（最低）目标位。 */
    private BigDecimal resolveZoneTargetLevel(String zoneCode) {
        List<PumpStation> stations = catalogRepository.stationsOfZone(zoneCode);
        return stations.stream()
                .map(PumpStation::targetLevel)
                .min(BigDecimal::compareTo)
                .orElseThrow(() -> ApiException.unprocessable("ZONE_WITHOUT_STATION")
                        .raise("分区下无泵站，无法预降: " + zoneCode));
    }
}
