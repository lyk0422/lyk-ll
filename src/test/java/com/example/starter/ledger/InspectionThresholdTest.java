package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.ledger.domain.BatteryStatus;
import com.example.starter.ledger.domain.EventType;
import com.example.starter.ledger.repo.EventRepository;
import com.example.starter.ledger.service.ConflictException;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.web.BatteryResponse;
import com.example.starter.ledger.web.InboundRequest;
import com.example.starter.ledger.web.InspectionRequest;
import com.example.starter.ledger.web.OperationResult;
import com.example.starter.ledger.web.RegisterBatteryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 检测双阈值门槛边界：SOC 90、健康度 80 恰好达标合格；任一不达标进充电/待修；复检走新批次。
 */
class InspectionThresholdTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private EventRepository eventRepository;

    private String prepareAtStation(String code) {
        RegisterBatteryRequest register = new RegisterBatteryRequest();
        register.setCode(code);
        ledgerService.registerBattery(register);
        InboundRequest inbound = new InboundRequest();
        inbound.setRequestId("in-" + code);
        inbound.setBatteryCode(code);
        inbound.setStationCode("ST-A");
        ledgerService.inbound(inbound);
        return code;
    }

    private InspectionRequest inspection(String requestId, String code, int soc, int health) {
        InspectionRequest request = new InspectionRequest();
        request.setRequestId(requestId);
        request.setBatteryCode(code);
        request.setSoc(soc);
        request.setHealth(health);
        return request;
    }

    @Test
    void exactThresholdsPassAndListed() {
        prepareAtStation("BAT-EXACT-90-80");
        OperationResult<BatteryResponse> result =
                ledgerService.inspect(inspection("req-exact", "BAT-EXACT-90-80", 90, 80));

        assertThat(result.isReplayed()).isFalse();
        assertThat(result.getData().getStatus()).isEqualTo(BatteryStatus.AVAILABLE.name());
        assertThat(result.getData().getCurrentStation()).isEqualTo("ST-A");
        assertThat(eventRepository.findByBattery("BAT-EXACT-90-80"))
                .extracting(event -> EventType.valueOf(event.getEventType()))
                .contains(EventType.REGISTER, EventType.INBOUND, EventType.INSPECTION, EventType.LISTING);
    }

    @Test
    void aboveThresholdsPass() {
        prepareAtStation("BAT-ABOVE");
        OperationResult<BatteryResponse> result =
                ledgerService.inspect(inspection("req-above", "BAT-ABOVE", 95, 88));
        assertThat(result.getData().getStatus()).isEqualTo(BatteryStatus.AVAILABLE.name());
    }

    @Test
    void socOneBelowThresholdGoesCharging() {
        prepareAtStation("BAT-SOC-89");
        OperationResult<BatteryResponse> result =
                ledgerService.inspect(inspection("req-soc89", "BAT-SOC-89", 89, 80));
        assertThat(result.getData().getStatus()).isEqualTo(BatteryStatus.CHARGING.name());
        assertThat(eventRepository.findByBattery("BAT-SOC-89"))
                .extracting(event -> EventType.valueOf(event.getEventType()))
                .doesNotContain(EventType.LISTING);
    }

    @Test
    void healthOneBelowThresholdGoesCharging() {
        prepareAtStation("BAT-H-79");
        OperationResult<BatteryResponse> result =
                ledgerService.inspect(inspection("req-h79", "BAT-H-79", 90, 79));
        assertThat(result.getData().getStatus()).isEqualTo(BatteryStatus.CHARGING.name());
    }

    @Test
    void bothBelowThresholdGoesCharging() {
        prepareAtStation("BAT-BOTH-LOW");
        OperationResult<BatteryResponse> result =
                ledgerService.inspect(inspection("req-low", "BAT-BOTH-LOW", 0, 0));
        assertThat(result.getData().getStatus()).isEqualTo(BatteryStatus.CHARGING.name());
    }

    @Test
    void chargingBatteryCanBeReinspectedInNewBatchAndPass() {
        prepareAtStation("BAT-REINSPECT");
        ledgerService.inspect(inspection("batch-1", "BAT-REINSPECT", 50, 50));
        assertThat(ledgerService.getBattery("BAT-REINSPECT").getStatus())
                .isEqualTo(BatteryStatus.CHARGING.name());

        // 复检走新检测批次（新 requestId），合格后上架。
        OperationResult<BatteryResponse> second =
                ledgerService.inspect(inspection("batch-2", "BAT-REINSPECT", 92, 81));
        assertThat(second.getData().getStatus()).isEqualTo(BatteryStatus.AVAILABLE.name());
    }

    @Test
    void sameBatchDifferentParametersIsConflictWithoutNewEvent() {
        prepareAtStation("BAT-IMMUTABLE");
        ledgerService.inspect(inspection("batch-same", "BAT-IMMUTABLE", 90, 80));
        long eventsBefore = eventRepository.findByBattery("BAT-IMMUTABLE").size();

        // 同键异参：409，且不产生新事件。
        assertThatThrownBy(() -> ledgerService.inspect(inspection("batch-same", "BAT-IMMUTABLE", 91, 80)))
                .isInstanceOf(ConflictException.class);
        assertThat(eventRepository.findByBattery("BAT-IMMUTABLE")).hasSize((int) eventsBefore);
        assertThat(ledgerService.getBattery("BAT-IMMUTABLE").getStatus())
                .isEqualTo(BatteryStatus.AVAILABLE.name());
    }
}
