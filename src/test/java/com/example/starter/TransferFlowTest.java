package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.ledger.AbstractLedgerIntegrationTest;
import com.example.starter.ledger.LedgerFixtures;
import com.example.starter.ledger.TestClockConfig;
import com.example.starter.ledger.domain.BatteryStatus;
import com.example.starter.ledger.domain.TransferStatus;
import com.example.starter.ledger.repo.TransferRepository;
import com.example.starter.ledger.service.ConflictException;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.service.NotFoundException;
import com.example.starter.ledger.web.SwapOutRequest;
import com.example.starter.ledger.web.TransferDispatchRequest;
import com.example.starter.ledger.web.TransferReceiveRequest;
import com.example.starter.ledger.web.TransferResponse;
import com.example.starter.ledger.web.TransferWriteOffRequest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 调拨在途约束：调出方发起、调入方签收；在途禁止换出与再次调拨；超 24 小时转异常；核销仅一次。
 */
class TransferFlowTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private TransferRepository transferRepository;

    private TransferDispatchRequest dispatch(String requestId, String code, String from, String to) {
        TransferDispatchRequest request = new TransferDispatchRequest();
        request.setRequestId(requestId);
        request.setBatteryCode(code);
        request.setFromStation(from);
        request.setToStation(to);
        return request;
    }

    private SwapOutRequest swap(String requestId, String code, String station, String vehicle) {
        SwapOutRequest request = new SwapOutRequest();
        request.setRequestId(requestId);
        request.setBatteryCode(code);
        request.setStationCode(station);
        request.setVehicleCode(vehicle);
        return request;
    }

    @Test
    void dispatchReceiveHappyPath() {
        String code = "BAT-TRANSFER-OK";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);

        var dispatchResult = ledgerService.dispatchTransfer(
                dispatch("d-1", code, LedgerFixtures.STATION_A, LedgerFixtures.STATION_B));
        assertThat(dispatchResult.isReplayed()).isFalse();
        TransferResponse order = dispatchResult.getData();
        assertThat(order.getStatus()).isEqualTo(TransferStatus.DISPATCHED.name());
        assertThat(ledgerService.getBattery(code).getStatus()).isEqualTo(BatteryStatus.IN_TRANSIT.name());

        TransferReceiveRequest receive = new TransferReceiveRequest();
        receive.setRequestId("r-1");
        receive.setOrderNo(order.getOrderNo());
        receive.setToStation(LedgerFixtures.STATION_B);
        var received = ledgerService.receiveTransfer(receive);
        assertThat(received.getData().getStatus()).isEqualTo(TransferStatus.RECEIVED.name());
        assertThat(received.getData().getReceivedAt()).isNotNull();
        assertThat(ledgerService.getBattery(code).getStatus()).isEqualTo(BatteryStatus.AT_STATION.name());
        assertThat(ledgerService.getBattery(code).getCurrentStation()).isEqualTo(LedgerFixtures.STATION_B);
    }

    @Test
    void inTransitForbidsSwapOutAndSecondDispatch() {
        String code = "BAT-IN-TRANSIT";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);
        var order = ledgerService.dispatchTransfer(
                dispatch("d-2", code, LedgerFixtures.STATION_A, LedgerFixtures.STATION_B)).getData();

        assertThatThrownBy(() -> ledgerService.swapOut(swap("s-blocked", code,
                LedgerFixtures.STATION_A, "VEH-9")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不可换出");

        assertThatThrownBy(() -> ledgerService.dispatchTransfer(
                dispatch("d-3", code, LedgerFixtures.STATION_A, "ST-C")))
                .isInstanceOf(ConflictException.class);

        // 调入方不匹配也不能签收。
        TransferReceiveRequest wrongStation = new TransferReceiveRequest();
        wrongStation.setRequestId("r-wrong");
        wrongStation.setOrderNo(order.getOrderNo());
        wrongStation.setToStation("ST-C");
        assertThatThrownBy(() -> ledgerService.receiveTransfer(wrongStation))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void receivedOrderCannotReceiveAgain() {
        String code = "BAT-DOUBLE-RECEIVE";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);
        var order = ledgerService.dispatchTransfer(
                dispatch("d-4", code, LedgerFixtures.STATION_A, LedgerFixtures.STATION_B)).getData();

        TransferReceiveRequest receive = new TransferReceiveRequest();
        receive.setRequestId("r-4");
        receive.setOrderNo(order.getOrderNo());
        receive.setToStation(LedgerFixtures.STATION_B);
        ledgerService.receiveTransfer(receive);

        TransferReceiveRequest again = new TransferReceiveRequest();
        again.setRequestId("r-4-again-different");
        again.setOrderNo(order.getOrderNo());
        again.setToStation(LedgerFixtures.STATION_B);
        assertThatThrownBy(() -> ledgerService.receiveTransfer(again))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不允许签收");
    }

    @Test
    void transferOver24HoursBecomesExceptionAndExactly24HoursStaysDispatched() {
        String code = "BAT-TIMEOUT";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);
        Instant dispatchTime = Instant.parse("2026-09-25T00:00:00Z");
        TestClockConfig.CLOCK.setInstant(dispatchTime);
        var order = ledgerService.dispatchTransfer(
                dispatch("d-5", code, LedgerFixtures.STATION_A, LedgerFixtures.STATION_B)).getData();

        // 恰好 24 小时：仍在途，不转异常。
        TestClockConfig.CLOCK.setInstant(dispatchTime.plus(Duration.ofHours(24)));
        assertThat(ledgerService.markTimedOutTransfers()).isZero();
        assertThat(transferRepository.findByOrderNo(order.getOrderNo()).getStatus())
                .isEqualTo(TransferStatus.DISPATCHED.name());

        // 超过 24 小时（24h+1s）：自动转异常。
        TestClockConfig.CLOCK.setInstant(dispatchTime.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(ledgerService.markTimedOutTransfers()).isEqualTo(1);
        assertThat(transferRepository.findByOrderNo(order.getOrderNo()).getStatus())
                .isEqualTo(TransferStatus.EXCEPTION.name());
        assertThat(transferRepository.findByOrderNo(order.getOrderNo()).getExceptionAt()).isNotNull();
        assertThat(ledgerService.getBattery(code).getStatus())
                .isEqualTo(BatteryStatus.TRANSFER_EXCEPTION.name());

        // 幂等：再次扫描不重复计数。
        TestClockConfig.CLOCK.advance(Duration.ofHours(10));
        assertThat(ledgerService.markTimedOutTransfers()).isZero();
    }

    @Test
    void timedOutTransferCannotBeReceivedButCanBeWrittenOffOnce() {
        String code = "BAT-WRITEOFF";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);
        Instant dispatchTime = Instant.parse("2026-09-25T00:00:00Z");
        TestClockConfig.CLOCK.setInstant(dispatchTime);
        var order = ledgerService.dispatchTransfer(
                dispatch("d-6", code, LedgerFixtures.STATION_A, LedgerFixtures.STATION_B)).getData();
        TestClockConfig.CLOCK.setInstant(dispatchTime.plus(Duration.ofHours(25)));
        ledgerService.markTimedOutTransfers();

        // 异常单不能再签收。
        TransferReceiveRequest receive = new TransferReceiveRequest();
        receive.setRequestId("r-6");
        receive.setOrderNo(order.getOrderNo());
        receive.setToStation(LedgerFixtures.STATION_B);
        assertThatThrownBy(() -> ledgerService.receiveTransfer(receive))
                .isInstanceOf(ConflictException.class);

        TransferWriteOffRequest writeOff = new TransferWriteOffRequest();
        writeOff.setRequestId("w-6");
        writeOff.setOrderNo(order.getOrderNo());
        writeOff.setReason("运输车辆故障，人工核销");
        var result = ledgerService.writeOffTransfer(writeOff);
        assertThat(result.getData().getStatus()).isEqualTo(TransferStatus.WRITTEN_OFF.name());
        assertThat(ledgerService.getBattery(code).getStatus()).isEqualTo(BatteryStatus.WRITTEN_OFF.name());

        // 核销仅一次：不同 requestId 再来一次返回 409。
        TransferWriteOffRequest again = new TransferWriteOffRequest();
        again.setRequestId("w-6-second");
        again.setOrderNo(order.getOrderNo());
        again.setReason("再次核销");
        assertThatThrownBy(() -> ledgerService.writeOffTransfer(again))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("仅调拨异常单可核销");
    }

    @Test
    void normalDispatchedTransferCannotBeWrittenOff() {
        String code = "BAT-WRITEOFF-NORMAL";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);
        var order = ledgerService.dispatchTransfer(
                dispatch("d-7", code, LedgerFixtures.STATION_A, LedgerFixtures.STATION_B)).getData();

        TransferWriteOffRequest writeOff = new TransferWriteOffRequest();
        writeOff.setRequestId("w-7");
        writeOff.setOrderNo(order.getOrderNo());
        writeOff.setReason("尚未超时不应核销");
        assertThatThrownBy(() -> ledgerService.writeOffTransfer(writeOff))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void receiveUnknownOrderIs404() {
        TransferReceiveRequest receive = new TransferReceiveRequest();
        receive.setRequestId("r-missing");
        receive.setOrderNo("TO-NOT-EXIST");
        receive.setToStation(LedgerFixtures.STATION_B);
        assertThatThrownBy(() -> ledgerService.receiveTransfer(receive))
                .isInstanceOf(NotFoundException.class);
    }
}
