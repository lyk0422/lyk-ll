package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.ledger.domain.BatteryStatus;
import com.example.starter.ledger.po.ChainAlertPO;
import com.example.starter.ledger.repo.ChainAlertRepository;
import com.example.starter.ledger.repo.EventRepository;
import com.example.starter.ledger.service.ChainMismatchException;
import com.example.starter.ledger.service.ConflictException;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.service.PayloadCodec;
import com.example.starter.ledger.web.InboundRequest;
import com.example.starter.ledger.web.SwapOutRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 回站入站链一致校验：历史链一致方可入站；链不一致拒绝（422）并告警，状态与事件链不变。
 */
class InboundChainConsistencyTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ChainAlertRepository chainAlertRepository;

    private InboundRequest inbound(String requestId, String code, String station) {
        InboundRequest request = new InboundRequest();
        request.setRequestId(requestId);
        request.setBatteryCode(code);
        request.setStationCode(station);
        return request;
    }

    @Test
    void registeredBatteryCanInboundFirstTime() {
        LedgerFixtures.register(ledgerService, "BAT-FIRST-IN");
        var result = ledgerService.inbound(inbound("in-first", "BAT-FIRST-IN", LedgerFixtures.STATION_A));
        assertThat(result.getData().getStatus()).isEqualTo(BatteryStatus.AT_STATION.name());
        assertThat(result.getData().getCurrentStation()).isEqualTo(LedgerFixtures.STATION_A);
    }

    @Test
    void inUseBatteryWithIntactChainCanReturnToStation() {
        LedgerFixtures.readyAvailableAtA(ledgerService, "BAT-RETURN");
        SwapOutRequest swap = new SwapOutRequest();
        swap.setRequestId("swap-return");
        swap.setBatteryCode("BAT-RETURN");
        swap.setStationCode(LedgerFixtures.STATION_A);
        swap.setVehicleCode("VEH-RET");
        ledgerService.swapOut(swap);
        assertThat(ledgerService.getBattery("BAT-RETURN").getStatus()).isEqualTo(BatteryStatus.IN_USE.name());

        var result = ledgerService.inbound(inbound("in-return", "BAT-RETURN", LedgerFixtures.STATION_B));
        assertThat(result.getData().getStatus()).isEqualTo(BatteryStatus.AT_STATION.name());
        assertThat(result.getData().getCurrentStation()).isEqualTo(LedgerFixtures.STATION_B);
        assertThat(result.getData().getBoundVehicle()).isNull();
    }

    @Test
    void tamperedChainInboundRejectedAndAlerted() {
        LedgerFixtures.readyAvailableAtA(ledgerService, "BAT-TAMPERED");
        SwapOutRequest swap = new SwapOutRequest();
        swap.setRequestId("swap-tampered");
        swap.setBatteryCode("BAT-TAMPERED");
        swap.setStationCode(LedgerFixtures.STATION_A);
        swap.setVehicleCode("VEH-T");
        ledgerService.swapOut(swap);

        // 模拟链被篡改：改写一条历史事件的载荷。
        var events = eventRepository.findByBattery("BAT-TAMPERED");
        var target = events.get(1);
        Map<String, Object> payload = PayloadCodec.decode(target.getPayload());
        payload.put("stationCode", "ST-FORGE");
        eventRepository.tamperPayloadForTest(target.getEventHash(), PayloadCodec.encode(payload));

        assertThatThrownBy(() -> ledgerService.inbound(
                inbound("in-tampered", "BAT-TAMPERED", LedgerFixtures.STATION_A)))
                .isInstanceOf(ChainMismatchException.class)
                .hasMessageContaining("历史链不一致");

        // 电池仍为“在用”，未产生入站事件。
        assertThat(ledgerService.getBattery("BAT-TAMPERED").getStatus()).isEqualTo(BatteryStatus.IN_USE.name());
        assertThat(eventRepository.findByBattery("BAT-TAMPERED"))
                .filteredOn(event -> "INBOUND".equals(event.getEventType()))
                .hasSize(1); // 仅首次入站那一条

        // 告警已独立落库（即使业务事务回滚）。
        List<ChainAlertPO> alerts = chainAlertRepository.findByBattery("BAT-TAMPERED");
        assertThat(alerts).hasSize(1);
        ChainAlertPO alert = alerts.get(0);
        assertThat(alert.getStationCode()).isEqualTo(LedgerFixtures.STATION_A);
        assertThat(alert.getRequestId()).isEqualTo("in-tampered");
        assertThat(alert.getReason()).contains("链校验失败");
    }

    @Test
    void atStationBatteryCannotInboundAgain() {
        LedgerFixtures.register(ledgerService, "BAT-DOUBLE-IN");
        ledgerService.inbound(inbound("in-double", "BAT-DOUBLE-IN", LedgerFixtures.STATION_A));

        assertThatThrownBy(() -> ledgerService.inbound(
                inbound("in-double-2", "BAT-DOUBLE-IN", LedgerFixtures.STATION_A)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不允许入站");
    }
}
