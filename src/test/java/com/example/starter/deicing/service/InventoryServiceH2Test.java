package com.example.starter.deicing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.deicing.domain.BorrowStatus;
import com.example.starter.deicing.domain.DeicingType;
import com.example.starter.deicing.domain.RequestStatus;
import com.example.starter.deicing.web.ApiException;
import com.example.starter.deicing.web.dto.ApplicationRequest;
import com.example.starter.deicing.web.dto.BorrowRequest;
import com.example.starter.deicing.web.dto.BorrowResponse;
import com.example.starter.deicing.web.dto.FluidLedgerView;
import com.example.starter.deicing.web.dto.InboundRequest;
import com.example.starter.deicing.web.dto.LedgerResponse;
import com.example.starter.deicing.web.dto.ManualReviewView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 库存与配比管理的 H2（MODE=MySQL）业务/事务测试：
 * 两步法预占、库存不足转人工、效期 FEFO、一步法结算、借用幂等与液罐唯一、台账复算。
 */
@SpringBootTest
class InventoryServiceH2Test {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    private DeicingService deicingService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private BorrowService borrowService;
    @Autowired
    private ManualReviewService manualReviewService;
    @Autowired
    private TimeSource timeSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM deicing_allocation");
        jdbcTemplate.update("DELETE FROM deicing_request");
        jdbcTemplate.update("DELETE FROM fluid_reservation");
        jdbcTemplate.update("DELETE FROM fluid_consumption");
        jdbcTemplate.update("DELETE FROM fluid_borrow");
        jdbcTemplate.update("DELETE FROM manual_review_queue");
        jdbcTemplate.update("DELETE FROM fluid_batch WHERE source = 'INBOUND'");
        jdbcTemplate.update("UPDATE fluid_batch SET remaining_liters = quantity_liters");
        jdbcTemplate.update("UPDATE fluid_batch SET expiry_time = '2027-12-31 00:00:00'");
        timeSource.pin(Clock.fixed(T0, ZoneOffset.UTC));
    }

    private ApplicationRequest twoStep(String requestId, String flight, int durationMinutes,
                                       Instant takeoff) {
        return new ApplicationRequest(requestId, flight, "STAND-" + flight,
                DeicingType.TWO_STEP, 1, durationMinutes, takeoff);
    }

    private ApplicationRequest oneStep(String requestId, String flight, int durationMinutes,
                                       Instant takeoff) {
        return new ApplicationRequest(requestId, flight, "STAND-" + flight,
                DeicingType.ONE_STEP, 1, durationMinutes, takeoff);
    }

    private FluidLedgerView ledgerOf(String fluidCode) {
        return inventoryService.ledger().fluids().stream()
                .filter(f -> f.fluidCode().equals(fluidCode))
                .findFirst().orElseThrow();
    }

    @Test
    void twoStepApplyReservesByDurationAndRatioInSameTransaction() {
        // TYPE-II 配比 25 升/分钟，10 分钟作业 → 预占 250 升
        deicingService.apply(twoStep("REQ-R1", "CA101", 10, T0.plus(Duration.ofMinutes(60))));

        var reservation = jdbcTemplate.queryForMap(
                "SELECT fluid_code, quantity_liters, status FROM fluid_reservation"
                        + " WHERE request_id = 'REQ-R1'");
        assertThat(reservation.get("fluid_code")).isEqualTo("TYPE-II");
        assertThat((BigDecimal) reservation.get("quantity_liters"))
                .isEqualByComparingTo("250.00");
        assertThat(reservation.get("status")).isEqualTo("ACTIVE");

        FluidLedgerView ledger = ledgerOf("TYPE-II");
        assertThat(ledger.reservedLiters()).isEqualByComparingTo("250.00");
        // 预占不减少现存，但占用可预占量
        assertThat(ledger.currentLiters()).isEqualByComparingTo("10000.00");
        assertThat(ledger.availableLiters()).isEqualByComparingTo("9750.00");
    }

    @Test
    void insufficientStockTransfersToManualReviewWithoutSilentDegrade() {
        // 可预占库存仅 100 升，申请需 250 升
        jdbcTemplate.update("UPDATE fluid_batch SET remaining_liters = 100"
                + " WHERE batch_no = 'OPENING-TYPE-II'");

        assertThatThrownBy(() -> deicingService.apply(
                        twoStep("REQ-M1", "CA101", 10, T0.plus(Duration.ofMinutes(60)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("人工确认");

        // 未静默降级：没有产生排队记录，也没有预占
        Integer requests = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deicing_request WHERE request_id = 'REQ-M1'", Integer.class);
        assertThat(requests).isZero();
        Integer reservations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fluid_reservation WHERE request_id = 'REQ-M1'", Integer.class);
        assertThat(reservations).isZero();

        // 人工确认队列有 PENDING 记录（独立事务，申请回滚后仍保留）
        List<ManualReviewView> reviews = manualReviewService.list();
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).requestId()).isEqualTo("REQ-M1");
        assertThat(reviews.get(0).reason()).isEqualTo("INSUFFICIENT_FLUID");
        assertThat(reviews.get(0).status()).isEqualTo("PENDING");

        // 同键重试：库存仍不足 → 再次 409，且不重复登记
        assertThatThrownBy(() -> deicingService.apply(
                        twoStep("REQ-M1", "CA101", 10, T0.plus(Duration.ofMinutes(60)))))
                .isInstanceOf(ApiException.class);
        assertThat(manualReviewService.list()).hasSize(1);

        // 补料后同键申请可正常受理
        inventoryService.registerInbound(new InboundRequest("BATCH-NEW", "TYPE-II",
                new BigDecimal("1000"), T0.plus(Duration.ofDays(30))));
        var accepted = deicingService.apply(
                twoStep("REQ-M1", "CA101", 10, T0.plus(Duration.ofMinutes(60))));
        assertThat(accepted.item().status()).isEqualTo(RequestStatus.QUEUED);
    }

    @Test
    void expiredBatchesRejectedForReservationUntilFreshInbound() {
        // 全部 TYPE-II 批次效期耗尽
        jdbcTemplate.update("UPDATE fluid_batch SET expiry_time = '2026-09-24 00:00:00'"
                + " WHERE fluid_code = 'TYPE-II'");

        assertThatThrownBy(() -> deicingService.apply(
                        twoStep("REQ-E1", "CA101", 10, T0.plus(Duration.ofMinutes(60)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("效期");

        // 效期内的补料批次入库后可预占
        inventoryService.registerInbound(new InboundRequest("BATCH-FRESH", "TYPE-II",
                new BigDecimal("500"), T0.plus(Duration.ofDays(10))));
        var accepted = deicingService.apply(
                twoStep("REQ-E1", "CA101", 10, T0.plus(Duration.ofMinutes(60))));
        assertThat(accepted.item().status()).isEqualTo(RequestStatus.QUEUED);
    }

    @Test
    void expiredInboundBatchRejectedAtRegistration() {
        assertThatThrownBy(() -> inventoryService.registerInbound(new InboundRequest(
                        "BATCH-OLD", "TYPE-I", new BigDecimal("100"),
                        T0.minus(Duration.ofMinutes(1)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("效期");
    }

    @Test
    void oneStepSettlesByActualJobDurationOnTakeoff() {
        // TYPE-I 配比 40 升/分钟；10:00 开始作业，10:12 回报起飞 → 实际 12 分钟 → 480 升
        deicingService.apply(oneStep("REQ-S1", "CA101", 10, T0.plus(Duration.ofMinutes(90))));
        deicingService.dequeue("REQ-S1");
        deicingService.startJob("REQ-S1");
        deicingService.reportTakeoff("REQ-S1", T0.plus(Duration.ofMinutes(12)));

        var consumption = jdbcTemplate.queryForMap(
                "SELECT fluid_code, quantity_liters, job_minutes FROM fluid_consumption"
                        + " WHERE request_id = 'REQ-S1'");
        assertThat(consumption.get("fluid_code")).isEqualTo("TYPE-I");
        assertThat((BigDecimal) consumption.get("quantity_liters"))
                .isEqualByComparingTo("480.00");
        assertThat(((Number) consumption.get("job_minutes")).intValue()).isEqualTo(12);

        FluidLedgerView ledger = ledgerOf("TYPE-I");
        assertThat(ledger.consumedLiters()).isEqualByComparingTo("480.00");
        assertThat(ledger.currentLiters()).isEqualByComparingTo("19520.00");
    }

    @Test
    void twoStepReservationSettledOnTakeoff() {
        deicingService.apply(twoStep("REQ-S2", "CA101", 10, T0.plus(Duration.ofMinutes(30))));
        deicingService.dequeue("REQ-S2");
        deicingService.startJob("REQ-S2");
        deicingService.reportTakeoff("REQ-S2", T0.plus(Duration.ofMinutes(15)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fluid_reservation WHERE request_id = 'REQ-S2'",
                String.class)).isEqualTo("SETTLED");
        var consumption = jdbcTemplate.queryForMap(
                "SELECT quantity_liters, job_minutes FROM fluid_consumption"
                        + " WHERE request_id = 'REQ-S2'");
        assertThat((BigDecimal) consumption.get("quantity_liters"))
                .isEqualByComparingTo("250.00");

        FluidLedgerView ledger = ledgerOf("TYPE-II");
        assertThat(ledger.reservedLiters()).isEqualByComparingTo("0.00");
        assertThat(ledger.consumedLiters()).isEqualByComparingTo("250.00");
        assertThat(ledger.currentLiters()).isEqualByComparingTo("9750.00");
    }

    @Test
    void cancelReleasesActiveReservation() {
        deicingService.apply(twoStep("REQ-C1", "CA101", 10, T0.plus(Duration.ofMinutes(60))));
        deicingService.cancel("REQ-C1");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fluid_reservation WHERE request_id = 'REQ-C1'",
                String.class)).isEqualTo("RELEASED");
        FluidLedgerView ledger = ledgerOf("TYPE-II");
        assertThat(ledger.reservedLiters()).isEqualByComparingTo("0.00");
        assertThat(ledger.availableLiters()).isEqualByComparingTo("10000.00");
        assertThat(ledger.consumedLiters()).isEqualByComparingTo("0.00");
    }

    @Test
    void hotExpiredRedeiceReservesAnewNotReusingFirstReservation() {
        // 首次作业 HOT 失效：预占已转消耗（液已喷洒）
        deicingService.apply(twoStep("REQ-H1", "CA202", 10, T0.plus(Duration.ofMinutes(30))));
        deicingService.dequeue("REQ-H1");
        deicingService.startJob("REQ-H1");
        var expired = deicingService.reportTakeoff(
                "REQ-H1", T0.plus(Duration.ofMinutes(30)).plusSeconds(1));
        assertThat(expired.item().status()).isEqualTo(RequestStatus.HOT_EXPIRED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fluid_reservation WHERE request_id = 'REQ-H1'",
                String.class)).isEqualTo("SETTLED");

        // 重新除冰：新申请按新作业重新预占，不沿用首次预占
        deicingService.apply(twoStep("REQ-H1-RE", "CA202", 10, T0.plus(Duration.ofMinutes(90))));
        var reReservation = jdbcTemplate.queryForMap(
                "SELECT quantity_liters, status FROM fluid_reservation"
                        + " WHERE request_id = 'REQ-H1-RE'");
        assertThat((BigDecimal) reReservation.get("quantity_liters"))
                .isEqualByComparingTo("250.00");
        assertThat(reReservation.get("status")).isEqualTo("ACTIVE");

        FluidLedgerView ledger = ledgerOf("TYPE-II");
        assertThat(ledger.consumedLiters()).isEqualByComparingTo("250.00");
        assertThat(ledger.reservedLiters()).isEqualByComparingTo("250.00");
        assertThat(ledger.currentLiters()).isEqualByComparingTo("9750.00");
        assertThat(ledger.availableLiters()).isEqualByComparingTo("9500.00");
    }

    @Test
    void borrowIdempotentReplayAndConflictOnDifferentParams() {
        BorrowRequest request = new BorrowRequest("BOR-1", "TYPE-I", "TANK-1",
                new BigDecimal("100"), "TEAM-A", "TEAM-B");
        BorrowResponse first = borrowService.borrow(request);
        assertThat(first.replay()).isFalse();
        assertThat(first.item().status()).isEqualTo(BorrowStatus.OPEN);

        // 同键同参 → 返回首次结果
        BorrowResponse replay = borrowService.borrow(request);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.item().createdAt()).isEqualTo(first.item().createdAt());

        // 同键异参 → 409
        BorrowRequest tampered = new BorrowRequest("BOR-1", "TYPE-I", "TANK-1",
                new BigDecimal("200"), "TEAM-A", "TEAM-B");
        assertThatThrownBy(() -> borrowService.borrow(tampered))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fluid_borrow WHERE request_id = 'BOR-1'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameTankAllowsOnlyOneOpenBorrow() {
        borrowService.borrow(new BorrowRequest("BOR-T1", "TYPE-I", "TANK-9",
                new BigDecimal("50"), "TEAM-A", "TEAM-B"));

        // 同罐未归还 → 第二张借用单 409
        assertThatThrownBy(() -> borrowService.borrow(new BorrowRequest(
                        "BOR-T2", "TYPE-I", "TANK-9", new BigDecimal("50"),
                        "TEAM-C", "TEAM-D")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("TANK-9");

        // 归还登记后同罐可再次借用；重复归还幂等
        BorrowResponse returned = borrowService.returnBorrow("BOR-T1");
        assertThat(returned.item().status()).isEqualTo(BorrowStatus.RETURNED);
        assertThat(returned.item().returnedAt()).isNotNull();
        assertThat(borrowService.returnBorrow("BOR-T1").replay()).isTrue();

        BorrowResponse again = borrowService.borrow(new BorrowRequest(
                "BOR-T3", "TYPE-I", "TANK-9", new BigDecimal("50"), "TEAM-C", "TEAM-D"));
        assertThat(again.replay()).isFalse();
    }

    @Test
    void borrowDeductsAndReturnRestoresLedger() {
        FluidLedgerView before = ledgerOf("TYPE-I");
        assertThat(before.borrowedLiters()).isEqualByComparingTo("0.00");

        borrowService.borrow(new BorrowRequest("BOR-L1", "TYPE-I", "TANK-1",
                new BigDecimal("300"), "TEAM-A", "TEAM-B"));
        FluidLedgerView borrowed = ledgerOf("TYPE-I");
        assertThat(borrowed.borrowedLiters()).isEqualByComparingTo("300.00");
        assertThat(borrowed.currentLiters()).isEqualByComparingTo("19700.00");
        assertThat(borrowed.availableLiters()).isEqualByComparingTo("19700.00");

        borrowService.returnBorrow("BOR-L1");
        FluidLedgerView restored = ledgerOf("TYPE-I");
        assertThat(restored.borrowedLiters()).isEqualByComparingTo("0.00");
        assertThat(restored.currentLiters()).isEqualByComparingTo("20000.00");
    }

    @Test
    void fefoConsumesEarliestExpiryBatchFirst() {
        // 期初批次效期 2026-12-01；新入两个更早/更晚效期批次
        jdbcTemplate.update("UPDATE fluid_batch SET expiry_time = '2026-12-01 00:00:00'"
                + " WHERE batch_no = 'OPENING-TYPE-I'");
        inventoryService.registerInbound(new InboundRequest("BATCH-A", "TYPE-I",
                new BigDecimal("100"), Instant.parse("2026-10-01T00:00:00Z")));
        inventoryService.registerInbound(new InboundRequest("BATCH-B", "TYPE-I",
                new BigDecimal("100"), Instant.parse("2026-11-01T00:00:00Z")));

        // 借出 150 升 → 先扣 BATCH-A（10 月到期）100，再扣 BATCH-B（11 月到期）50
        borrowService.borrow(new BorrowRequest("BOR-F1", "TYPE-I", "TANK-1",
                new BigDecimal("150"), "TEAM-A", "TEAM-B"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_liters FROM fluid_batch WHERE batch_no = 'BATCH-A'",
                BigDecimal.class)).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_liters FROM fluid_batch WHERE batch_no = 'BATCH-B'",
                BigDecimal.class)).isEqualByComparingTo("50.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_liters FROM fluid_batch WHERE batch_no = 'OPENING-TYPE-I'",
                BigDecimal.class)).isEqualByComparingTo("20000.00");
    }

    @Test
    void ledgerIsRecomputableAndStableAcrossQueries() {
        // 期初 10000 + 入库 500 - 消耗 250 - 借出 200 = 10050
        inventoryService.registerInbound(new InboundRequest("BATCH-L", "TYPE-II",
                new BigDecimal("500"), T0.plus(Duration.ofDays(30))));
        deicingService.apply(twoStep("REQ-L1", "CA101", 10, T0.plus(Duration.ofMinutes(30))));
        deicingService.dequeue("REQ-L1");
        deicingService.startJob("REQ-L1");
        deicingService.reportTakeoff("REQ-L1", T0.plus(Duration.ofMinutes(15)));
        borrowService.borrow(new BorrowRequest("BOR-L9", "TYPE-II", "TANK-2",
                new BigDecimal("200"), "TEAM-A", "TEAM-B"));

        LedgerResponse first = inventoryService.ledger();
        LedgerResponse second = inventoryService.ledger();
        assertThat(second.fluids()).isEqualTo(first.fluids());

        FluidLedgerView ledger = first.fluids().stream()
                .filter(f -> f.fluidCode().equals("TYPE-II")).findFirst().orElseThrow();
        assertThat(ledger.openingLiters()).isEqualByComparingTo("10000.00");
        assertThat(ledger.inboundLiters()).isEqualByComparingTo("500.00");
        assertThat(ledger.consumedLiters()).isEqualByComparingTo("250.00");
        assertThat(ledger.borrowedLiters()).isEqualByComparingTo("200.00");
        assertThat(ledger.currentLiters()).isEqualByComparingTo("10050.00");

        // 台账与批次物理剩余一致：现存 = 全部批次剩余合计
        BigDecimal physical = jdbcTemplate.queryForObject(
                "SELECT SUM(remaining_liters) FROM fluid_batch WHERE fluid_code = 'TYPE-II'",
                BigDecimal.class);
        assertThat(physical).isEqualByComparingTo(ledger.currentLiters());
    }
}
