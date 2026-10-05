package com.example.starter.deicing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.RequestStatus;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.BorrowRequest;
import com.example.starter.deicing.web.dto.BorrowResponse;
import com.example.starter.deicing.web.dto.FluidLedgerView;
import com.example.starter.deicing.web.dto.InboundRequest;
import com.example.starter.deicing.web.dto.LedgerResponse;
import com.example.starter.deicing.web.dto.MutationResponse;
import com.example.starter.deicing.web.dto.TakeoffResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 库存与配比管理的 H2（MODE=MySQL）业务/事务测试：
 * 两步法预占、人工确认队列、一步法 FEFO 结算、借用幂等与液罐唯一性、台账复算、
 * HOT 失效重除冰重新预占。
 * 合成配比：ONE_STEP=TYPE_I 6 升/车位分钟；TWO_STEP=TYPE_I 6 + TYPE_IV 3 升/车位分钟。
 */
@SpringBootTest
class InventoryServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRY_FAR = Instant.parse("2027-06-30T00:00:00Z");
    private static final Instant EXPIRY_NEAR = Instant.parse("2026-12-31T00:00:00Z");
    private static final Instant EXPIRED = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private DeicingService deicingService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private TimeSource timeSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM deicing_allocation");
        jdbcTemplate.update("DELETE FROM deicing_request");
        jdbcTemplate.update("DELETE FROM inventory_consumption");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM tank_borrow_open");
        jdbcTemplate.update("DELETE FROM borrow_order");
        jdbcTemplate.update("DELETE FROM fluid_batch");
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private ApplicationRequest twoStep(String requestId, String flight, int requiredBays,
                                       int durationMinutes, Instant takeoff) {
        return new ApplicationRequest(requestId, flight, "STAND-" + flight,
                DeicingType.TWO_STEP, requiredBays, durationMinutes, takeoff);
    }

    private ApplicationRequest oneStep(String requestId, String flight, int durationMinutes,
                                       Instant takeoff) {
        return new ApplicationRequest(requestId, flight, "STAND-" + flight,
                DeicingType.ONE_STEP, 1, durationMinutes, takeoff);
    }

    private void seedBatch(String batchNo, String fluidCode, int quantity, Instant expiry) {
        jdbcTemplate.update("""
                INSERT INTO fluid_batch (batch_no, fluid_code, quantity_in, quantity_available, expiry_time)
                VALUES (?, ?, ?, ?, ?)
                """, batchNo, fluidCode, quantity, quantity, java.sql.Timestamp.from(expiry));
    }

    private int activeReservation(String requestId, String fluidCode) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_reservation
                WHERE request_id = ? AND fluid_code = ? AND status = 'ACTIVE'
                """, Integer.class, requestId, fluidCode);
        return value == null ? 0 : value;
    }

    private int consumed(String fluidCode) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM inventory_consumption WHERE fluid_code = ?",
                Integer.class, fluidCode);
        return value == null ? 0 : value;
    }

    private FluidLedgerView ledgerOf(String fluidCode) {
        return inventoryService.ledger().fluids().stream()
                .filter(f -> f.fluidCode().equals(fluidCode))
                .findFirst().orElseThrow();
    }

    @Test
    void twoStepApplyReservesByOccupancyDurationAndMixtureInSameTransaction() {
        // 2 车位 × 10 分钟：TYPE_I 2×10×6=120 升，TYPE_IV 2×10×3=60 升
        seedBatch("B-T1", "TYPE_I", 1000, EXPIRY_FAR);
        seedBatch("B-T4", "TYPE_IV", 1000, EXPIRY_FAR);

        MutationResponse response = deicingService.apply(
                twoStep("REQ-R1", "CA101", 2, 10, T0.plus(Duration.ofMinutes(90))));
        assertThat(response.item().status()).isEqualTo(RequestStatus.QUEUED);

        assertThat(activeReservation("REQ-R1", "TYPE_I")).isEqualTo(120);
        assertThat(activeReservation("REQ-R1", "TYPE_IV")).isEqualTo(60);
        // 预占不影响现存量，但扣减可预占量
        FluidLedgerView typeI = ledgerOf("TYPE_I");
        assertThat(typeI.currentLiters()).isEqualTo(1000);
        assertThat(typeI.activeReservedLiters()).isEqualTo(120);
        assertThat(typeI.reservableLiters()).isEqualTo(880);
    }

    @Test
    void insufficientStockGoesToManualReviewAndNeverSilentlyQueued() {
        // TYPE_I 仅 100 升：第一个两步法需 60 升成功，第二个不足转人工确认
        seedBatch("B-T1", "TYPE_I", 100, EXPIRY_FAR);
        seedBatch("B-T4", "TYPE_IV", 1000, EXPIRY_FAR);

        MutationResponse first = deicingService.apply(
                twoStep("REQ-M1", "CA101", 1, 10, T0.plus(Duration.ofMinutes(90))));
        assertThat(first.item().status()).isEqualTo(RequestStatus.QUEUED);

        MutationResponse second = deicingService.apply(
                twoStep("REQ-M2", "CA202", 1, 10, T0.plus(Duration.ofMinutes(120))));
        assertThat(second.item().status()).isEqualTo(RequestStatus.MANUAL_REVIEW);
        // 未预占任何库存
        assertThat(activeReservation("REQ-M2", "TYPE_I")).isZero();
        assertThat(activeReservation("REQ-M2", "TYPE_IV")).isZero();

        // 待确认记录不得出队、不得开始作业、不得起飞
        assertThatThrownBy(() -> deicingService.dequeue("REQ-M2"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("人工确认");
        assertThatThrownBy(() -> deicingService.startJob("REQ-M2"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> deicingService.reportTakeoff(
                "REQ-M2", T0.plus(Duration.ofMinutes(60))))
                .isInstanceOf(ApiException.class);

        // 库存仍不足：人工确认 409，保持人工确认队列
        assertThatThrownBy(() -> deicingService.confirmManual("REQ-M2"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("库存仍不足");
        assertThat(deicingService.get("REQ-M2").status()).isEqualTo(RequestStatus.MANUAL_REVIEW);

        // 补料入库后人工确认成功：转入排队并补登记预占
        inventoryService.inbound(new InboundRequest("B-T1-2", "TYPE_I", 100, EXPIRY_FAR));
        MutationResponse confirmed = deicingService.confirmManual("REQ-M2");
        assertThat(confirmed.item().status()).isEqualTo(RequestStatus.QUEUED);
        assertThat(activeReservation("REQ-M2", "TYPE_I")).isEqualTo(60);
        assertThat(activeReservation("REQ-M2", "TYPE_IV")).isEqualTo(30);

        // 确认后可正常出队（先排出截止更早的 REQ-M1）
        assertThat(deicingService.dequeue("REQ-M1").item().status())
                .isEqualTo(RequestStatus.SCHEDULED);
        assertThat(deicingService.dequeue("REQ-M2").item().status())
                .isEqualTo(RequestStatus.SCHEDULED);
    }

    @Test
    void expiredBatchesAreRejectedForReservationWithHint() {
        // TYPE_I 仅有已效期耗尽批次，TYPE_IV 充足
        seedBatch("B-T1-OLD", "TYPE_I", 1000, EXPIRED);
        seedBatch("B-T4", "TYPE_IV", 1000, EXPIRY_FAR);

        MutationResponse response = deicingService.apply(
                twoStep("REQ-E1", "CA101", 1, 10, T0.plus(Duration.ofMinutes(90))));
        assertThat(response.item().status()).isEqualTo(RequestStatus.MANUAL_REVIEW);
        assertThat(activeReservation("REQ-E1", "TYPE_I")).isZero();

        // 人工确认失败并提示效期耗尽批次
        assertThatThrownBy(() -> deicingService.confirmManual("REQ-E1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("效期耗尽");
    }

    @Test
    void oneStepSettlementDeductsFefoOnJobCompletion() {
        // 两个批次不同效期：先到期先出
        seedBatch("B-NEAR", "TYPE_I", 50, EXPIRY_NEAR);
        seedBatch("B-FAR", "TYPE_I", 50, EXPIRY_FAR);

        deicingService.apply(oneStep("REQ-S1", "CA101", 10, T0.plus(Duration.ofMinutes(90))));
        deicingService.dequeue("REQ-S1");
        deicingService.startJob("REQ-S1");

        // 实际作业 10 分钟 × 6 升 = 60 升：近期批次 50 升耗尽，远期批次扣 10 升
        MutationResponse completed = deicingService.completeJob(
                "REQ-S1", T0.plus(Duration.ofMinutes(10)));
        assertThat(completed.replay()).isFalse();
        assertThat(completed.item().actualEnd()).isEqualTo(T0.plus(Duration.ofMinutes(10)));

        List<Map<String, Object>> consumption = jdbcTemplate.queryForList("""
                SELECT b.batch_no, c.quantity FROM inventory_consumption c
                JOIN fluid_batch b ON b.id = c.batch_id
                WHERE c.request_id = 'REQ-S1' ORDER BY c.id
                """);
        assertThat(consumption).hasSize(2);
        assertThat(consumption.get(0).get("batch_no")).isEqualTo("B-NEAR");
        assertThat(consumption.get(0).get("quantity")).isEqualTo(50);
        assertThat(consumption.get(1).get("batch_no")).isEqualTo("B-FAR");
        assertThat(consumption.get(1).get("quantity")).isEqualTo(10);

        Integer nearLeft = jdbcTemplate.queryForObject(
                "SELECT quantity_available FROM fluid_batch WHERE batch_no = 'B-NEAR'",
                Integer.class);
        Integer farLeft = jdbcTemplate.queryForObject(
                "SELECT quantity_available FROM fluid_batch WHERE batch_no = 'B-FAR'",
                Integer.class);
        assertThat(nearLeft).isZero();
        assertThat(farLeft).isEqualTo(40);

        // 重复完成登记幂等：不重复扣减
        MutationResponse replay = deicingService.completeJob(
                "REQ-S1", T0.plus(Duration.ofMinutes(12)));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.item().actualEnd()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(consumed("TYPE_I")).isEqualTo(60);
    }

    @Test
    void settlementAndCompletionRollBackTogetherWhenStockInsufficient() {
        // 仅 30 升，作业需 60 升：结算失败则作业完成记录一并回滚
        seedBatch("B-T1", "TYPE_I", 30, EXPIRY_FAR);

        deicingService.apply(oneStep("REQ-S2", "CA101", 10, T0.plus(Duration.ofMinutes(90))));
        deicingService.dequeue("REQ-S2");
        deicingService.startJob("REQ-S2");

        assertThatThrownBy(() -> deicingService.completeJob(
                "REQ-S2", T0.plus(Duration.ofMinutes(10))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("不足");

        // 同事务回滚：无完成记录、无消耗台账、批次未扣减
        assertThat(deicingService.get("REQ-S2").actualEnd()).isNull();
        assertThat(consumed("TYPE_I")).isZero();
        Integer left = jdbcTemplate.queryForObject(
                "SELECT quantity_available FROM fluid_batch WHERE batch_no = 'B-T1'",
                Integer.class);
        assertThat(left).isEqualTo(30);
    }

    @Test
    void twoStepReservationConsumedOnCompletionAndNotDoubleCountedAtTakeoff() {
        seedBatch("B-T1", "TYPE_I", 1000, EXPIRY_FAR);
        seedBatch("B-T4", "TYPE_IV", 1000, EXPIRY_FAR);

        deicingService.apply(twoStep("REQ-TC", "CA101", 1, 10,
                T0.plus(Duration.ofMinutes(25))));
        deicingService.dequeue("REQ-TC");
        deicingService.startJob("REQ-TC");
        deicingService.completeJob("REQ-TC", T0.plus(Duration.ofMinutes(10)));

        // 预占已转消耗：TYPE_I 60、TYPE_IV 30
        assertThat(consumed("TYPE_I")).isEqualTo(60);
        assertThat(consumed("TYPE_IV")).isEqualTo(30);
        assertThat(activeReservation("REQ-TC", "TYPE_I")).isZero();

        // 起飞判定不再重复扣减
        TakeoffResponse takeoff = deicingService.reportTakeoff(
                "REQ-TC", T0.plus(Duration.ofMinutes(25)));
        assertThat(takeoff.valid()).isTrue();
        assertThat(consumed("TYPE_I")).isEqualTo(60);
        assertThat(consumed("TYPE_IV")).isEqualTo(30);
    }

    @Test
    void cancelBeforeStartReleasesReservation() {
        seedBatch("B-T1", "TYPE_I", 1000, EXPIRY_FAR);
        seedBatch("B-T4", "TYPE_IV", 1000, EXPIRY_FAR);

        deicingService.apply(twoStep("REQ-C1", "CA101", 1, 10,
                T0.plus(Duration.ofMinutes(90))));
        assertThat(activeReservation("REQ-C1", "TYPE_I")).isEqualTo(60);

        deicingService.cancel("REQ-C1");
        assertThat(activeReservation("REQ-C1", "TYPE_I")).isZero();
        String status = jdbcTemplate.queryForObject("""
                SELECT status FROM inventory_reservation
                WHERE request_id = 'REQ-C1' AND fluid_code = 'TYPE_I'
                """, String.class);
        assertThat(status).isEqualTo("RELEASED");
        // 可预占量恢复
        assertThat(ledgerOf("TYPE_I").reservableLiters()).isEqualTo(1000);
        assertThat(consumed("TYPE_I")).isZero();
    }

    @Test
    void hotExpiredRedeiceReservesAgainWithoutReusingFirstReservation() {
        seedBatch("B-T1", "TYPE_I", 1000, EXPIRY_FAR);
        seedBatch("B-T4", "TYPE_IV", 1000, EXPIRY_FAR);

        // 首次两步法：CA202 HOT=20，作业 [10:00,10:10)，HOT 终点 10:30
        deicingService.apply(twoStep("REQ-H1", "CA202", 1, 10,
                T0.plus(Duration.ofMinutes(30))));
        deicingService.dequeue("REQ-H1");
        deicingService.startJob("REQ-H1");
        TakeoffResponse late = deicingService.reportTakeoff(
                "REQ-H1", T0.plus(Duration.ofMinutes(30)).plusSeconds(1));
        assertThat(late.item().status()).isEqualTo(RequestStatus.HOT_EXPIRED);

        // 首次预占已转消耗（除冰液已实际使用）
        String firstStatus = jdbcTemplate.queryForObject("""
                SELECT status FROM inventory_reservation
                WHERE request_id = 'REQ-H1' AND fluid_code = 'TYPE_I'
                """, String.class);
        assertThat(firstStatus).isEqualTo("CONSUMED");
        assertThat(consumed("TYPE_I")).isEqualTo(60);

        // HOT 失效重除冰：新申请按新作业重新预占，不沿用首次预占
        MutationResponse reapply = deicingService.apply(
                twoStep("REQ-H2", "CA202", 1, 10, T0.plus(Duration.ofMinutes(90))));
        assertThat(reapply.item().status()).isEqualTo(RequestStatus.QUEUED);
        assertThat(activeReservation("REQ-H2", "TYPE_I")).isEqualTo(60);
        assertThat(activeReservation("REQ-H2", "TYPE_IV")).isEqualTo(30);
        // 现存量已扣首次消耗，可预占量再扣新预占
        FluidLedgerView typeI = ledgerOf("TYPE_I");
        assertThat(typeI.currentLiters()).isEqualTo(940);
        assertThat(typeI.reservableLiters()).isEqualTo(880);
    }

    @Test
    void borrowIsIdempotentAndRejectsDifferentParamsWith409() {
        seedBatch("B-T1", "TYPE_I", 1000, EXPIRY_FAR);
        BorrowRequest request = new BorrowRequest(
                "BOR-1", "TANK-1", "TYPE_I", 50, "TEAM-A", "TEAM-B");

        BorrowResponse first = inventoryService.borrow(request);
        assertThat(first.replay()).isFalse();
        assertThat(first.order().status().name()).isEqualTo("OPEN");

        // 同键同参：返回首次结果
        BorrowResponse replay = inventoryService.borrow(request);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.order().createdAt()).isEqualTo(first.order().createdAt());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM borrow_order WHERE request_id = 'BOR-1'", Integer.class);
        assertThat(count).isEqualTo(1);

        // 同键异参：409
        BorrowRequest tampered = new BorrowRequest(
                "BOR-1", "TANK-1", "TYPE_I", 60, "TEAM-A", "TEAM-B");
        assertThatThrownBy(() -> inventoryService.borrow(tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        // 台账借出量只记一次
        assertThat(ledgerOf("TYPE_I").lentOutLiters()).isEqualTo(50);
    }

    @Test
    void sameTankAllowsOnlyOneOpenBorrowUntilReturned() {
        seedBatch("B-T1", "TYPE_I", 1000, EXPIRY_FAR);
        inventoryService.borrow(new BorrowRequest(
                "BOR-1", "TANK-1", "TYPE_I", 50, "TEAM-A", "TEAM-B"));

        // 同一液罐第二张未归还借用单 → 409
        assertThatThrownBy(() -> inventoryService.borrow(new BorrowRequest(
                "BOR-2", "TANK-1", "TYPE_I", 10, "TEAM-A", "TEAM-C")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("TANK-1");

        // 归还登记后该液罐可再次借用；重复归还幂等
        BorrowResponse returned = inventoryService.returnBorrow("BOR-1");
        assertThat(returned.order().status().name()).isEqualTo("RETURNED");
        assertThat(returned.order().returnedAt()).isNotNull();
        BorrowResponse returnedReplay = inventoryService.returnBorrow("BOR-1");
        assertThat(returnedReplay.replay()).isTrue();

        BorrowResponse second = inventoryService.borrow(new BorrowRequest(
                "BOR-3", "TANK-1", "TYPE_I", 20, "TEAM-A", "TEAM-D"));
        assertThat(second.replay()).isFalse();

        // 归还后借出量只剩未归还的 20 升
        assertThat(ledgerOf("TYPE_I").lentOutLiters()).isEqualTo(20);
    }

    @Test
    void borrowCannotTouchReservedOrExpiredStock() {
        seedBatch("B-T1", "TYPE_I", 100, EXPIRY_FAR);
        seedBatch("B-T4", "TYPE_IV", 1000, EXPIRY_FAR);
        // 两步法预占 60 升后可借出量仅 40 升
        deicingService.apply(twoStep("REQ-RB", "CA101", 1, 10,
                T0.plus(Duration.ofMinutes(90))));
        assertThat(deicingService.get("REQ-RB").status()).isEqualTo(RequestStatus.QUEUED);

        assertThatThrownBy(() -> inventoryService.borrow(new BorrowRequest(
                "BOR-X", "TANK-9", "TYPE_I", 50, "TEAM-A", "TEAM-B")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("不足");
    }

    @Test
    void ledgerIsRecomputableAndStableAcrossQueries() {
        seedBatch("B-T1", "TYPE_I", 500, EXPIRY_FAR);
        seedBatch("B-T4", "TYPE_IV", 300, EXPIRY_FAR);
        inventoryService.inbound(new InboundRequest("B-T1-2", "TYPE_I", 200, EXPIRY_FAR));

        // 消耗 60 升（一步法 10 分钟）
        deicingService.apply(oneStep("REQ-L1", "CA101", 10, T0.plus(Duration.ofMinutes(90))));
        deicingService.dequeue("REQ-L1");
        deicingService.startJob("REQ-L1");
        deicingService.completeJob("REQ-L1", T0.plus(Duration.ofMinutes(10)));
        // 借出 30 升未归还
        inventoryService.borrow(new BorrowRequest(
                "BOR-L", "TANK-2", "TYPE_I", 30, "TEAM-A", "TEAM-B"));

        LedgerResponse first = inventoryService.ledger();
        LedgerResponse second = inventoryService.ledger();
        assertThat(second.fluids()).isEqualTo(first.fluids());

        FluidLedgerView typeI = first.fluids().stream()
                .filter(f -> f.fluidCode().equals("TYPE_I")).findFirst().orElseThrow();
        // 期初 0 + 入库 700 - 消耗 60 - 借出 30 = 现存 610
        assertThat(typeI.openingLiters()).isZero();
        assertThat(typeI.inboundLiters()).isEqualTo(700);
        assertThat(typeI.consumedLiters()).isEqualTo(60);
        assertThat(typeI.lentOutLiters()).isEqualTo(30);
        assertThat(typeI.currentLiters()).isEqualTo(610);
        // 与批次剩余量交叉复核：现存 = Σ批次剩余 - 未归还借出
        Integer batchLeft = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity_available), 0) FROM fluid_batch "
                        + "WHERE fluid_code = 'TYPE_I'", Integer.class);
        assertThat(typeI.currentLiters()).isEqualTo(batchLeft - 30);
    }
}
