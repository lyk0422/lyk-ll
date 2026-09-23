package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.domain.AliquotStatus;
import com.example.starter.domain.ReservationStatus;
import com.example.starter.repo.AliquotRepository;
import com.example.starter.repo.AliquotRow;
import com.example.starter.repo.ReservationRepository;
import com.example.starter.repo.ReservationRow;
import com.example.starter.repo.SpecimenRepository;
import com.example.starter.support.ConflictException;
import com.example.starter.support.LabClock;
import com.example.starter.web.dto.AliquotView;
import com.example.starter.web.dto.CancelRequest;
import com.example.starter.web.dto.CustodyEventView;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.QuarantineRequest;
import com.example.starter.web.dto.ReservationView;
import com.example.starter.web.dto.ReserveRequest;
import com.example.starter.web.dto.ResultRequest;
import com.example.starter.web.dto.SplitItemRequest;
import com.example.starter.web.dto.SplitRequest;
import com.example.starter.web.dto.SplitResponse;
import com.example.starter.web.dto.StartRequest;
import com.example.starter.web.dto.TransferRequest;
import com.example.starter.web.dto.VolumeLedgerResponse;

/**
 * 基于 H2（MODE=MySQL）的事务、守恒、并发与保管链集成测试。
 */
@SpringBootTest
class CustodyServiceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-23T01:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private CustodyService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LabClock labClock;
    @Autowired
    private SpecimenRepository specimenRepository;
    @Autowired
    private AliquotRepository aliquotRepository;
    @Autowired
    private ReservationRepository reservationRepository;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE custody_event");
        jdbc.execute("TRUNCATE TABLE idempotent_request");
        jdbc.execute("TRUNCATE TABLE reservation");
        jdbc.execute("TRUNCATE TABLE aliquot");
        jdbc.execute("TRUNCATE TABLE specimen");
        labClock.setClock(Clock.fixed(BASE, ZONE));
    }

    @AfterEach
    void resetClock() {
        labClock.setClock(Clock.system(ZONE));
    }

    private void tickSeconds(long seconds) {
        labClock.setClock(Clock.fixed(BASE.plusSeconds(seconds), ZONE));
    }

    // ===================== 精度与守恒 =====================

    @Test
    void splitConservesVolumeWithMilliPrecision() {
        service.intake(new IntakeRequest("S1", "10.000", "alice"));

        SplitResponse split = service.split(new SplitRequest(
                "S1", "REQ-SP-1", 0, null,
                List.of(new SplitItemRequest("A1", "4.000", null),
                        new SplitItemRequest("A2", "3.500", "bob"))));

        assertThat(split.replayed()).isFalse();
        assertThat(split.specimen().remainingUl()).isEqualTo("2.5");
        assertThat(split.aliquots()).extracting(AliquotView::aliquotId)
                .containsExactly("A1", "A2");
        assertThat(split.aliquots().get(0).custodian()).isEqualTo("alice");
        assertThat(split.aliquots().get(1).custodian()).isEqualTo("bob");

        VolumeLedgerResponse ledger = service.ledger("S1");
        assertThat(ledger.specimen().volumeUl()).isEqualTo("10");
        // 守恒：原始体积 = 剩余 + 全部分装管体积
        long accounted = parseUl(ledger.specimen().remainingUl());
        for (AliquotView view : ledger.aliquots()) {
            accounted += parseUl(view.volumeUl());
        }
        assertThat(accounted).isEqualTo(10_000L);
    }

    @Test
    void rejectsFourDecimalVolume() {
        service.intake(new IntakeRequest("S1", "10", "alice"));
        assertThatThrownBy(() -> service.split(new SplitRequest(
                "S1", "REQ-BAD-P", 0, null,
                List.of(new SplitItemRequest("A1", "1.0005", null)))))
                .hasMessageContaining("三位小数");
        assertThat(service.getSpecimen("S1").remainingUl()).isEqualTo("10");
    }

    // ===================== 分装回滚 =====================

    @Test
    void illegalSplitRollsBackEntireBatch() {
        service.intake(new IntakeRequest("S1", "5.000", "alice"));

        // 第二个条目超额（6.000 > 5.000），整批必须回滚
        assertThatThrownBy(() -> service.split(new SplitRequest(
                "S1", "REQ-SP-BAD", 0, null,
                List.of(new SplitItemRequest("A1", "2.000", null),
                        new SplitItemRequest("A2", "6.000", null)))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("超过剩余量");

        assertThat(service.getSpecimen("S1").remainingUl()).isEqualTo("5");
        assertThat(aliquotRepository.findBySpecimenId("S1")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM custody_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateAliquotIdInsideBatchRollsBack() {
        service.intake(new IntakeRequest("S1", "5.000", "alice"));
        assertThatThrownBy(() -> service.split(new SplitRequest(
                "S1", "REQ-DUP", 0, null,
                List.of(new SplitItemRequest("A1", "1", null),
                        new SplitItemRequest("A1", "1", null)))))
                .hasMessageContaining("aliquotId 重复");
        assertThat(aliquotRepository.findBySpecimenId("S1")).isEmpty();
    }

    // ===================== 请求指纹（幂等与 409） =====================

    @Test
    void splitSameRequestIdSameParamsReplaysFirstResult() {
        service.intake(new IntakeRequest("S1", "10.000", "alice"));
        SplitRequest first = new SplitRequest("S1", "REQ-IDEM", 0, null,
                List.of(new SplitItemRequest("A1", "2.000", null)));
        assertThat(service.split(first).replayed()).isFalse();

        SplitResponse replay = service.split(first);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.aliquots()).hasSize(1);
        assertThat(service.getSpecimen("S1").remainingUl()).isEqualTo("8");
        assertThat(aliquotRepository.findBySpecimenId("S1")).hasSize(1);
    }

    @Test
    void splitSameRequestIdDifferentVersionReturns409() {
        service.intake(new IntakeRequest("S1", "10.000", "alice"));
        service.split(new SplitRequest("S1", "REQ-FP", 0, null,
                List.of(new SplitItemRequest("A1", "2.000", null))));

        // 同键异参：expectedVersion 从 0 变为 1
        assertThatThrownBy(() -> service.split(new SplitRequest("S1", "REQ-FP", 1, null,
                List.of(new SplitItemRequest("A1", "2.000", null)))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("参数与首次请求不一致");

        // 同键异参：占用量变化
        assertThatThrownBy(() -> service.split(new SplitRequest("S1", "REQ-FP", 0, null,
                List.of(new SplitItemRequest("A1", "3.000", null)))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reserveFingerprintConflictsOnVolumeOrVersionChange() {
        prepareSingleAliquot("S1", "A1", "10.000");
        ReserveRequest first = new ReserveRequest("REQ-R1", "A1", "2.000", "PROJ-X", 0, "lab");
        ReservationView view = service.reserve(first);
        assertThat(view.status()).isEqualTo("RESERVED");

        // 同键同参：返回首次结果
        assertThat(service.reserve(first)).usingRecursiveComparison().isEqualTo(view);

        assertThatThrownBy(() -> service.reserve(
                new ReserveRequest("REQ-R1", "A1", "2.500", "PROJ-X", 0, "lab")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.reserve(
                new ReserveRequest("REQ-R1", "A1", "2.000", "PROJ-X", 1, "lab")))
                .isInstanceOf(ConflictException.class);
        assertThat(reservationRepository.findByAliquotId("A1")).hasSize(1);
    }

    // ===================== 并发占用 =====================

    @Test
    void concurrentReservationsNeverOvercommit() throws Exception {
        prepareSingleAliquot("S1", "A1", "10.000");

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.reserve(new ReserveRequest(
                            "REQ-C-" + index, "A1", "3.000", "P", 0, null));
                    success.incrementAndGet();
                } catch (ConflictException ex) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 10µL 中每单 3µL：恰好 3 单成功，7 单拒绝
        assertThat(success.get()).isEqualTo(3);
        assertThat(rejected.get()).isEqualTo(7);

        AliquotRow row = aliquotRepository.findByAliquotId("A1").orElseThrow();
        long reservedTotal = reservationRepository.findByAliquotId("A1").stream()
                .filter(r -> r.status() == ReservationStatus.RESERVED)
                .mapToLong(ReservationRow::volumeNl).sum();
        assertThat(reservedTotal).isEqualTo(9_000L);
        assertThat(row.consumedNl()).isZero();
        assertThat(service.getAliquot("A1").availableUl()).isEqualTo("1");
    }

    @Test
    void concurrentSameRequestIdCreatesExactlyOneReservation() throws Exception {
        prepareSingleAliquot("S1", "A1", "10.000");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationView>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.reserve(new ReserveRequest("REQ-SAME", "A1", "2.000", "P", 0, null));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<ReservationView> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).requestId()).isEqualTo("REQ-SAME");
        }
        pool.shutdown();

        assertThat(reservationRepository.findByAliquotId("A1")).hasSize(1);
        assertThat(service.getAliquot("A1").reservedUl()).isEqualTo("2");
    }

    // ===================== 开始 / 取消 / 迟到结果 =====================

    @Test
    void startMovesVolumeToConsumedAndConsumedCannotCancel() {
        prepareSingleAliquot("S1", "A1", "10.000");
        service.reserve(new ReserveRequest("REQ-X", "A1", "4.000", "P", 0, null));

        ReservationView started = service.start("REQ-X", new StartRequest(0));
        assertThat(started.status()).isEqualTo("CONSUMED");
        AliquotRow row = aliquotRepository.findByAliquotId("A1").orElseThrow();
        assertThat(row.consumedNl()).isEqualTo(4_000L);
        assertThat(row.version()).isEqualTo(1);

        assertThatThrownBy(() -> service.cancel("REQ-X", new CancelRequest(1)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.start("REQ-X", new StartRequest(1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelledReservationRejectsLateResultAndKeepsVolumeReleased() {
        prepareSingleAliquot("S1", "A1", "10.000");
        service.reserve(new ReserveRequest("REQ-Y", "A1", "6.000", "P", 0, null));
        assertThat(service.getAliquot("A1").availableUl()).isEqualTo("4");

        ReservationView cancelled = service.cancel("REQ-Y", new CancelRequest(0));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(service.getAliquot("A1").availableUl()).isEqualTo("10");

        assertThatThrownBy(() -> service.recordResult("REQ-Y",
                new ResultRequest(1, java.util.Map.of("value", 42))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("迟到结果");

        ReservationRow row = reservationRepository.findByRequestId("REQ-Y").orElseThrow();
        assertThat(row.resultPayload()).isNull();
        assertThat(row.resultAt()).isNull();
        // 取消后不可重复取消
        assertThatThrownBy(() -> service.cancel("REQ-Y", new CancelRequest(1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void resultBeforeStartIsRejectedAndResultWritesExactlyOnce() {
        prepareSingleAliquot("S1", "A2", "10.000");
        service.reserve(new ReserveRequest("REQ-Z", "A2", "1.000", "P", 0, null));
        assertThatThrownBy(() -> service.recordResult("REQ-Z",
                new ResultRequest(0, java.util.Map.of("v", 1))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("尚未开始");

        service.start("REQ-Z", new StartRequest(0));
        ReservationView withResult = service.recordResult("REQ-Z",
                new ResultRequest(1, java.util.Map.of("v", 1)));
        assertThat(withResult.status()).isEqualTo("CONSUMED");
        assertThat(withResult.result()).isEqualTo(java.util.Map.of("v", 1));
        // 重复结果
        assertThatThrownBy(() -> service.recordResult("REQ-Z",
                new ResultRequest(2, java.util.Map.of("v", 2))))
                .isInstanceOf(ConflictException.class);
    }

    // ===================== 污染传播 =====================

    @Test
    void quarantineCancelsReservedButKeepsConsumedAndHistory() {
        prepareSingleAliquot("S1", "A1", "10.000");
        service.reserve(new ReserveRequest("REQ-K1", "A1", "3.000", "P1", 0, null));
        service.reserve(new ReserveRequest("REQ-K2", "A1", "2.000", "P2", 0, "lab"));
        service.start("REQ-K1", new StartRequest(0)); // aliquot version 变为 1

        AliquotView quarantined = service.quarantine(new QuarantineRequest(
                "A1", 1, "alice", null, "污染事件"));
        assertThat(quarantined.status()).isEqualTo("QUARANTINED");
        assertThat(quarantined.custodian()).isEqualTo("QUARANTINE");

        ReservationRow consumed = reservationRepository.findByRequestId("REQ-K1").orElseThrow();
        ReservationRow cancelled = reservationRepository.findByRequestId("REQ-K2").orElseThrow();
        assertThat(consumed.status()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);

        AliquotRow row = aliquotRepository.findByAliquotId("A1").orElseThrow();
        assertThat(row.status()).isEqualTo(AliquotStatus.QUARANTINED);
        // 已消耗量保留
        assertThat(row.consumedNl()).isEqualTo(3_000L);

        // 隔离后不能新建占用
        assertThatThrownBy(() -> service.reserve(
                new ReserveRequest("REQ-K3", "A1", "1.000", "P3", 2, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("隔离");
        // 隔离后迟到结果
        assertThatThrownBy(() -> service.recordResult("REQ-K2",
                new ResultRequest(1, java.util.Map.of("v", 1))))
                .isInstanceOf(ConflictException.class);
        // 历史保管事件全部保留（A1 的 CREATE 与 QUARANTINE）
        assertThat(service.getEvents("ALIQUOT", "A1")).hasSize(2);
    }

    @Test
    void quarantineWithWrongVersionOrCustodianIsRejected() {
        prepareSingleAliquot("S1", "A1", "10.000");
        assertThatThrownBy(() -> service.quarantine(
                new QuarantineRequest("A1", 9, "alice", null, null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.quarantine(
                new QuarantineRequest("A1", 0, "nobody", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("持有人");
        assertThat(aliquotRepository.findByAliquotId("A1").orElseThrow().status())
                .isEqualTo(AliquotStatus.AVAILABLE);
    }

    // ===================== 交接幂等 / 持有人 / 时间 =====================

    @Test
    void transferIsIdempotentAndChecksHolderVersionAndTime() {
        service.intake(new IntakeRequest("S1", "10.000", "alice"));

        TransferRequest transfer = new TransferRequest("SPECIMEN", "S1", "alice", "bob", 0);
        CustodyEventView event = service.transfer(transfer);
        assertThat(event.eventKind()).isEqualTo("TRANSFER");
        assertThat(event.versionBefore()).isEqualTo(0);
        assertThat(event.versionAfter()).isEqualTo(1);
        assertThat(service.getSpecimen("S1").custodian()).isEqualTo("bob");
        assertThat(service.getSpecimen("S1").version()).isEqualTo(1);

        // 重复交接：不新增事件、不推进版本
        CustodyEventView replay = service.transfer(transfer);
        assertThat(replay).usingRecursiveComparison().isEqualTo(event);
        assertThat(service.getEvents("SPECIMEN", "S1")).hasSize(2);
        assertThat(service.getSpecimen("S1").version()).isEqualTo(1);

        // 错误当前持有人
        assertThatThrownBy(() -> service.transfer(
                new TransferRequest("SPECIMEN", "S1", "alice", "carol", 1)))
                .isInstanceOf(ConflictException.class);
        // 错误版本
        assertThatThrownBy(() -> service.transfer(
                new TransferRequest("SPECIMEN", "S1", "bob", "carol", 0)))
                .isInstanceOf(ConflictException.class);
        // 目标人与当前人相同
        assertThatThrownBy(() -> service.transfer(
                new TransferRequest("SPECIMEN", "S1", "bob", "bob", 1)))
                .isInstanceOf(RuntimeException.class);

        // 时间倒退
        labClock.setClock(Clock.fixed(BASE.minusSeconds(60), ZONE));
        assertThatThrownBy(() -> service.transfer(
                new TransferRequest("SPECIMEN", "S1", "bob", "carol", 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("时间");

        // 时间恢复后成功
        labClock.setClock(Clock.fixed(BASE.plusSeconds(30), ZONE));
        service.transfer(new TransferRequest("SPECIMEN", "S1", "bob", "carol", 1));
        assertThat(service.getSpecimen("S1").custodian()).isEqualTo("carol");
        assertThat(service.getEvents("SPECIMEN", "S1")).hasSize(3);
    }

    @Test
    void transferOfAliquotAndReservationFollowsSameRules() {
        prepareSingleAliquot("S1", "A1", "10.000");
        service.reserve(new ReserveRequest("REQ-T", "A1", "1.000", "P", 0, "lab"));

        service.transfer(new TransferRequest("ALIQUOT", "A1", "alice", "bob", 0));
        assertThat(service.getAliquot("A1").custodian()).isEqualTo("bob");

        service.transfer(new TransferRequest("RESERVATION", "REQ-T", "lab", "analyst", 0));
        assertThat(service.getReservation("REQ-T").custodian()).isEqualTo("analyst");

        assertThatThrownBy(() -> service.transfer(
                new TransferRequest("UNKNOWN", "X", "a", "b", 0)))
                .isInstanceOf(RuntimeException.class);
    }

    // ===================== 账本 =====================

    @Test
    void ledgerReflectsReservedConsumedAndCancelled() {
        service.intake(new IntakeRequest("S1", "10.000", "alice"));
        service.split(new SplitRequest("S1", "REQ-SP", 0, null,
                List.of(new SplitItemRequest("A1", "6.000", null),
                        new SplitItemRequest("A2", "3.000", null))));
        service.reserve(new ReserveRequest("REQ-L1", "A1", "2.000", "P1", 0, null));
        service.reserve(new ReserveRequest("REQ-L2", "A1", "1.500", "P2", 0, null));
        service.start("REQ-L1", new StartRequest(0));
        service.cancel("REQ-L2", new CancelRequest(0));

        VolumeLedgerResponse ledger = service.ledger("S1");
        assertThat(ledger.specimen().remainingUl()).isEqualTo("1");
        AliquotView a1 = ledger.aliquots().stream()
                .filter(v -> v.aliquotId().equals("A1")).findFirst().orElseThrow();
        assertThat(a1.volumeUl()).isEqualTo("6");
        assertThat(a1.consumedUl()).isEqualTo("2");
        assertThat(a1.reservedUl()).isEqualTo("0");
        assertThat(a1.availableUl()).isEqualTo("4");
        assertThat(ledger.reservations()).hasSize(2);
    }

    // ===================== 辅助 =====================

    private void prepareSingleAliquot(String specimenId, String aliquotId, String volume) {
        service.intake(new IntakeRequest(specimenId, volume, "alice"));
        service.split(new SplitRequest(specimenId, "REQ-SP-" + aliquotId, 0, null,
                List.of(new SplitItemRequest(aliquotId, volume, null))));
    }

    private static long parseUl(String text) {
        return new java.math.BigDecimal(text)
                .setScale(3, java.math.RoundingMode.HALF_UP)
                .movePointRight(3).longValueExact();
    }
}
