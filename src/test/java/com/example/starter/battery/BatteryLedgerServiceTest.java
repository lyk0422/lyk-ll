package com.example.starter.battery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.battery.web.ConflictException;
import com.example.starter.battery.web.NotFoundException;
import com.example.starter.battery.web.Requests.InboundRequest;
import com.example.starter.battery.web.Requests.InspectionRequest;
import com.example.starter.battery.web.Requests.RegisterBatteryRequest;
import com.example.starter.battery.web.Requests.SwapOutRequest;
import com.example.starter.battery.web.Requests.TransferRequest;
import com.example.starter.battery.web.Responses.InboundResponse;
import com.example.starter.battery.web.Responses.InspectionResponse;
import com.example.starter.battery.web.Responses.SwapOutResponse;
import com.example.starter.battery.web.Responses.TransferResponse;
import com.example.starter.battery.web.Responses.VerifyResponse;

/**
 * 电池台账核心业务规则测试：双阈值边界、检测不可改、换出、入站、
 * 幂等重放、事件链验真与篡改检测、调拨在途约束、24 小时异常与核销唯一。
 */
class BatteryLedgerServiceTest extends AbstractLedgerTest {

    private void register(String code, String station) {
        service.register(new RegisterBatteryRequest(code, station));
    }

    private InspectionResponse inspect(String code, String requestId, long batch,
                                       int soc, int health) {
        return service.inspect(new InspectionRequest(requestId, code, batch, soc, health));
    }

    private void registerAndList(String code, String station) {
        register(code, station);
        inspect(code, "insp-" + code, 1L, 95, 90);
    }

    // ------------------------------------------------------------------
    // 双阈值边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("双阈值恰好达标（SOC=90 且健康度=80）判定为上架可换")
    void exactThresholdPasses() {
        register("BAT-T1", "ST-A");
        InspectionResponse response = inspect("BAT-T1", "req-t1", 1L, 90, 80);
        assertTrue(response.passed());
        assertEquals(BatteryStatus.LISTED.name(), response.status());
        assertEquals(BatteryStatus.LISTED.name(), batteryStatus("BAT-T1"));
    }

    @Test
    @DisplayName("SOC 差 1%（89）不达标进入充电/待修")
    void socBelowThresholdFails() {
        register("BAT-T2", "ST-A");
        InspectionResponse response = inspect("BAT-T2", "req-t2", 1L, 89, 100);
        assertFalse(response.passed());
        assertEquals(BatteryStatus.CHARGING.name(), batteryStatus("BAT-T2"));
    }

    @Test
    @DisplayName("健康度差 1%（79）不达标进入充电/待修")
    void healthBelowThresholdFails() {
        register("BAT-T3", "ST-A");
        InspectionResponse response = inspect("BAT-T3", "req-t3", 1L, 100, 79);
        assertFalse(response.passed());
        assertEquals(BatteryStatus.CHARGING.name(), batteryStatus("BAT-T3"));
    }

    @Test
    @DisplayName("SOC 达标但健康度不达标仍判定不合格")
    void onlyOneThresholdMetFails() {
        register("BAT-T4", "ST-A");
        InspectionResponse response = inspect("BAT-T4", "req-t4", 1L, 90, 79);
        assertFalse(response.passed());
        assertEquals(BatteryStatus.CHARGING.name(), batteryStatus("BAT-T4"));
    }

    // ------------------------------------------------------------------
    // 检测结论不可修改，复检走新批次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同批次重复提交检测被拒绝，复检须使用更大批次号")
    void inspectionBatchMustIncrease() {
        register("BAT-B1", "ST-A");
        inspect("BAT-B1", "req-b1", 1L, 70, 70);
        assertEquals(BatteryStatus.CHARGING.name(), batteryStatus("BAT-B1"));

        assertThrows(ConflictException.class, () -> inspect("BAT-B1", "req-b2", 1L, 95, 95));
        assertEquals(BatteryStatus.CHARGING.name(), batteryStatus("BAT-B1"));

        InspectionResponse recheck = inspect("BAT-B1", "req-b3", 2L, 95, 95);
        assertTrue(recheck.passed());
        assertEquals(BatteryStatus.LISTED.name(), batteryStatus("BAT-B1"));
    }

    // ------------------------------------------------------------------
    // 换出与入站
    // ------------------------------------------------------------------

    @Test
    @DisplayName("换出扣减站点库存并绑定车辆，电池转为在用")
    void swapOutBindsVehicle() {
        registerAndList("BAT-S1", "ST-A");
        SwapOutResponse response = service.swapOut(new SwapOutRequest("req-s1", "BAT-S1", "VEH-1"));
        assertEquals(BatteryStatus.IN_USE.name(), response.status());
        assertEquals(BatteryStatus.IN_USE.name(), batteryStatus("BAT-S1"));
        assertEquals("VEH-1", service.getBattery("BAT-S1").vehicleId());
    }

    @Test
    @DisplayName("未上架电池不允许换出")
    void swapOutRequiresListed() {
        register("BAT-S2", "ST-A");
        assertThrows(ConflictException.class,
                () -> service.swapOut(new SwapOutRequest("req-s2", "BAT-S2", "VEH-1")));
    }

    @Test
    @DisplayName("同一电池换出后禁止再次换出")
    void swapOutOnlyOnce() {
        registerAndList("BAT-S3", "ST-A");
        service.swapOut(new SwapOutRequest("req-s3a", "BAT-S3", "VEH-1"));
        assertThrows(ConflictException.class,
                () -> service.swapOut(new SwapOutRequest("req-s3b", "BAT-S3", "VEH-2")));
    }

    @Test
    @DisplayName("换出后回站入站成功，非在用电池入站被拒绝")
    void inboundAfterSwapOut() {
        registerAndList("BAT-R1", "ST-A");
        service.swapOut(new SwapOutRequest("req-r1", "BAT-R1", "VEH-1"));

        InboundResponse inbound = service.inbound(new InboundRequest("req-r2", "BAT-R1", "ST-B"));
        assertEquals(BatteryStatus.PENDING_INSPECTION.name(), inbound.status());
        assertEquals("ST-B", service.getBattery("BAT-R1").stationId());

        assertThrows(ConflictException.class,
                () -> service.inbound(new InboundRequest("req-r3", "BAT-R1", "ST-B")));
    }

    @Test
    @DisplayName("查询不存在的电池返回 404 语义")
    void unknownBatteryNotFound() {
        assertThrows(NotFoundException.class, () -> service.getBattery("NOPE"));
    }

    // ------------------------------------------------------------------
    // 幂等：同键同参重放、同键异参 409
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同 requestId 同参数重放返回首次结果且不产生新事件")
    void idempotentReplayReturnsFirstResult() {
        register("BAT-I1", "ST-A");
        int eventsBefore = eventCount("BAT-I1");

        InspectionResponse first = inspect("BAT-I1", "req-i1", 1L, 95, 90);
        InspectionResponse replay = inspect("BAT-I1", "req-i1", 1L, 95, 90);

        assertEquals(first, replay);
        assertEquals(eventsBefore + 2, eventCount("BAT-I1"));
    }

    @Test
    @DisplayName("同 requestId 异参数返回 409 且不产生新事件")
    void idempotentConflictOnDifferentParams() {
        register("BAT-I2", "ST-A");
        inspect("BAT-I2", "req-i2", 1L, 95, 90);
        int eventsBefore = eventCount("BAT-I2");

        assertThrows(ConflictException.class, () -> inspect("BAT-I2", "req-i2", 1L, 50, 50));
        assertEquals(eventsBefore, eventCount("BAT-I2"));
        assertEquals(BatteryStatus.LISTED.name(), batteryStatus("BAT-I2"));
    }

    @Test
    @DisplayName("换出幂等：同键重放不重复扣减，异参 409")
    void swapOutIdempotency() {
        registerAndList("BAT-I3", "ST-A");
        SwapOutResponse first = service.swapOut(new SwapOutRequest("req-i3", "BAT-I3", "VEH-1"));
        SwapOutResponse replay = service.swapOut(new SwapOutRequest("req-i3", "BAT-I3", "VEH-1"));
        assertEquals(first, replay);

        assertThrows(ConflictException.class,
                () -> service.swapOut(new SwapOutRequest("req-i3", "BAT-I3", "VEH-9")));
        assertEquals("VEH-1", service.getBattery("BAT-I3").vehicleId());
    }

    // ------------------------------------------------------------------
    // 事件链验真与篡改检测
    // ------------------------------------------------------------------

    @Test
    @DisplayName("完整流转后事件链验真通过且顺序稳定")
    void chainVerifiesAfterFullFlow() {
        registerAndList("BAT-C1", "ST-A");
        service.swapOut(new SwapOutRequest("req-c1", "BAT-C1", "VEH-1"));
        service.inbound(new InboundRequest("req-c2", "BAT-C1", "ST-A"));

        VerifyResponse verify = service.verify("BAT-C1");
        assertTrue(verify.valid());
        assertEquals(5, verify.eventCount());

        var events = service.getEvents("BAT-C1");
        assertEquals(EventType.REGISTER, events.get(0).eventType());
        assertEquals(EventType.INSPECTION, events.get(1).eventType());
        assertEquals(EventType.LISTED, events.get(2).eventType());
        assertEquals(EventType.SWAP_OUT, events.get(3).eventType());
        assertEquals(EventType.INBOUND, events.get(4).eventType());
    }

    @Test
    @DisplayName("篡改事件内容后验真失败，入站拒绝并告警")
    void tamperedChainDetectedAndInboundRejected() {
        registerAndList("BAT-C2", "ST-A");
        service.swapOut(new SwapOutRequest("req-c3", "BAT-C2", "VEH-1"));

        jdbc.update("UPDATE battery_event SET payload = ? WHERE battery_code = ? AND seq = ?",
                "{\"vehicleId\":\"VEH-HACK\"}", "BAT-C2", 3);

        assertFalse(service.verify("BAT-C2").valid());
        assertThrows(ConflictException.class,
                () -> service.inbound(new InboundRequest("req-c4", "BAT-C2", "ST-A")));
        assertEquals(1, alarmRepository.countByBattery("BAT-C2"));
        assertEquals(BatteryStatus.IN_USE.name(), batteryStatus("BAT-C2"));
    }

    // ------------------------------------------------------------------
    // 站间调拨：在途约束、签收、24 小时异常、核销唯一
    // ------------------------------------------------------------------

    @Test
    @DisplayName("调拨在途期间禁止换出与再次调拨，签收后进入调入站待检")
    void transferInTransitConstraints() {
        registerAndList("BAT-X1", "ST-A");
        TransferResponse transfer = service.initiateTransfer(
                new TransferRequest("req-x1", "BAT-X1", "ST-A", "ST-B"));
        assertEquals(TransferStatus.IN_TRANSIT.name(), transfer.status());
        assertEquals(BatteryStatus.IN_TRANSIT.name(), batteryStatus("BAT-X1"));

        assertThrows(ConflictException.class,
                () -> service.swapOut(new SwapOutRequest("req-x2", "BAT-X1", "VEH-1")));
        assertThrows(ConflictException.class,
                () -> service.initiateTransfer(new TransferRequest("req-x3", "BAT-X1", "ST-A", "ST-C")));

        TransferResponse signed = service.signTransfer(transfer.transferId(), "req-x4");
        assertEquals(TransferStatus.SIGNED.name(), signed.status());
        assertEquals(BatteryStatus.PENDING_INSPECTION.name(), batteryStatus("BAT-X1"));
        assertEquals("ST-B", service.getBattery("BAT-X1").stationId());

        assertThrows(ConflictException.class,
                () -> service.signTransfer(transfer.transferId(), "req-x5"));
    }

    @Test
    @DisplayName("调出方与电池当前站点不一致时拒绝调拨")
    void transferRequiresMatchingSourceStation() {
        registerAndList("BAT-X2", "ST-A");
        assertThrows(ConflictException.class,
                () -> service.initiateTransfer(new TransferRequest("req-x6", "BAT-X2", "ST-Z", "ST-B")));
    }

    @Test
    @DisplayName("在途超过 24 小时自动转调拨异常，恰好 24 小时不转")
    void transferExceptionAfter24Hours() {
        registerAndList("BAT-X3", "ST-A");
        TransferResponse transfer = service.initiateTransfer(
                new TransferRequest("req-x7", "BAT-X3", "ST-A", "ST-B"));

        clock.advance(Duration.ofHours(24));
        assertEquals(0, service.refreshOverdueTransfers());
        assertEquals(TransferStatus.IN_TRANSIT.name(), transferStatus(transfer.transferId()));

        clock.advance(Duration.ofMillis(1));
        assertEquals(1, service.refreshOverdueTransfers());
        assertEquals(TransferStatus.EXCEPTION.name(), transferStatus(transfer.transferId()));
        assertEquals(BatteryStatus.TRANSFER_EXCEPTION.name(), batteryStatus("BAT-X3"));

        assertThrows(ConflictException.class,
                () -> service.signTransfer(transfer.transferId(), "req-x8"));
    }

    @Test
    @DisplayName("调拨异常仅可人工核销一次，核销后电池回调出站待检")
    void writeOffOnlyOnce() {
        registerAndList("BAT-X4", "ST-A");
        TransferResponse transfer = service.initiateTransfer(
                new TransferRequest("req-x9", "BAT-X4", "ST-A", "ST-B"));
        clock.advance(Duration.ofHours(25));
        assertEquals(1, service.refreshOverdueTransfers());

        TransferResponse writtenOff = service.writeOffTransfer(transfer.transferId());
        assertEquals(TransferStatus.WRITTEN_OFF.name(), writtenOff.status());
        assertEquals(BatteryStatus.PENDING_INSPECTION.name(), batteryStatus("BAT-X4"));
        assertEquals("ST-A", service.getBattery("BAT-X4").stationId());

        assertThrows(ConflictException.class,
                () -> service.writeOffTransfer(transfer.transferId()));
    }

    @Test
    @DisplayName("在途调拨未超时不允许直接核销")
    void writeOffRequiresException() {
        registerAndList("BAT-X5", "ST-A");
        TransferResponse transfer = service.initiateTransfer(
                new TransferRequest("req-x10", "BAT-X5", "ST-A", "ST-B"));
        assertThrows(ConflictException.class,
                () -> service.writeOffTransfer(transfer.transferId()));
    }

    @Test
    @DisplayName("调拨发起幂等：同键重放返回同一调拨单，异参 409")
    void transferIdempotency() {
        registerAndList("BAT-X6", "ST-A");
        TransferResponse first = service.initiateTransfer(
                new TransferRequest("req-x11", "BAT-X6", "ST-A", "ST-B"));
        TransferResponse replay = service.initiateTransfer(
                new TransferRequest("req-x11", "BAT-X6", "ST-A", "ST-B"));
        assertEquals(first, replay);

        assertThrows(ConflictException.class, () -> service.initiateTransfer(
                new TransferRequest("req-x11", "BAT-X6", "ST-A", "ST-C")));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer WHERE battery_code = ?",
                Integer.class, "BAT-X6"));
    }

    @Test
    @DisplayName("重复建档同编码被拒绝")
    void duplicateRegisterRejected() {
        register("BAT-D1", "ST-A");
        assertThrows(ConflictException.class,
                () -> service.register(new RegisterBatteryRequest("BAT-D1", "ST-B")));
    }
}
