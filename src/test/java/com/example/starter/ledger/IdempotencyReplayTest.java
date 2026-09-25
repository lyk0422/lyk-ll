package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.ledger.domain.BatteryStatus;
import com.example.starter.ledger.po.LedgerEventPO;
import com.example.starter.ledger.repo.EventRepository;
import com.example.starter.ledger.service.ConflictException;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.web.BatteryResponse;
import com.example.starter.ledger.web.InboundRequest;
import com.example.starter.ledger.web.InspectionRequest;
import com.example.starter.ledger.web.RegisterBatteryRequest;
import com.example.starter.ledger.web.SwapOutRequest;
import com.example.starter.ledger.web.TransferDispatchRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * requestId 幂等：同键同参返回首次结果且不产生新事件；同键异参 409；跨操作复用 requestId 亦 409。
 */
class IdempotencyReplayTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void duplicateRegistrationConflicts() {
        RegisterBatteryRequest request = new RegisterBatteryRequest();
        request.setCode("BAT-DUP-REGISTER");
        ledgerService.registerBattery(request);

        RegisterBatteryRequest again = new RegisterBatteryRequest();
        again.setCode("BAT-DUP-REGISTER");
        assertThatThrownBy(() -> ledgerService.registerBattery(again))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void inboundSameKeySameParamsReplaysWithoutNewEvent() {
        LedgerFixtures.register(ledgerService, "BAT-REPLAY-IN");
        InboundRequest inbound = new InboundRequest();
        inbound.setRequestId("req-inbound");
        inbound.setBatteryCode("BAT-REPLAY-IN");
        inbound.setStationCode(LedgerFixtures.STATION_A);

        var first = ledgerService.inbound(inbound);
        assertThat(first.isReplayed()).isFalse();
        List<LedgerEventPO> eventsAfterFirst = eventRepository.findByBattery("BAT-REPLAY-IN");

        var second = ledgerService.inbound(inbound);
        assertThat(second.isReplayed()).isTrue();
        assertThat(second.getData().getStatus()).isEqualTo(first.getData().getStatus());
        assertThat(second.getData().getCurrentStation()).isEqualTo(LedgerFixtures.STATION_A);
        assertThat(eventRepository.findByBattery("BAT-REPLAY-IN")).hasSize(eventsAfterFirst.size());
    }

    @Test
    void inboundSameKeyDifferentParamsConflictsWithoutNewEvent() {
        LedgerFixtures.register(ledgerService, "BAT-REPLAY-CONFLICT");
        InboundRequest inbound = new InboundRequest();
        inbound.setRequestId("req-inbound-conflict");
        inbound.setBatteryCode("BAT-REPLAY-CONFLICT");
        inbound.setStationCode(LedgerFixtures.STATION_A);
        ledgerService.inbound(inbound);
        long eventCount = eventRepository.findByBattery("BAT-REPLAY-CONFLICT").size();

        InboundRequest changed = new InboundRequest();
        changed.setRequestId("req-inbound-conflict");
        changed.setBatteryCode("BAT-REPLAY-CONFLICT");
        changed.setStationCode(LedgerFixtures.STATION_B);
        assertThatThrownBy(() -> ledgerService.inbound(changed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
        assertThat(eventRepository.findByBattery("BAT-REPLAY-CONFLICT")).hasSize((int) eventCount);
        assertThat(ledgerService.getBattery("BAT-REPLAY-CONFLICT").getCurrentStation())
                .isEqualTo(LedgerFixtures.STATION_A);
    }

    @Test
    void sameRequestIdAcrossOperationsConflicts() {
        LedgerFixtures.readyAvailableAtA(ledgerService, "BAT-CROSS-OP");

        SwapOutRequest swap = new SwapOutRequest();
        swap.setRequestId("shared-key");
        swap.setBatteryCode("BAT-CROSS-OP");
        swap.setStationCode(LedgerFixtures.STATION_A);
        swap.setVehicleCode("VEH-CROSS");
        ledgerService.swapOut(swap);

        // 同一 requestId 用于另一操作（入站）：指纹含操作类型，返回 409。
        LedgerFixtures.register(ledgerService, "BAT-CROSS-OP-2");
        InboundRequest inbound = new InboundRequest();
        inbound.setRequestId("shared-key");
        inbound.setBatteryCode("BAT-CROSS-OP-2");
        inbound.setStationCode(LedgerFixtures.STATION_A);
        assertThatThrownBy(() -> ledgerService.inbound(inbound))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void inspectionReplayReturnsFirstConclusion() {
        LedgerFixtures.register(ledgerService, "BAT-INSP-REPLAY");
        LedgerFixtures.inbound(ledgerService, "in-r", "BAT-INSP-REPLAY", LedgerFixtures.STATION_A);

        InspectionRequest failRequest = new InspectionRequest();
        failRequest.setRequestId("insp-replay");
        failRequest.setBatteryCode("BAT-INSP-REPLAY");
        failRequest.setSoc(10);
        failRequest.setHealth(10);
        var first = ledgerService.inspect(failRequest);
        assertThat(first.getData().getStatus()).isEqualTo(BatteryStatus.CHARGING.name());

        // 同键同参重放，仍返回首次的“充电/待修”结论，不产生新事件。
        var replay = ledgerService.inspect(failRequest);
        assertThat(replay.isReplayed()).isTrue();
        assertThat(replay.getData().getStatus()).isEqualTo(BatteryStatus.CHARGING.name());
    }

    @Test
    void swapOutAndDispatchReplayReturnFirstResult() {
        LedgerFixtures.readyAvailableAtA(ledgerService, "BAT-SWAP-REPLAY");
        SwapOutRequest swap = new SwapOutRequest();
        swap.setRequestId("swap-replay");
        swap.setBatteryCode("BAT-SWAP-REPLAY");
        swap.setStationCode(LedgerFixtures.STATION_A);
        swap.setVehicleCode("VEH-R");
        BatteryResponse first = ledgerService.swapOut(swap).getData();
        BatteryResponse replay = ledgerService.swapOut(swap).getData();
        assertThat(replay.getBoundVehicle()).isEqualTo(first.getBoundVehicle());
        assertThat(eventRepository.findByBattery("BAT-SWAP-REPLAY"))
                .filteredOn(event -> "SWAP_OUT".equals(event.getEventType()))
                .hasSize(1);

        // 回站上架后再调拨，同键重放返回同一调拨单号。
        LedgerFixtures.inbound(ledgerService, "in-sr", "BAT-SWAP-REPLAY", LedgerFixtures.STATION_A);
        LedgerFixtures.inspectPass(ledgerService, "insp-sr", "BAT-SWAP-REPLAY");
        TransferDispatchRequest dispatch = new TransferDispatchRequest();
        dispatch.setRequestId("dispatch-replay");
        dispatch.setBatteryCode("BAT-SWAP-REPLAY");
        dispatch.setFromStation(LedgerFixtures.STATION_A);
        dispatch.setToStation(LedgerFixtures.STATION_B);
        String firstOrderNo = ledgerService.dispatchTransfer(dispatch).getData().getOrderNo();
        String replayOrderNo = ledgerService.dispatchTransfer(dispatch).getData().getOrderNo();
        assertThat(replayOrderNo).isEqualTo(firstOrderNo);
    }
}
