package com.example.starter.wb.service;

import com.example.starter.wb.AbstractIntegrationTest;
import com.example.starter.wb.dto.AssemblyPlanView;
import com.example.starter.wb.dto.AssemblyRequest;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CloseRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LoadItem;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.dto.UldItem;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.exception.UnprocessableException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 集装器装配 H2 事务/并发测试：整版校验、力矩精确求和、版本匹配与乐观并发、
 * 跨航班抢同器唯一成功、改图释放占用并使旧复核失效、LMC/加油后关闭拦截与释放、幂等回放。
 */
class AssemblyServiceIntegrationTest extends AbstractIntegrationTest {

    private static final long F1 = 1L;
    private static final long F2 = 2L;

    /** 货运力矩=3000×12700 + 1000×20828 = 58,928,000 kg·mm（总重含皮重，整数毫米力臂）。 */
    private static final long EXPECTED_MOMENT_KGMM = 58_928_000L;

    @Autowired
    private WeightBalanceService wb;
    @Autowired
    private AssemblyService assembly;

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static LocalDateTime occurredAt() {
        return LocalDateTime.of(2026, 9, 28, 8, 30, 0);
    }

    @BeforeAll
    static void ensureSecondFlight(@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc) {
        // 合成第二个航班，同机型，用于跨航班占用与并发抢同器；MERGE 保证重复执行幂等。
        jdbc.execute("""
                MERGE INTO flight (id, flight_no, origin, destination, aircraft_id, scheduled_time, created_at)
                KEY (id) VALUES
                (2, 'CA5678', 'PEK', 'CAN', 1, TIMESTAMP '2026-09-26 14:00:00', TIMESTAMP '2026-09-26 10:00:00')
                """);
    }

    // 旅客 60@80@PAX_F(600)=4800；CARGO 29@100@CARGO_F(500)=2900；BAGGAGE 9@100@BAG_A(820)=900。
    private static List<LoadItem> manifestItems() {
        return List.of(
                new LoadItem("PASSENGER", "PAX_F", 60, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 29, new BigDecimal("100")),
                new LoadItem("BAGGAGE", "BAG_A", 9, new BigDecimal("100")));
    }

    /** ULD_FWD_1：皮 100+净 2900=总 3000（恰达舱位限额）；ULD_AFT_1：皮 100+净 900=总 1000；Σ净重 3800。 */
    private static List<UldItem> uldItems() {
        return List.of(
                new UldItem("AKE-0001", "AKE", "ULD_FWD_1", 100, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 100, 900));
    }

    private void toLoaded(long flightId) {
        wb.createManifest(new CreateManifestRequest(rid(), flightId, new BigDecimal("5000"), "lc001", manifestItems()));
        wb.checkin(flightId, new CheckinRequest(rid(), manifestItems(), new BigDecimal("5000"), "agent01"));
        wb.confirmLoad(flightId, new StageRequest(rid(), "loader01"));
    }

    private AssemblyRequest assemblyRequest(int expectedVersion, List<UldItem> items) {
        return new AssemblyRequest(rid(), expectedVersion, occurredAt(), "lc001", items);
    }

    private void reviewBoth(long flightId) {
        wb.review(flightId, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        wb.review(flightId, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
    }

    // --------------------------------------------------------------- 主流程

    @Test
    void submitAssemblyValidatesWeightMomentAndLinksManifest() {
        toLoaded(F1);
        AssemblyPlanView v1 = assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));

        assertThat(v1.planVersion()).isEqualTo(1);
        assertThat(v1.manifestVersion()).isEqualTo(1);
        assertThat(v1.active()).isTrue();
        assertThat(v1.released()).isFalse();
        assertThat(v1.cargoPayloadKg()).isEqualByComparingTo("3800.00");
        assertThat(v1.fuelKg()).isEqualByComparingTo("5000.00");
        // 总重（含皮重）×整数毫米力臂精确求和
        assertThat(v1.cargoMomentKgmm()).isEqualTo(EXPECTED_MOMENT_KGMM);
        assertThat(v1.confirmedRoles()).isEmpty();
        assertThat(v1.manifestStatus()).isEqualTo("LOADED");
        assertThat(v1.items()).hasSize(2);
        assertThat(v1.items().get(0).grossKg()).isEqualTo(3000);
        assertThat(v1.items().get(0).armMm()).isEqualTo(12700);

        Long manifestId = v1.manifestId();
        Integer open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE flight_id=1 AND open_flag=0", Integer.class);
        assertThat(open).isEqualTo(2);
        Integer activePlans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assembly_plan WHERE flight_id=1 AND active_flag=1", Integer.class);
        assertThat(activePlans).isEqualTo(1);
        assertThat(manifestId).isNotNull();
    }

    @Test
    void grossExactlyAtPositionLimitIsLegalAndTareOnlyAddsMoment() {
        toLoaded(F1);
        // 总重恰 3000 = ULD_FWD_1 限额，合法
        AssemblyPlanView v = assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
        assertThat(v.cargoMomentKgmm()).isEqualTo(EXPECTED_MOMENT_KGMM);
        // 皮重力矩进入重心复算但总货重只算净重：业载仍为 3800
        assertThat(v.cargoPayloadKg()).isEqualByComparingTo("3800.00");
    }

    @Test
    void activeAndHistoryQueriesAreReadable() {
        toLoaded(F1);
        assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
        AssemblyPlanView active = assembly.getActivePlan(F1);
        assertThat(active.planVersion()).isEqualTo(1);

        // 改一版图后历史版本只读保留
        assembly.submitAssembly(F1, assemblyRequest(1, uldItems()));
        List<AssemblyPlanView> all = assembly.listPlans(F1);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).planVersion()).isEqualTo(1);
        assertThat(all.get(0).active()).isFalse();
        assertThat(all.get(1).planVersion()).isEqualTo(2);
        assertThat(all.get(1).active()).isTrue();
    }

    @Test
    void getActivePlanWithoutAnyReturns404() {
        toLoaded(F1);
        assertThatThrownBy(() -> assembly.getActivePlan(F1))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------- 失败分支

    @Test
    void cargoWeightMismatchRejectsWholePlan() {
        toLoaded(F1);
        // 第二器净重改成 901：Σ净重 3801 ≠ 舱单货运业载 3800，整版拒绝，不写图、不占器
        List<UldItem> bad = List.of(
                new UldItem("AKE-0001", "AKE", "ULD_FWD_1", 100, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 100, 901));
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, bad)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("货运业载");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uld_occupancy", Integer.class)).isZero();
    }

    @Test
    void duplicateUldNumberAndDuplicatePositionRejectPlan() {
        toLoaded(F1);
        List<UldItem> dupNo = List.of(
                new UldItem("AKE-DUP", "AKE", "ULD_FWD_1", 0, 2000),
                new UldItem("AKE-DUP", "AKE", "ULD_AFT_1", 0, 1800));
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, dupNo)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("重复");

        List<UldItem> dupPos = List.of(
                new UldItem("AKE-0001", "AKE", "ULD_FWD_1", 0, 2000),
                new UldItem("AKE-0002", "AKE", "ULD_FWD_1", 0, 1800));
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, dupPos)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("同舱位最多一器");
    }

    @Test
    void grossOverPositionLimitRejectsPlan() {
        toLoaded(F1);
        // 皮 200 + 净 2900 = 3100 > 3000
        List<UldItem> over = List.of(
                new UldItem("AKE-0001", "AKE", "ULD_FWD_1", 200, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 0, 900));
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, over)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("限额");
    }

    @Test
    void disallowedUldTypeRejectsPlan() {
        toLoaded(F1);
        // ULD_FWD_1 仅允许 AKE,AKH，PAG 不允许
        List<UldItem> badType = List.of(
                new UldItem("PAG-0001", "PAG", "ULD_FWD_1", 0, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 0, 900));
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, badType)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    void unknownPositionIsBadRequest() {
        toLoaded(F1);
        List<UldItem> badPos = List.of(
                new UldItem("AKE-0001", "AKE", "ULD_NOPE", 0, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 0, 900));
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, badPos)))
                .isInstanceOf(com.example.starter.wb.exception.BadRequestException.class);
    }

    @Test
    void submitBeforeLoadConfirmationIsRejected() {
        wb.createManifest(new CreateManifestRequest(rid(), F1, new BigDecimal("5000"), "lc001", manifestItems()));
        // PRELIMINARY 阶段不能提交装配图
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, uldItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("装载确认");
    }

    @Test
    void staleExpectedVersionConflictsAndNothingChanges() {
        toLoaded(F1);
        assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
        // 当前已是版本 1，仍按 0 提交 → 409
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(0, uldItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本不匹配");
        Integer plans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assembly_plan WHERE flight_id=1", Integer.class);
        assertThat(plans).isEqualTo(1);
    }

    // -------------------------------------------------- 占用 / 改图 / 复核失效

    @Test
    void sameUldCannotOccupyTwoOpenFlights() {
        toLoaded(F1);
        toLoaded(F2);
        assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
        // 航班 2 试图占用同样编号的集装器 → 409
        assertThatThrownBy(() -> assembly.submitAssembly(F2, assemblyRequest(0, uldItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已被航班 1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE flight_id=2 AND open_flag=0", Integer.class)).isZero();
    }

    @Test
    void resubmitReleasesOldOccupanciesAndInvalidatesReviews() {
        toLoaded(F1);
        AssemblyPlanView v1 = assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
        reviewBoth(F1);
        assertThat(wb.getActive(F1).status()).isEqualTo("REVIEWED");

        // 重新提交版本 2：旧占用释放、旧复核失效、舱单回到 LOADED
        AssemblyPlanView v2 = assembly.submitAssembly(F1, assemblyRequest(1, uldItems()));
        assertThat(v2.planVersion()).isEqualTo(2);
        assertThat(v2.confirmedRoles()).isEmpty();
        assertThat(v2.manifestStatus()).isEqualTo("LOADED");
        assertThat(wb.getActive(F1).status()).isEqualTo("LOADED");
        assertThat(wb.getActive(F1).confirmedRoles()).isEmpty();

        Integer openOld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE plan_id=? AND open_flag=0",
                Integer.class, v1.planId());
        assertThat(openOld).isZero();
        Integer openNew = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE plan_id=? AND open_flag=0",
                Integer.class, v2.planId());
        assertThat(openNew).isEqualTo(2);
        // 旧图历史只读保留
        assertThat(assembly.listPlans(F1).get(0).active()).isFalse();
    }

    @Test
    void closeRechecksVersionAndReleasesOccupanciesAtomically() {
        toLoaded(F1);
        assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
        reviewBoth(F1);
        AssemblyPlanView closed = assembly.getActivePlan(F1);

        var resp = wb.close(F1, new CloseRequest(rid(), "captain"));
        assertThat(resp.status()).isEqualTo("CLOSED");

        Integer stillOpen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE flight_id=1 AND open_flag=0", Integer.class);
        assertThat(stillOpen).isZero();
        Integer released = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE flight_id=1 AND open_flag IS NULL AND released_at IS NOT NULL",
                Integer.class);
        assertThat(released).isEqualTo(2);
        AssemblyPlanView after = assembly.getActivePlan(F1);
        assertThat(after.released()).isTrue();
        assertThat(after.releasedAt()).isNotNull();
        assertThat(closed.planId()).isNotNull();

        // 关闭后不能再改图（历史只读）
        assertThatThrownBy(() -> assembly.submitAssembly(F1, assemblyRequest(1, uldItems())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("只读");

        // 关闭后实物编号可被另一未关闭航班使用
        toLoaded(F2);
        assembly.submitAssembly(F2, assemblyRequest(0, uldItems()));
        Integer f2open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE flight_id=2 AND open_flag=0", Integer.class);
        assertThat(f2open).isEqualTo(2);
    }

    @Test
    void fuelChangeAfterAssemblyBlocksCloseUntilPlanResubmittedAndReviewed() {
        toLoaded(F1);
        assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
        // 装配后加油 +1000（限内 LMC，舱单仍为版本 1 但油量快照已变）
        wb.applyLmc(F1, new com.example.starter.wb.dto.LmcRequest(
                rid(), 0, "PAX_F", new BigDecimal("80"),
                BigDecimal.ZERO, "CARGO", "CARGO_F", new BigDecimal("1000"), "lc001"));
        reviewBoth(F1);
        assertThat(wb.getActive(F1).status()).isEqualTo("REVIEWED");

        // 关闭被拦截：装配图油量与最新舱单对不上
        assertThatThrownBy(() -> wb.close(F1, new CloseRequest(rid(), "captain")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("加油量");

        // 重新提交版本 2（匹配最新油量），旧复核失效，重新双岗后关闭成功并释放占用
        assembly.submitAssembly(F1, assemblyRequest(1, uldItems()));
        reviewBoth(F1);
        var closed = wb.close(F1, new CloseRequest(rid(), "captain"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE flight_id=1 AND open_flag=0", Integer.class)).isZero();
    }

    // ------------------------------------------------------------- 幂等

    @Test
    void sameRequestIdSameParamsReplaysFrozenPlan() {
        toLoaded(F1);
        AssemblyRequest req = assemblyRequest(0, uldItems());
        AssemblyPlanView first = assembly.submitAssembly(F1, req);
        AssemblyPlanView replay = assembly.submitAssembly(F1, req);
        assertThat(replay.planId()).isEqualTo(first.planId());
        assertThat(replay.planVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsConflicts() {
        toLoaded(F1);
        AssemblyRequest req = assemblyRequest(0, uldItems());
        assembly.submitAssembly(F1, req);
        // 同键但 expectedVersion 不同 → 409
        AssemblyRequest tampered = new AssemblyRequest(req.requestId(), 1, req.occurredAt(),
                req.operator(), req.items());
        assertThatThrownBy(() -> assembly.submitAssembly(F1, tampered))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
    }

    @Test
    void failedRequestDoesNotOccupyKeyAndLeavesNoBusinessData() {
        toLoaded(F1);
        AssemblyRequest bad = assemblyRequest(0, List.of(
                new UldItem("AKE-0001", "AKE", "ULD_FWD_1", 0, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 0, 901)));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> assembly.submitAssembly(F1, bad))
                    .isInstanceOf(UnprocessableException.class)
                    .hasMessageContaining("货运业载");
        }
        // 业务、版本与幂等同事务回滚：无图、无占用、幂等键也不被失败占用
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uld_occupancy", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id=?",
                Integer.class, bad.requestId())).isZero();

        // 同一 requestId 用合法参数重试应成功（失败未占键，键可被后续成功冻结）
        AssemblyRequest fixed = new AssemblyRequest(bad.requestId(), 0, bad.occurredAt(),
                bad.operator(), uldItems());
        AssemblyPlanView ok = assembly.submitAssembly(F1, fixed);
        assertThat(ok.planVersion()).isEqualTo(1);
        // 成功结果冻结：同键同参回放
        assertThat(assembly.submitAssembly(F1, fixed).planId()).isEqualTo(ok.planId());
    }

    @Test
    void uldNumberContainingDelimitersIsStructurallyDistinguished() {
        toLoaded(F1);
        // 编号含 JSON/键分隔符仍可正常提交
        List<UldItem> withDelim = List.of(
                new UldItem("AKE-1|X,Y\"Z", "AKE", "ULD_FWD_1", 0, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 0, 900));
        AssemblyRequest req = assemblyRequest(0, withDelim);
        AssemblyPlanView ok = assembly.submitAssembly(F1, req);
        assertThat(ok.items().get(0).uldNo()).isEqualTo("AKE-1|X,Y\"Z");

        // 同键改一个字符（即使只是分隔符排布）→ 结构化指纹不同 → 409
        List<UldItem> tamperedItems = List.of(
                new UldItem("AKE-1X|,Y\"Z", "AKE", "ULD_FWD_1", 0, 2900),
                new UldItem("AKE-0002", "AKE", "ULD_AFT_1", 0, 900));
        AssemblyRequest tampered = new AssemblyRequest(req.requestId(), 0, req.occurredAt(),
                req.operator(), tamperedItems);
        assertThatThrownBy(() -> assembly.submitAssembly(F1, tampered))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");
    }

    // ------------------------------------------------------------- 并发

    @Test
    void concurrentSubmitSameFlightHasExactlyOneSuccess() throws Exception {
        toLoaded(F1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                try {
                    assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM assembly_plan WHERE flight_id=1 AND active_flag=1",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentTwoFlightsGrabbingSameUldHasExactlyOneSuccess() throws Exception {
        toLoaded(F1);
        toLoaded(F2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable r1 = () -> {
            await(start);
            try {
                assembly.submitAssembly(F1, assemblyRequest(0, uldItems()));
                success.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
        };
        Runnable r2 = () -> {
            await(start);
            try {
                assembly.submitAssembly(F2, assemblyRequest(0, uldItems()));
                success.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(r1);
        Future<?> f2 = pool.submit(r2);
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        // 同一集装器编号只有一条在装占用
        Integer openForUld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupancy WHERE uld_no='AKE-0001' AND open_flag=0",
                Integer.class);
        assertThat(openForUld).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
