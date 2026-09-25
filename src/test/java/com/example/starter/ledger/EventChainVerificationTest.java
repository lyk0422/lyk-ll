package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ledger.po.LedgerEventPO;
import com.example.starter.ledger.repo.EventRepository;
import com.example.starter.ledger.service.HashChainUtil;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.service.PayloadCodec;
import com.example.starter.ledger.web.SwapOutRequest;
import com.example.starter.ledger.web.TransferDispatchRequest;
import com.example.starter.ledger.web.TransferReceiveRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 事件哈希链：完整流转链可逐条验真；篡改载荷、哈希或前置链接均可检测。
 */
class EventChainVerificationTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private EventRepository eventRepository;

    private void buildFullChain(String code) {
        LedgerFixtures.readyAvailableAtA(ledgerService, code);

        SwapOutRequest swap = new SwapOutRequest();
        swap.setRequestId("swap-" + code);
        swap.setBatteryCode(code);
        swap.setStationCode(LedgerFixtures.STATION_A);
        swap.setVehicleCode("VEH-1");
        ledgerService.swapOut(swap);

        // 回站再次上架，随后调拨签收形成完整链。
        LedgerFixtures.inbound(ledgerService, "in2-" + code, code, LedgerFixtures.STATION_A);
        LedgerFixtures.inspectPass(ledgerService, "insp2-" + code, code);

        TransferDispatchRequest dispatch = new TransferDispatchRequest();
        dispatch.setRequestId("dispatch-" + code);
        dispatch.setBatteryCode(code);
        dispatch.setFromStation(LedgerFixtures.STATION_A);
        dispatch.setToStation(LedgerFixtures.STATION_B);
        var transfer = ledgerService.dispatchTransfer(dispatch);

        TransferReceiveRequest receive = new TransferReceiveRequest();
        receive.setRequestId("receive-" + code);
        receive.setOrderNo(transfer.getData().getOrderNo());
        receive.setToStation(LedgerFixtures.STATION_B);
        ledgerService.receiveTransfer(receive);
    }

    @Test
    void fullChainVerifiesAndHeadMatchesBattery() {
        String code = "BAT-CHAIN-OK";
        buildFullChain(code);

        LedgerService.ChainVerificationView view = ledgerService.verifyChain(code);
        assertThat(view.valid()).isTrue();
        assertThat(view.failedSeq()).isEqualTo(-1);

        List<LedgerEventPO> events = eventRepository.findByBattery(code);
        assertThat(events).hasSizeGreaterThanOrEqualTo(8);
        assertThat(events.get(0).getPrevHash()).isEqualTo(HashChainUtil.GENESIS_PREV_HASH);
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getPrevHash()).isEqualTo(events.get(i - 1).getEventHash());
            assertThat(events.get(i).getSeq()).isEqualTo(events.get(i - 1).getSeq() + 1);
        }
        assertThat(view.headHash()).isEqualTo(events.get(events.size() - 1).getEventHash());
        assertThat(ledgerService.getBattery(code).getHeadEventHash()).isEqualTo(view.headHash());
    }

    @Test
    void tamperedPayloadIsDetected() {
        String code = "BAT-CHAIN-TAMPER-PAYLOAD";
        buildFullChain(code);

        List<LedgerEventPO> events = eventRepository.findByBattery(code);
        LedgerEventPO middle = events.get(2);
        Map<String, Object> payload = PayloadCodec.decode(middle.getPayload());
        payload.put("tampered", true);
        eventRepository.tamperPayloadForTest(middle.getEventHash(), PayloadCodec.encode(payload));

        LedgerService.ChainVerificationView view = ledgerService.verifyChain(code);
        assertThat(view.valid()).isFalse();
        assertThat(view.failedSeq()).isEqualTo(middle.getSeq());
        assertThat(view.reason()).contains("哈希不一致");
    }

    @Test
    void tamperedStoredHashIsDetected() {
        String code = "BAT-CHAIN-TAMPER-HASH";
        buildFullChain(code);

        List<LedgerEventPO> events = eventRepository.findByBattery(code);
        LedgerEventPO first = events.get(0);
        String originalHash = first.getEventHash();
        // 直接改库中存储的哈希值模拟篡改。
        jdbcTemplate.update("UPDATE ledger_event SET event_hash = ? WHERE battery_code = ? AND seq = ?",
                "a".repeat(64), code, first.getSeq());

        LedgerService.ChainVerificationView view = ledgerService.verifyChain(code);
        assertThat(view.valid()).isFalse();
        // 首事件自身复算不一致；同时后续事件 prevHash 链接也断裂。
        assertThat(view.failedSeq()).isEqualTo(first.getSeq());

        jdbcTemplate.update("UPDATE ledger_event SET event_hash = ? WHERE battery_code = ? AND seq = ?",
                originalHash, code, first.getSeq());
        assertThat(ledgerService.verifyChain(code).valid()).isTrue();
    }

    @Test
    void brokenLinkIsDetected() {
        String code = "BAT-CHAIN-BROKEN-LINK";
        buildFullChain(code);

        List<LedgerEventPO> events = eventRepository.findByBattery(code);
        LedgerEventPO second = events.get(1);
        jdbcTemplate.update("UPDATE ledger_event SET prev_hash = ? WHERE battery_code = ? AND seq = ?",
                "b".repeat(64), code, second.getSeq());

        LedgerService.ChainVerificationView view = ledgerService.verifyChain(code);
        assertThat(view.valid()).isFalse();
        assertThat(view.failedSeq()).isEqualTo(second.getSeq());
        assertThat(view.reason()).contains("链接断裂");
    }
}
