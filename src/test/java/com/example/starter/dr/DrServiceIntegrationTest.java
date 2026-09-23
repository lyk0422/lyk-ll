package com.example.starter.dr;

import com.example.starter.dr.dto.AdjustmentResponse;
import com.example.starter.dr.dto.EnrollRequest;
import com.example.starter.dr.dto.EventCreateRequest;
import com.example.starter.dr.dto.FreezeRequest;
import com.example.starter.dr.dto.FreezeResponse;
import com.example.starter.dr.dto.IntervalEvidence;
import com.example.starter.dr.dto.MeterEvidence;
import com.example.starter.dr.dto.PointResponse;
import com.example.starter.dr.dto.PointWriteRequest;
import com.example.starter.dr.service.DrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 需求响应服务集成测试：使用真实 H2（MODE=MySQL）验证半开区间、缺值、封顶、
 * 重复承诺、冻结回滚、并发更正、冻结后调整与请求指纹等业务规则。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:dr_svc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Import(DrTestConfiguration.class)
class DrServiceIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-23T01:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-23T02:00:00Z");
    private static final BigDecimal BD5 = new BigDecimal("5.000000");
    private static final BigDecimal BD4 = new BigDecimal("4.000000");
    private static final BigDecimal BD3 = new BigDecimal("3.000000");
    private static final BigDecimal BD2 = new BigDecimal("2.000000");
    private static final BigDecimal BD10 = new BigDecimal("10.000000");

    @Autowired
    private DrService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dr_adjustment");
        jdbc.update("DELETE FROM dr_freeze_interval");
        jdbc.update("DELETE FROM dr_settlement_freeze");
        jdbc.update("DELETE FROM dr_curve_point");
        jdbc.update("DELETE FROM dr_enrollment");
        jdbc.update("DELETE FROM dr_event");
    }

    private String createEvent(String ref, Instant start, Instant end, int intervalMinutes) {
        service.createEvent(new EventCreateRequest(ref, start, end, intervalMinutes));
        return ref;
    }

    private String uniqueEvent() {
        return "EVT-" + UUID.randomUUID();
    }

    private void enroll(String eventRef, String meterId, String committed) {
        service.enrollMeter(eventRef,
                new EnrollRequest(meterId, new BigDecimal(committed), "BL-TAG-1"));
    }

    private PointResponse writePoint(String eventRef, String meterId, String type,
                                     int idx, String value, Integer expectedVersion) {
        return service.writePoint(eventRef, meterId,
                new PointWriteRequest(type, idx, new BigDecimal(value), expectedVersion));
    }

    private FreezeRequest singleMeterFreezeRequest(String requestId, int expectedVersion,
                                                   String meterId, int intervalCount,
                                                   int baselineVersion, int actualVersion) {
        List<IntervalEvidence> intervals = new java.util.ArrayList<>();
        for (int idx = 0; idx < intervalCount; idx++) {
            intervals.add(new IntervalEvidence(idx, baselineVersion, actualVersion));
        }
        return new FreezeRequest(requestId, expectedVersion,
                List.of(new MeterEvidence(meterId, intervals)));
    }

    // ---------- 半开区间与粒度 ----------

    @Test
    @DisplayName("半开区间：60 分钟事件按 30 分钟粒度恰好 2 个区间，尾部不足粒度被拒绝")
    void halfOpenIntervalCountAndGranularity() {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 30);
        assertThat(service.getEvent(ref).intervalCount()).isEqualTo(2);

        String bad = uniqueEvent();
        assertThatThrownBy(() -> createEvent(bad, T0, T1.plusSeconds(60), 30))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("整数倍");

        // 区间编号 2 超出半开范围 [0,2)
        enroll(ref, "M1", "10");
        assertThatThrownBy(() -> writePoint(ref, "M1", "BASELINE", 2, "10", null))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("半开范围");
    }

    // ---------- 重复承诺 ----------

    @Test
    @DisplayName("时间重叠事件中同一电表不得重复承诺；端点相接允许；同事件重复登记拒绝")
    void noOverlappingCommitmentButAdjacentAllowed() {
        String a = uniqueEvent();
        String b = uniqueEvent();
        String c = uniqueEvent();
        createEvent(a, T0, T1, 60);
        createEvent(b, T1, Instant.parse("2026-09-23T03:00:00Z"), 60); // 与 a 端点相接
        createEvent(c, Instant.parse("2026-09-23T01:30:00Z"),
                Instant.parse("2026-09-23T02:30:00Z"), 60); // 与 a 重叠

        enroll(a, "M1", "10");
        // 端点相接不算重叠
        enroll(b, "M1", "10");
        // 真实时间重叠：拒绝
        assertThatThrownBy(() -> enroll(c, "M1", "10"))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("时间重叠");
        // 同一事件重复登记：拒绝
        assertThatThrownBy(() -> enroll(a, "M1", "10"))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("已登记");
        // 另一块电表不受影响
        enroll(c, "M2", "10");
    }

    // ---------- 曲线版本与缺值 ----------

    @Test
    @DisplayName("首次写入版本为 1；更正须携带前版本号；缺值冻结按 PENDING 失败而非按零结算")
    void curveVersioningAndMissingValueNotSettledAsZero() {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 30);
        enroll(ref, "M1", "10");

        PointResponse first = writePoint(ref, "M1", "BASELINE", 0, "8", null);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.corrected()).isFalse();

        // 首次写入携带版本号被拒绝
        assertThatThrownBy(() -> writePoint(ref, "M1", "BASELINE", 1, "8", 3))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("首次写入不能携带");

        // 更正携带错误的前版本号被拒绝
        assertThatThrownBy(() -> writePoint(ref, "M1", "BASELINE", 0, "9", 7))
                .isInstanceOf(DrApiException.class)
                .satisfies(ex -> assertThat(((DrApiException) ex).getCode())
                        .isEqualTo("VERSION_CONFLICT"));

        // 只写了区间 0 的基线，区间 0 实测与区间 1 数据均缺失：冻结必须按缺值失败
        FreezeRequest req = singleMeterFreezeRequest("R-MISSING", 2, "M1", 2, 1, 1);
        assertThatThrownBy(() -> service.freeze(ref, req))
                .isInstanceOf(DrApiException.class)
                .satisfies(ex -> assertThat(((DrApiException) ex).getCode())
                        .isIn("MISSING_ACTUAL", "MISSING_BASELINE"))
                .hasMessageContaining("不得按零值结算");

        // 没有任何冻结或逐区间落账
        Integer freezeCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dr_settlement_freeze", Integer.class);
        Integer intervalCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dr_freeze_interval", Integer.class);
        assertThat(freezeCount).isZero();
        assertThat(intervalCount).isZero();
    }

    // ---------- 交付量封顶与零截断 ----------

    @Test
    @DisplayName("单区间交付量=min(承诺,max(0,基线-实测))，超额封顶、实测高于基线为零")
    void deliveredCappedAndFlooredAtZero() {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 30);
        enroll(ref, "M1", "5"); // 承诺 5

        // 区间0：基线 10、实测 4 -> 削减 6，封顶为 5
        writePoint(ref, "M1", "BASELINE", 0, "10", null);
        writePoint(ref, "M1", "ACTUAL", 0, "4", null);
        // 区间1：基线 3、实测 4 -> 削减 -1，截断为 0
        writePoint(ref, "M1", "BASELINE", 1, "3", null);
        writePoint(ref, "M1", "ACTUAL", 1, "4", null);

        FreezeResponse resp = service.freeze(ref,
                singleMeterFreezeRequest("R-CAP", 2, "M1", 2, 1, 1));
        assertThat(resp.intervals()).hasSize(2);
        assertThat(resp.intervals().get(0).deliveredValue()).isEqualByComparingTo(BD5);
        assertThat(resp.intervals().get(1).deliveredValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.totalDelivered()).isEqualByComparingTo(BD5);
    }

    // ---------- 冻结证据版本冲突与回滚 ----------

    @Test
    @DisplayName("证据版本不匹配整笔失败：无冻结头、无逐区间快照，原始数据版本不变")
    void freezeRollsBackOnEvidenceVersionConflict() {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 60);
        enroll(ref, "M1", "5");
        writePoint(ref, "M1", "BASELINE", 0, "10", null);
        writePoint(ref, "M1", "ACTUAL", 0, "4", null);
        // 实测随后更正到版本 2
        writePoint(ref, "M1", "ACTUAL", 0, "2", 1);

        FreezeRequest stale = singleMeterFreezeRequest("R-STALE", 2, "M1", 1, 1, 1);
        assertThatThrownBy(() -> service.freeze(ref, stale))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("实测证据版本冲突");

        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM dr_settlement_freeze", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM dr_freeze_interval", Integer.class))
                .isZero();
        Integer actualVersion = jdbc.queryForObject(
                "SELECT version FROM dr_curve_point WHERE meter_id='M1' AND data_type='ACTUAL'",
                Integer.class);
        assertThat(actualVersion).isEqualTo(2);

        // 事件版本不匹配同样失败
        FreezeRequest wrongEventVersion = singleMeterFreezeRequest("R-EV", 1, "M1", 1, 1, 2);
        assertThatThrownBy(() -> service.freeze(ref, wrongEventVersion))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("事件版本冲突");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM dr_settlement_freeze", Integer.class))
                .isZero();
    }

    // ---------- 证据完整性 ----------

    @Test
    @DisplayName("冻结证据必须完整：缺电表/多电表/未知电表/重复区间均失败，且不产生任何结算")
    void freezeRequiresCompleteEvidence() {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 30);
        enroll(ref, "M1", "5");
        enroll(ref, "M2", "5"); // 两块电表、两个区间
        for (String meter : List.of("M1", "M2")) {
            for (int idx = 0; idx < 2; idx++) {
                writePoint(ref, meter, "BASELINE", idx, "10", null);
                writePoint(ref, meter, "ACTUAL", idx, "4", null);
            }
        }

        // 缺一块电表 M2
        FreezeRequest missingMeter = new FreezeRequest("R-MM", 3,
                List.of(new MeterEvidence("M1", List.of(
                        new IntervalEvidence(0, 1, 1),
                        new IntervalEvidence(1, 1, 1)))));
        assertThatThrownBy(() -> service.freeze(ref, missingMeter))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("全部已登记电表");

        // 多一块未登记电表 M3
        FreezeRequest unknownMeter = new FreezeRequest("R-UM", 3, List.of(
                new MeterEvidence("M1", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(1, 1, 1))),
                new MeterEvidence("M2", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(1, 1, 1))),
                new MeterEvidence("M3", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(1, 1, 1)))));
        assertThatThrownBy(() -> service.freeze(ref, unknownMeter))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("未登记电表");

        // M2 缺少区间 1（区间集合不完整）
        FreezeRequest missingInterval = new FreezeRequest("R-MI", 3, List.of(
                new MeterEvidence("M1", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(1, 1, 1))),
                new MeterEvidence("M2", List.of(
                        new IntervalEvidence(0, 1, 1)))));
        assertThatThrownBy(() -> service.freeze(ref, missingInterval))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("区间不完整");

        // 同一电表区间重复
        FreezeRequest duplicateInterval = new FreezeRequest("R-DI", 3, List.of(
                new MeterEvidence("M1", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(0, 1, 1),
                        new IntervalEvidence(1, 1, 1))),
                new MeterEvidence("M2", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(1, 1, 1)))));
        assertThatThrownBy(() -> service.freeze(ref, duplicateInterval))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("区间重复");

        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM dr_settlement_freeze", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM dr_freeze_interval", Integer.class))
                .isZero();

        // 冻结后不能再登记新电表
        FreezeRequest good = new FreezeRequest("R-OK", 3, List.of(
                new MeterEvidence("M1", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(1, 1, 1))),
                new MeterEvidence("M2", List.of(
                        new IntervalEvidence(0, 1, 1), new IntervalEvidence(1, 1, 1)))));
        service.freeze(ref, good);
        assertThatThrownBy(() -> enroll(ref, "M3", "5"))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("已冻结");
    }

    // ---------- 并发更正 ----------

    @Test
    @DisplayName("并发更正同一曲线点：仅一个携带前版本号的更正成功，另一个版本冲突")
    void concurrentCorrectionExactlyOneWins() throws Exception {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 60);
        enroll(ref, "M1", "5");
        writePoint(ref, "M1", "BASELINE", 0, "10", null);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    writePoint(ref, "M1", "BASELINE", 0, String.valueOf(20 + seq), 1);
                    success.incrementAndGet();
                } catch (DrApiException ex) {
                    if ("VERSION_CONFLICT".equals(ex.getCode())) {
                        conflict.incrementAndGet();
                    } else {
                        throw new RuntimeException(ex);
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        // 最终版本恰好推进到 2，不存在覆盖丢失之外的异常状态
        Integer version = jdbc.queryForObject(
                "SELECT version FROM dr_curve_point WHERE meter_id='M1' AND data_type='BASELINE'",
                Integer.class);
        assertThat(version).isEqualTo(2);
    }

    // ---------- 请求指纹：重放与同键异参 ----------

    @Test
    @DisplayName("相同 requestId 与完整参数指纹重放返回首次结果；同键异参（版本变化）返回 409")
    void fingerprintReplayAndConflict() {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 60);
        enroll(ref, "M1", "5");
        writePoint(ref, "M1", "BASELINE", 0, "10", null);
        writePoint(ref, "M1", "ACTUAL", 0, "4", null);

        FreezeRequest first = singleMeterFreezeRequest("R-FP", 2, "M1", 1, 1, 1);
        FreezeResponse saved = service.freeze(ref, first);
        assertThat(saved.totalDelivered()).isEqualByComparingTo(BD5);

        // 完全相同参数重放：返回首次冻结结果（同 ID、同指纹）
        FreezeResponse replay = service.freeze(ref,
                singleMeterFreezeRequest("R-FP", 2, "M1", 1, 1, 1));
        assertThat(replay.freezeId()).isEqualTo(saved.freezeId());
        assertThat(replay.fingerprint()).isEqualTo(saved.fingerprint());
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM dr_settlement_freeze", Integer.class))
                .isEqualTo(1);

        // 先把实测更正到版本 2
        writePoint(ref, "M1", "ACTUAL", 0, "3", 1);
        // 同 requestId 但携带新版本号：指纹不同，409
        FreezeRequest sameKeyDifferentParam = singleMeterFreezeRequest("R-FP", 2, "M1", 1, 1, 2);
        assertThatThrownBy(() -> service.freeze(ref, sameKeyDifferentParam))
                .isInstanceOf(DrApiException.class)
                .satisfies(ex -> assertThat(((DrApiException) ex).getStatus().value()).isEqualTo(409))
                .hasMessageContaining("不同的冻结参数");

        // 不同 requestId 也不能再次冻结同一事件
        assertThatThrownBy(() -> service.freeze(ref,
                singleMeterFreezeRequest("R-OTHER", 2, "M1", 1, 1, 2)))
                .isInstanceOf(DrApiException.class)
                .hasMessageContaining("至多冻结一次");
    }

    // ---------- 冻结后调整 ----------

    @Test
    @DisplayName("冻结后原始数据可更正，但只形成独立调整建议，冻结快照与总交付量不变")
    void correctionAfterFreezeCreatesAdjustmentOnly() {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 60);
        enroll(ref, "M1", "5");
        writePoint(ref, "M1", "BASELINE", 0, "10", null);
        writePoint(ref, "M1", "ACTUAL", 0, "4", null); // 冻结交付 = min(5, 10-4)=5

        FreezeResponse frozen = service.freeze(ref,
                singleMeterFreezeRequest("R-ADJ", 2, "M1", 1, 1, 1));
        assertThat(frozen.totalDelivered()).isEqualByComparingTo(BD5);

        // 实测由 4 更正为 1（携带前版本 1）：建议交付 min(5, 10-1)=5，封顶后无差额
        writePoint(ref, "M1", "ACTUAL", 0, "1", 1);
        List<AdjustmentResponse> adjustments = service.listAdjustments(ref);
        assertThat(adjustments).hasSize(1);
        AdjustmentResponse adj = adjustments.get(0);
        assertThat(adj.dataType()).isEqualTo("ACTUAL");
        assertThat(adj.frozenVersion()).isEqualTo(1);
        assertThat(adj.correctedVersion()).isEqualTo(2);
        assertThat(adj.suggestedIntervalDelivered()).isEqualByComparingTo(BD5);
        assertThat(adj.suggestedDelta()).isEqualByComparingTo(BigDecimal.ZERO);

        // 再次更正（1->3，版本3）：建议更新为同一 OPEN 记录，按 10-3=7 封顶 5，仍无差额
        writePoint(ref, "M1", "ACTUAL", 0, "3", 2);
        adjustments = service.listAdjustments(ref);
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).correctedVersion()).isEqualTo(3);

        // 基线下调到 6（前版本 1）：该建议按更正值与冻结快照实测值 4 独立重算，
        // min(5,6-4)=2，建议退 3；不与实测类型的未决更正混合。
        writePoint(ref, "M1", "BASELINE", 0, "6", 1);
        adjustments = service.listAdjustments(ref);
        assertThat(adjustments).hasSize(2);
        AdjustmentResponse baselineAdj = adjustments.stream()
                .filter(a -> "BASELINE".equals(a.dataType())).findFirst().orElseThrow();
        assertThat(baselineAdj.suggestedIntervalDelivered()).isEqualByComparingTo(BD2);
        assertThat(baselineAdj.suggestedDelta()).isEqualByComparingTo(new BigDecimal("-3.000000"));

        // 冻结证据保持不变：仍按版本 1 的值计算，总额 5
        FreezeResponse evidence = service.getFreezeEvidence(ref);
        assertThat(evidence.freezeId()).isEqualTo(frozen.freezeId());
        assertThat(evidence.totalDelivered()).isEqualByComparingTo(BD5);
        assertThat(evidence.intervals().get(0).baselineVersion()).isEqualTo(1);
        assertThat(evidence.intervals().get(0).actualVersion()).isEqualTo(1);
        assertThat(evidence.intervals().get(0).actualValue()).isEqualByComparingTo(BD4);
    }

    // ---------- 冻结与更正并发：串行化后结果自洽 ----------

    @Test
    @DisplayName("冻结与并发更正竞争：最终要么冻结锁定旧版本，要么冻结失败且无部分落账")
    void concurrentFreezeAndCorrectionKeepsConsistency() throws Exception {
        String ref = uniqueEvent();
        createEvent(ref, T0, T1, 60);
        enroll(ref, "M1", "5");
        writePoint(ref, "M1", "BASELINE", 0, "10", null);
        writePoint(ref, "M1", "ACTUAL", 0, "4", null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new java.util.ArrayList<>();

        // 冻结方按版本 1 证据冻结；更正方把实测 4 -> 2 推进到版本 2。
        futures.add(pool.submit(() -> {
            start.await();
            return service.freeze(ref, singleMeterFreezeRequest("R-RACE", 2, "M1", 1, 1, 1));
        }));
        futures.add(pool.submit(() -> {
            start.await();
            return writePoint(ref, "M1", "ACTUAL", 0, "2", 1);
        }));
        start.countDown();

        DrApiException freezeError = null;
        FreezeResponse freezeResult = null;
        for (Future<Object> f : futures) {
            try {
                Object result = f.get(10, TimeUnit.SECONDS);
                if (result instanceof FreezeResponse fr) {
                    freezeResult = fr;
                }
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof DrApiException de) {
                    freezeError = de;
                } else {
                    throw new RuntimeException(cause);
                }
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        Integer freezeRows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dr_settlement_freeze", Integer.class);
        Integer snapshotRows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dr_freeze_interval", Integer.class);

        if (freezeResult != null) {
            // 冻结先提交：锁定版本 1 快照（实测 4，交付 5）；更正随后形成调整建议
            assertThat(freezeError).isNull();
            assertThat(freezeRows).isEqualTo(1);
            assertThat(snapshotRows).isEqualTo(1);
            FreezeResponse.IntervalResult snap = freezeResult.intervals().get(0);
            assertThat(snap.actualVersion()).isEqualTo(1);
            assertThat(snap.actualValue()).isEqualByComparingTo(BD4);
            assertThat(service.listAdjustments(ref)).hasSize(1);
        } else {
            // 更正先提交：冻结证据版本冲突整笔失败，无任何部分结算
            assertThat(freezeError).isNotNull();
            assertThat(freezeError.getCode()).isEqualTo("EVIDENCE_VERSION_CONFLICT");
            assertThat(freezeRows).isZero();
            assertThat(snapshotRows).isZero();
            // 携带新版本 2 重新冻结可成功，快照实测值为更正值 2
            FreezeResponse retried = service.freeze(ref,
                    singleMeterFreezeRequest("R-RACE-RETRY", 2, "M1", 1, 1, 2));
            assertThat(retried.intervals().get(0).actualValue()).isEqualByComparingTo(BD2);
        }
    }
}
