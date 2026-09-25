package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ledger.domain.EventType;
import com.example.starter.ledger.domain.TransferStatus;
import com.example.starter.ledger.repo.EventRepository;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.web.TransferDispatchRequest;
import com.example.starter.ledger.web.TransferResponse;
import com.example.starter.ledger.web.TransferWriteOffRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 核销幂等：同一 requestId 同参重放返回首次结果且仅一条核销事件；异参 409。
 */
class WriteOffIdempotencyTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void writeOffSameKeyReplaysOnce() {
        String code = "BAT-WRITEOFF-IDEMPOTENT";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);
        Instant dispatchTime = Instant.parse("2026-09-25T00:00:00Z");
        TestClockConfig.CLOCK.setInstant(dispatchTime);

        TransferDispatchRequest dispatch = new TransferDispatchRequest();
        dispatch.setRequestId("wd-1");
        dispatch.setBatteryCode(code);
        dispatch.setFromStation(LedgerFixtures.STATION_A);
        dispatch.setToStation(LedgerFixtures.STATION_B);
        TransferResponse order = ledgerService.dispatchTransfer(dispatch).getData();

        TestClockConfig.CLOCK.setInstant(dispatchTime.plus(Duration.ofHours(25)));
        ledgerService.markTimedOutTransfers();

        TransferWriteOffRequest writeOff = new TransferWriteOffRequest();
        writeOff.setRequestId("wo-1");
        writeOff.setOrderNo(order.getOrderNo());
        writeOff.setReason("统一核销原因");

        var first = ledgerService.writeOffTransfer(writeOff);
        assertThat(first.isReplayed()).isFalse();
        long writeOffEvents = eventRepository.findByBattery(code).stream()
                .filter(event -> EventType.TRANSFER_WRITEOFF.name().equals(event.getEventType()))
                .count();
        assertThat(writeOffEvents).isEqualTo(1);

        // 同键同参重放：返回首次结果，标记 replayed，不新增事件。
        var replay = ledgerService.writeOffTransfer(writeOff);
        assertThat(replay.isReplayed()).isTrue();
        assertThat(replay.getData().getStatus()).isEqualTo(TransferStatus.WRITTEN_OFF.name());
        assertThat(eventRepository.findByBattery(code).stream()
                .filter(event -> EventType.TRANSFER_WRITEOFF.name().equals(event.getEventType()))
                .count()).isEqualTo(1);
    }

    @Test
    void writeOffSameKeyDifferentReasonConflicts() {
        String code = "BAT-WRITEOFF-CONFLICT";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);
        Instant dispatchTime = Instant.parse("2026-09-25T00:00:00Z");
        TestClockConfig.CLOCK.setInstant(dispatchTime);
        TransferDispatchRequest dispatch = new TransferDispatchRequest();
        dispatch.setRequestId("wd-2");
        dispatch.setBatteryCode(code);
        dispatch.setFromStation(LedgerFixtures.STATION_A);
        dispatch.setToStation(LedgerFixtures.STATION_B);
        TransferResponse order = ledgerService.dispatchTransfer(dispatch).getData();
        TestClockConfig.CLOCK.setInstant(dispatchTime.plus(Duration.ofHours(30)));
        ledgerService.markTimedOutTransfers();

        TransferWriteOffRequest first = new TransferWriteOffRequest();
        first.setRequestId("wo-2");
        first.setOrderNo(order.getOrderNo());
        first.setReason("原因A");
        ledgerService.writeOffTransfer(first);

        TransferWriteOffRequest changed = new TransferWriteOffRequest();
        changed.setRequestId("wo-2");
        changed.setOrderNo(order.getOrderNo());
        changed.setReason("原因B-不同");
        var eventsBefore = eventRepository.findByBattery(code).size();
        try {
            ledgerService.writeOffTransfer(changed);
            throw new AssertionError("同键异参应返回冲突");
        } catch (com.example.starter.ledger.service.ConflictException e) {
            // 期望 409
        }
        assertThat(eventRepository.findByBattery(code)).hasSize(eventsBefore);
    }

    @Test
    void hashFingerprintCoversRequestIdOperationAndFields() {
        Map<String, Object> fields = Map.of("batteryCode", "B1", "stationCode", "S1");
        String fp1 = com.example.starter.ledger.service.HashChainUtil.fingerprint("INBOUND", "r1", fields);
        // requestId 不同 → 指纹不同
        assertThat(fp1).isNotEqualTo(
                com.example.starter.ledger.service.HashChainUtil.fingerprint("INBOUND", "r2", fields));
        // 操作不同 → 指纹不同
        assertThat(fp1).isNotEqualTo(
                com.example.starter.ledger.service.HashChainUtil.fingerprint("SWAP_OUT", "r1", fields));
        // 业务字段不同 → 指纹不同
        assertThat(fp1).isNotEqualTo(com.example.starter.ledger.service.HashChainUtil.fingerprint(
                "INBOUND", "r1", Map.of("batteryCode", "B2", "stationCode", "S1")));
        // 相同输入 → 指纹稳定可复算
        assertThat(fp1).isEqualTo(
                com.example.starter.ledger.service.HashChainUtil.fingerprint("INBOUND", "r1",
                        Map.of("stationCode", "S1", "batteryCode", "B1")));
    }
}
