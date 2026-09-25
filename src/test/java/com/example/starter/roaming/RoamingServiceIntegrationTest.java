package com.example.starter.roaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.roaming.service.ServiceResult;
import com.example.starter.roaming.testsupport.MutableClock;
import com.example.starter.roaming.web.dto.AuthRequest;
import com.example.starter.roaming.web.dto.AuthResponse;
import com.example.starter.roaming.web.dto.BatchResponse;
import com.example.starter.roaming.web.dto.FreezeRequest;
import com.example.starter.roaming.web.dto.MeterRequest;
import com.example.starter.roaming.web.dto.MeterResponse;
import com.example.starter.roaming.web.dto.SealRequest;
import com.example.starter.roaming.web.dto.StopRequest;
import com.example.starter.roaming.web.dto.StopResponse;
import com.example.starter.roaming.web.dto.WriteOffRequest;
import com.example.starter.roaming.web.dto.WriteOffResponse;

/**
 * 基于嵌入式 H2（MODE=MySQL）的业务、事务与并发集成测试。
 */
@SpringBootTest
class RoamingServiceIntegrationTest {

    static final Instant FIXED = Instant.parse("2026-01-15T10:00:00Z");
    static final ZoneOffset UTC = ZoneOffset.UTC;

    /** 合成车辆（归属 OP-A，费率 1.2 元/kWh）与桩（归属 OP-B，费率 0.85 元/kWh）。 */
    static final String FP_A = "FP-AAA111-CERT-SHA256-0000000000000000000000000001";
    static final String FP_B = "FP-BBB222-CERT-SHA256-0000000000000000000000000002";
    static final String CP_B = "CP-0001";
    static final LocalDate DAY = LocalDate.parse("2026-01-15");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(FIXED);
        }
    }

    @Autowired
    private com.example.starter.roaming.service.RoamingService service;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        clock.setInstant(FIXED);
        jdbc.update("DELETE FROM discrepancy");
        jdbc.update("DELETE FROM clearing_detail");
        jdbc.update("DELETE FROM clearing_batch");
        jdbc.update("DELETE FROM meter_snapshot");
        jdbc.update("DELETE FROM charging_session");
        jdbc.update("DELETE FROM idempotent_request");
    }

    private OffsetDateTime odt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, UTC);
    }

    private AuthResponse authorize(String rid, String fp, String point, Instant at) {
        ServiceResult result = service.authorize(new AuthRequest(rid, fp, point, odt(at)));
        assertThat(result.status()).isEqualTo(200);
        return (AuthResponse) result.body();
    }

    // ---------------- 授权 5 分钟边界 ----------------

    @Test
    void auth_within_five_minutes_replays_first_result_after_expiry_rejects() {
        AuthResponse first = authorize("rid-auth-1", FP_A, CP_B, FIXED);
        assertThat(first.cached()).isFalse();
        assertThat(first.authorized()).isTrue();
        assertThat(first.expiresAt()).isEqualTo(Instant.parse("2026-01-15T10:05:00Z"));

        // 有效期内（含第 5 分钟整）重复请求返回首次授权结果。
        clock.setInstant(FIXED.plusSeconds(4 * 60 + 59));
        AuthResponse before = authorize("rid-auth-2", FP_A, CP_B, FIXED);
        assertThat(before.cached()).isTrue();
        assertThat(before.sessionNo()).isEqualTo(first.sessionNo());

        clock.setInstant(FIXED.plusSeconds(5 * 60));
        AuthResponse atBoundary = authorize("rid-auth-3", FP_A, CP_B, FIXED);
        assertThat(atBoundary.cached()).isTrue();
        assertThat(atBoundary.sessionNo()).isEqualTo(first.sessionNo());

        // 超期不再回放：进行中的会话冲突，需重新授权（先结束既有会话）。
        clock.setInstant(FIXED.plusSeconds(5 * 60 + 1));
        ServiceResult expired = service.authorize(
                new AuthRequest("rid-auth-4", FP_A, CP_B, odt(FIXED)));
        assertThat(expired.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM charging_session", Integer.class)).isEqualTo(1);
    }

    // ---------------- 同车唯一会话（并发） ----------------

    @Test
    void concurrent_auth_same_vehicle_same_time_only_one_session() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<ServiceResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    ServiceResult r = service.authorize(new AuthRequest(
                            "rid-parallel-" + idx, FP_A, CP_B, odt(FIXED)));
                    done.countDown();
                    return r;
                }));
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

            Set<String> sessionNos = new HashSet<>();
            for (Future<ServiceResult> f : futures) {
                ServiceResult r = f.get(5, TimeUnit.SECONDS);
                assertThat(r.status()).isEqualTo(200);
                sessionNos.add(((AuthResponse) r.body()).sessionNo());
            }
            // 唯一会话成功：所有并发请求看到同一会话。
            assertThat(sessionNos).hasSize(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM charging_session", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void second_auth_within_window_replays_first_even_with_new_request_time() {
        AuthResponse first = authorize("rid-a", FP_A, CP_B, FIXED);
        // 不同请求时刻但仍在首个授权 5 分钟窗口内：重复请求回放首次授权结果。
        ServiceResult second = service.authorize(new AuthRequest("rid-b", FP_A, CP_B,
                OffsetDateTime.parse("2026-01-15T10:01:00Z")));
        assertThat(second.status()).isEqualTo(200);
        AuthResponse body = (AuthResponse) second.body();
        assertThat(body.cached()).isTrue();
        assertThat(body.sessionNo()).isEqualTo(first.sessionNo());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM charging_session", Integer.class)).isEqualTo(1);
    }

    @Test
    void unknown_vehicle_and_point_return_404_and_are_idempotent() {
        ServiceResult v = service.authorize(new AuthRequest("rid-unk-v", "FP-NOPE", CP_B,
                odt(FIXED)));
        assertThat(v.status()).isEqualTo(404);
        // 业务错误也按 requestId 幂等回放。
        ServiceResult replay = service.authorize(new AuthRequest("rid-unk-v", "FP-NOPE", CP_B,
                odt(FIXED)));
        assertThat(replay.status()).isEqualTo(404);

        ServiceResult p = service.authorize(new AuthRequest("rid-unk-p", FP_A, "CP-X",
                odt(FIXED)));
        assertThat(p.status()).isEqualTo(404);
    }

    // ---------------- requestId 幂等冲突 ----------------

    @Test
    void same_request_id_with_different_params_returns_409_and_creates_nothing() {
        ServiceResult first = service.authorize(new AuthRequest("rid-same", FP_A, CP_B,
                odt(FIXED)));
        assertThat(first.status()).isEqualTo(200);

        ServiceResult conflict = service.authorize(new AuthRequest("rid-same", FP_A, "CP-0002",
                odt(FIXED)));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM charging_session", Integer.class)).isEqualTo(1);
    }

    // ---------------- 快照去重与乱序归位 ----------------

    @Test
    void meter_snapshots_deduplicate_and_reorder_into_place() {
        AuthResponse auth = authorize("rid-m-auth", FP_A, CP_B, FIXED);
        String sessionNo = auth.sessionNo();

        // 乱序到达：先传序号 3。
        MeterResponse m3 = meter("rid-m-3", sessionNo, 3, 3000, FIXED.plusSeconds(30));
        assertThat(m3.duplicate()).isFalse();
        assertThat(m3.lastMeterSeq()).isEqualTo(3);
        assertThat(m3.totalEnergyWh()).isEqualTo(3000);

        // 序号 1 晚到：归位但累计取最大序号读数，不变成 1000。
        MeterResponse m1 = meter("rid-m-1", sessionNo, 1, 1000, FIXED.plusSeconds(10));
        assertThat(m1.duplicate()).isFalse();
        assertThat(m1.lastMeterSeq()).isEqualTo(3);
        assertThat(m1.totalEnergyWh()).isEqualTo(3000);

        MeterResponse m2 = meter("rid-m-2", sessionNo, 2, 2000, FIXED.plusSeconds(20));
        assertThat(m2.totalEnergyWh()).isEqualTo(3000);

        // 重复快照（即使使用新 requestId）：duplicate=true，不重计、插入行数不变。
        MeterResponse dup = meter("rid-m-1-dup", sessionNo, 1, 1000, FIXED.plusSeconds(10));
        assertThat(dup.duplicate()).isTrue();
        assertThat(dup.totalEnergyWh()).isEqualTo(3000);

        // 同 requestId 异参：409 且无新快照。
        ServiceResult clash = service.uploadMeter(new MeterRequest("rid-m-3", sessionNo, 9,
                9000L, odt(FIXED.plusSeconds(90))));
        assertThat(clash.status()).isEqualTo(409);

        MeterResponse m4 = meter("rid-m-4", sessionNo, 4, 4000, FIXED.plusSeconds(40));
        assertThat(m4.totalEnergyWh()).isEqualTo(4000);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter_snapshot WHERE session_no = ?",
                Integer.class, sessionNo)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT total_energy_wh FROM charging_session WHERE session_no = ?",
                Long.class, sessionNo)).isEqualTo(4000L);
    }

    private MeterResponse meter(String rid, String sessionNo, int seq, long wh, Instant at) {
        ServiceResult r = service.uploadMeter(new MeterRequest(rid, sessionNo, seq, wh, odt(at)));
        assertThat(r.status()).isEqualTo(200);
        return (MeterResponse) r.body();
    }

    // ---------------- 清分合计一致 + 封存终态 ----------------

    @Test
    void clearing_totals_equal_details_and_seal_is_terminal() {
        AuthResponse auth = authorize("rid-c-auth", FP_A, CP_B, FIXED);
        String sessionNo = auth.sessionNo();
        meter("rid-c-m1", sessionNo, 1, 10_000, FIXED.plusSeconds(60));
        meter("rid-c-m2", sessionNo, 2, 12_000, FIXED.plusSeconds(120));

        ServiceResult stopped = service.stopSession(new StopRequest("rid-c-stop", sessionNo,
                OffsetDateTime.parse("2026-01-15T11:00:00Z")));
        StopResponse stop = (StopResponse) stopped.body();
        assertThat(stop.status()).isEqualTo("ENDED");
        assertThat(stop.totalEnergyWh()).isEqualTo(12_000);

        BatchResponse frozen = freeze("rid-c-freeze", DAY);
        assertThat(frozen.status()).isEqualTo("FROZEN");
        assertThat(frozen.sessionCount()).isEqualTo(1);
        assertThat(frozen.details()).hasSize(1);
        assertThat(frozen.totalEnergyWh()).isEqualTo(12_000);
        // 应收按车辆归属 OP-A 1.2 元/kWh = 14.4；应付按桩归属 OP-B 0.85 = 10.2。
        assertThat(frozen.totalReceivable()).isEqualByComparingTo("14.4000");
        assertThat(frozen.totalPayable()).isEqualByComparingTo("10.2000");
        // 批内会话合计必须等于明细之和。
        assertThat(frozen.details()).allSatisfy(d -> {
            assertThat(d.receivableAmount()).isEqualByComparingTo("14.4000");
            assertThat(d.payableAmount()).isEqualByComparingTo("10.2000");
        });

        BatchResponse sealed = seal("rid-c-seal", frozen.batchNo());
        assertThat(sealed.status()).isEqualTo("SEALED");
        assertThat(sealed.sealedAt()).isNotNull();
        Instant sealedAt = sealed.sealedAt();

        // 重复封存（新 requestId）返回首次清分单，不重新计算。
        clock.setInstant(FIXED.plusSeconds(3600));
        BatchResponse sealedAgain = seal("rid-c-seal-2", frozen.batchNo());
        assertThat(sealedAgain.status()).isEqualTo("SEALED");
        assertThat(sealedAgain.sealedAt()).isEqualTo(sealedAt);
        assertThat(sealedAgain.totalReceivable()).isEqualByComparingTo("14.4000");
        assertThat(sealedAgain.totalPayable()).isEqualByComparingTo("10.2000");
    }

    @Test
    void stop_is_idempotent_and_does_not_double_finish() {
        AuthResponse auth = authorize("rid-s-auth", FP_A, CP_B, FIXED);
        ServiceResult r1 = service.stopSession(new StopRequest("rid-s-stop", auth.sessionNo(),
                OffsetDateTime.parse("2026-01-15T11:00:00Z")));
        assertThat(r1.status()).isEqualTo(200);
        assertThat(((StopResponse) r1.body()).cached()).isFalse();

        ServiceResult r2 = service.stopSession(new StopRequest("rid-s-stop-2",
                auth.sessionNo(), OffsetDateTime.parse("2026-01-15T11:00:00Z")));
        StopResponse body = (StopResponse) r2.body();
        assertThat(body.cached()).isTrue();
        assertThat(body.status()).isEqualTo("ENDED");
    }

    // ---------------- 封存后迟到快照：挂账与唯一核销 ----------------

    @Test
    void late_meter_after_seal_creates_discrepancy_with_single_write_off() {
        AuthResponse auth = authorize("rid-d-auth", FP_A, CP_B, FIXED);
        String sessionNo = auth.sessionNo();
        meter("rid-d-m1", sessionNo, 1, 5_000, FIXED.plusSeconds(60));
        service.stopSession(new StopRequest("rid-d-stop", sessionNo,
                OffsetDateTime.parse("2026-01-15T11:00:00Z")));
        BatchResponse batch = freeze("rid-d-freeze", DAY);
        seal("rid-d-seal", batch.batchNo());

        // 封存后迟到快照 → 差异挂账，批次合计不变。
        MeterResponse late = meter("rid-d-late", sessionNo, 2, 6_500,
                Instant.parse("2026-01-16T08:00:00Z"));
        assertThat(late.discrepancyNo()).isNotBlank();
        assertThat(late.batchStatus()).isEqualTo("SEALED");
        Long batchIdOfSession = jdbc.queryForObject(
                "SELECT batch_id FROM charging_session WHERE session_no = ?",
                Long.class, sessionNo);
        assertThat(jdbc.queryForObject(
                "SELECT total_energy_wh FROM clearing_batch WHERE id = ?",
                Long.class, batchIdOfSession)).isEqualTo(5_000L);

        // 同一快照再次上传：挂账不重复生成。
        MeterResponse lateDup = meter("rid-d-late-dup", sessionNo, 2, 6_500,
                Instant.parse("2026-01-16T08:05:00Z"));
        assertThat(lateDup.discrepancyNo()).isEqualTo(late.discrepancyNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy", Integer.class))
                .isEqualTo(1);

        // 人工核销，留痕。
        ServiceResult wo = service.writeOff(new WriteOffRequest("rid-wo",
                late.discrepancyNo(), "operator-zhang", "跨日电量差异已人工核对，补登账单"));
        assertThat(wo.status()).isEqualTo(200);
        WriteOffResponse woBody = (WriteOffResponse) wo.body();
        assertThat(woBody.status()).isEqualTo("WRITTEN_OFF");
        assertThat(woBody.writeOffBy()).isEqualTo("operator-zhang");

        // 同 requestId 回放：返回首次核销结果。
        ServiceResult woReplay = service.writeOff(new WriteOffRequest("rid-wo",
                late.discrepancyNo(), "operator-zhang", "跨日电量差异已人工核对，补登账单"));
        assertThat(woReplay.status()).isEqualTo(200);

        // 同一挂账第二次核销（新 requestId）：409，核销留痕保持首次。
        ServiceResult wo2 = service.writeOff(new WriteOffRequest("rid-wo-2",
                late.discrepancyNo(), "operator-li", "再次尝试核销"));
        assertThat(wo2.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT write_off_by FROM discrepancy WHERE discrepancy_no = ?",
                String.class, late.discrepancyNo())).isEqualTo("operator-zhang");
    }

    // ---------------- 会话结束与封存并发：归属唯一批次 ----------------

    @Test
    void concurrent_stop_and_freeze_assigns_session_to_exactly_one_batch() throws Exception {
        AuthResponse auth = authorize("rid-race-auth", FP_A, CP_B, FIXED);
        String sessionNo = auth.sessionNo();
        meter("rid-race-m", sessionNo, 1, 7_777, FIXED.plusSeconds(60));

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?> stopFuture = pool.submit(() -> {
                awaitQuiet(barrier);
                service.stopSession(new StopRequest("rid-race-stop", sessionNo,
                        OffsetDateTime.parse("2026-01-15T11:00:00Z")));
            });
            Future<?> freezeFuture = pool.submit(() -> {
                awaitQuiet(barrier);
                service.freezeBatch(new FreezeRequest("rid-race-freeze", DAY));
            });
            stopFuture.get(30, TimeUnit.SECONDS);
            freezeFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 再冻结一次：若上一批先于结束提交，会话应进入且仅进入下一批。
        BatchResponse second = freeze("rid-race-freeze-2", DAY);

        // 会话只可能出现在一个批次的明细中：不重不漏。
        Integer appearances = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clearing_detail WHERE session_no = ?",
                Integer.class, sessionNo);
        assertThat(appearances).isEqualTo(1);
        Long batchId = jdbc.queryForObject(
                "SELECT batch_id FROM charging_session WHERE session_no = ?",
                Long.class, sessionNo);
        assertThat(batchId).isNotNull();
        Integer batchesForDay = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clearing_batch WHERE settlement_day = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertThat(batchesForDay).isBetween(1, 2);

        // 归批批次合计与明细一致。
        BatchResponse containing;
        if (second.details().stream().anyMatch(d -> d.sessionNo().equals(sessionNo))) {
            assertThat(second.sessionCount()).isEqualTo(1);
            assertThat(second.totalEnergyWh()).isEqualTo(7_777);
            containing = second;
        } else {
            containing = freeze("rid-race-freeze-replay", DAY);
            assertThat(containing.details())
                    .noneMatch(d -> d.sessionNo().equals(sessionNo));
        }
        // RowCallbackHandler 回调时结果集已定位到归批行。
        jdbc.query("SELECT batch_no, total_energy_wh, session_count FROM clearing_batch "
                + "WHERE id = ?", rs -> {
            assertThat(rs.getString("batch_no")).startsWith("CB-");
            assertThat(rs.getLong("total_energy_wh")).isEqualTo(7_777L);
            assertThat(rs.getInt("session_count")).isEqualTo(1);
        }, batchId);
    }

    // ---------------- 封存与迟到计量并发：互斥，结果唯一 ----------------

    @Test
    void concurrent_seal_and_late_meter_is_mutually_exclusive() throws Exception {
        AuthResponse auth = authorize("rid-ms-auth", FP_A, CP_B, FIXED);
        String sessionNo = auth.sessionNo();
        meter("rid-ms-m1", sessionNo, 1, 8_000, FIXED.plusSeconds(60));
        service.stopSession(new StopRequest("rid-ms-stop", sessionNo,
                OffsetDateTime.parse("2026-01-15T11:00:00Z")));
        BatchResponse batch = freeze("rid-ms-freeze", DAY);

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<ServiceResult> results = new ArrayList<>();
        try {
            List<Future<ServiceResult>> futures = IntStream.range(0, 2).<Future<ServiceResult>>mapToObj(
                    i -> pool.submit(() -> {
                        awaitQuiet(barrier);
                        if (i == 0) {
                            return service.sealBatch(new SealRequest("rid-ms-seal",
                                    batch.batchNo()));
                        }
                        return service.uploadMeter(new MeterRequest("rid-ms-m2", sessionNo, 2,
                                9_000L, odt(Instant.parse("2026-01-15T12:00:00Z"))));
                    })).toList();
            for (Future<ServiceResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long batchEnergy = jdbc.queryForObject(
                "SELECT total_energy_wh FROM clearing_batch WHERE batch_no = ?",
                Long.class, batch.batchNo());
        Integer discrepancyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy WHERE session_no = ?",
                Integer.class, sessionNo);

        boolean sealedWithNewEnergy = batchEnergy == 9_000L && discrepancyCount == 0;
        boolean sealedWithoutAndDiscrepancy = batchEnergy == 8_000L && discrepancyCount == 1;
        // 二者必居其一，不能既计入封存单又挂账。
        assertThat(sealedWithNewEnergy || sealedWithoutAndDiscrepancy).isTrue();
    }

    // ---------------- H2 唯一约束边界 ----------------

    @Test
    void h2_unique_constraint_blocks_duplicate_fingerprint_time_and_snapshot_seq() {
        authorize("rid-u-auth", FP_A, CP_B, FIXED);
        // 直接绕过服务层插入同车同刻会话，验证数据库唯一约束。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO charging_session (session_no, cert_fingerprint, point_id, "
                        + "home_operator, charging_operator, auth_at, start_at, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'OP-A', 'OP-B', ?, ?, "
                        + "'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))",
                "CS-DUP", FP_A, CP_B, FIXED.atOffset(UTC).toLocalDateTime(),
                FIXED.atOffset(UTC).toLocalDateTime()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    private BatchResponse freeze(String rid, LocalDate day) {
        ServiceResult r = service.freezeBatch(new FreezeRequest(rid, day));
        assertThat(r.status()).isEqualTo(200);
        return (BatchResponse) r.body();
    }

    private BatchResponse seal(String rid, String batchNo) {
        ServiceResult r = service.sealBatch(new SealRequest(rid, batchNo));
        assertThat(r.status()).isEqualTo(200);
        return (BatchResponse) r.body();
    }

    private static void awaitQuiet(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }
}
