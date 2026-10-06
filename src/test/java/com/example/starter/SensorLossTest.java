package com.example.starter;

import com.example.starter.domain.SensorState;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.DisposalRepository;
import com.example.starter.service.MaintenanceService;
import com.example.starter.service.MonitoringService;
import com.example.starter.service.SensorService;
import com.example.starter.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传感器失联：恰满 10 分钟标记数据缺失并挂起自动处置，复连须人工确认补算，保留原告警链。
 */
class SensorLossTest extends BaseIntegrationTest {

    @Autowired
    private SensorService sensorService;
    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private MonitoringService monitoringService;
    @Autowired
    private AlarmRepository alarmRepository;
    @Autowired
    private DisposalRepository disposalRepository;

    @Test
    void 恰满10分钟未心跳标记数据缺失() {
        // 基准心跳为 T0；9 分 59 秒时不标记
        var early = maintenanceService.tick(T0.plusMinutes(9).plusSeconds(59));
        assertEquals(0, early.missingSensors());
        assertEquals("ONLINE", sensorState("ZONE-A"));

        // 恰满 10 分钟标记（三个展示池同时失联）
        var tick = maintenanceService.tick(T0.plusMinutes(10));
        assertEquals(3, tick.missingSensors());
        assertEquals("MISSING", sensorState("ZONE-A"));
        // 再次节拍不重复标记
        var again = maintenanceService.tick(T0.plusMinutes(11));
        assertEquals(0, again.missingSensors());
    }

    @Test
    void 失联期间采样挂起评估与自动处置() {
        maintenanceService.tick(T0.plusMinutes(10));
        assertEquals("MISSING", sensorState("ZONE-A"));

        var result = monitoringService.ingest("ZONE-A", "req-miss-1", new BigDecimal("4.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(11));
        assertFalse(result.evaluated());
        assertEquals(0, alarmRepository.findByZone(zoneId("ZONE-A")).size());
        assertTrue(disposalRepository.findByZone(zoneId("ZONE-A")).isEmpty());
    }

    @Test
    void 复连须人工确认补算且保留原告警链() {
        long zoneId = zoneId("ZONE-A");
        // 失联前已有 1 条告警链
        monitoringService.ingest("ZONE-A", "req-pre-1", new BigDecimal("4.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(1));
        assertEquals(1, alarmRepository.findByZone(zoneId).size());

        // 失联，期间补录 3 条越限采样
        maintenanceService.tick(T0.plusMinutes(10));
        for (int i = 0; i < 3; i++) {
            monitoringService.ingest("ZONE-A", "req-buf-" + i, new BigDecimal("4.0"),
                    new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(11 + i));
        }
        assertEquals(1, alarmRepository.findByZone(zoneId).size(), "失联期间不评估");

        // 复连：心跳转为待确认，未确认前不允许直接视为在线
        assertEquals(SensorState.PENDING_CONFIRM,
                sensorService.heartbeat("ZONE-A", T0.plusMinutes(15)));
        assertEquals("PENDING_CONFIRM", sensorState("ZONE-A"));

        // 人工确认补算
        var result = sensorService.confirmReconnect("ZONE-A", T0.plusMinutes(16));
        assertEquals(3, result.backfilledSamples());
        assertEquals("ONLINE", sensorState("ZONE-A"));

        // 原告警链保留，补算告警追加；失联前已越限 1 点，补算第 2 条即累计恰 3 点升级
        var alarms = alarmRepository.findByZone(zoneId);
        assertEquals(4, alarms.size());
        assertEquals("OPEN", alarms.get(0).status().name());
        assertEquals("ESCALATED", alarms.get(2).status().name());
        assertEquals(1, disposalRepository.findByZone(zoneId).size(), "补算后应补发处置指令");
        assertEquals("DISPOSING", zoneStatus("ZONE-A"));
    }

    @Test
    void 在线状态下不允许人工确认复连() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> sensorService.confirmReconnect("ZONE-A", T0.plusMinutes(1)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
    }
}
