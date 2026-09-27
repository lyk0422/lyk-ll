package com.example.starter.wb.service;

import com.example.starter.wb.AbstractIntegrationTest;
import com.example.starter.wb.dto.CargoPlanResponse;
import com.example.starter.wb.dto.CargoPlanSubmitRequest;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CloseRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LoadItem;
import com.example.starter.wb.dto.LmcRequest;
import com.example.starter.wb.dto.ReviewRequest;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.dto.UldItemInput;
import com.example.starter.wb.exception.BadRequestException;
import com.example.starter.wb.exception.ConflictException;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.exception.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * 集装器装配图 H2 事务/集成测试：主流程、整版拒绝失败分支、版本/幂等边界、
 * LMC/加油后复核对应最新舱单、关闭原子释放占用，以及同航班并发改图与两航班抢同器。
 */
class CargoPlanIntegrationTest extends AbstractIntegrationTest {

    private static final long F1 = 1L;
    private static final long F2 = 2L;

    @Autowired
    private WeightBalanceService wb;
    @Autowired
    private CargoPlanService cargo;

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 90 人@80@PAX_F=7200kg；货运 5 件@1000@CARGO_F(500in=12700mm)=5000kg；油 5000kg。 */
    private static List<LoadItem> items() {
        return List.of(
                new LoadItem("PASSENGER", "PAX_F", 90, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 5, new BigDecimal("1000")));
    }

    private void toLoaded(long flightId) {
        wb.createManifest(new CreateManifestRequest(rid(), flightId, new BigDecimal("5000"), "lc001", items()));
        wb.checkin(flightId, new CheckinRequest(rid(), items(), new BigDecimal("5000"), "agent01"));
        wb.confirmLoad(flightId, new StageRequest(rid(), "loader01"));
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 9, 27, 9, 0);
    }

    /** 一个 AKE 装入 11P（arm 12700mm=500in，限额 5000 恰达）：皮重 100+净重 4900=5000kg。 */
    private static List<UldItemInput> oneAkeAtLimit() {
        return List.of(new UldItemInput("AKE12345CA", "11P", 100, 4900));
    }

    private CargoPlanSubmitRequest submitReq(int expectedVersion, List<UldItemInput> ulds) {
        return new CargoPlanSubmitRequest(rid(), expectedVersion, now(), "lc001", ulds);
    }

    // ------------------------------------------------------------- 主流程

    @Test
    void submitPlanWritesVersion1ExactMomentAndUpdatesCargoMomentThenReviewCloseReleases() {
        toLoaded(F1);
        CargoPlanResponse plan = cargo.submitPlan(F1, submitReq(0, oneAkeAtLimit()));

        assertThat(plan.planVersion()).isEqualTo(1);
        assertThat(plan.manifestVersionNo()).isEqualTo(1);
        assertThat(plan.cargoNetKg()).isEqualTo(4900);
        assertThat(plan.cargoGrossKg()).isEqualTo(5000);
        // 力矩精确求和：5000kg × 12700mm = 63,500,000 kg·mm
        assertThat(plan.totalMomentKgMm()).isEqualTo(63_500_000L);
        assertThat(plan.withinLimits()).isTrue();
        assertThat(plan.manifestVersionMatch()).isTrue();
        assertThat(plan.items()).hasSize(1);
        assertThat(plan.items().get(0).armMm()).isEqualTo(12700);
        assertThat(plan.items().get(0).grossKg()).isEqualTo(5000);

        // 舱单货运力矩/重心已按装配图更新（12700mm=500in 与原 CARGO_F 站位同臂，重心不变且在包线内）
        var active = wb.getActive(F1);
        assertThat(active.status()).isEqualTo("LOADED");
        assertThat(active.cgTowPct()).isEqualByComparingTo(plan.cgTowPct());

        // 实物占用生效
        Integer occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_uld_occupancy WHERE uld_no='AKE12345CA' AND active_flag=1",
                Integer.class);
        assertThat(occupied).isEqualTo(1);

        // 双岗复核 → 关闭
        wb.review(F1, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        wb.review(F1, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
        var closed = wb.close(F1, new CloseRequest(rid(), "captain"));
        assertThat(closed.status()).isEqualTo("CLOSED");

        // 关闭原子释放实物占用
        Integer stillOccupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_uld_occupancy WHERE uld_no='AKE12345CA' AND active_flag=1",
                Integer.class);
        assertThat(stillOccupied).isZero();
        Integer releasedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_uld_occupancy WHERE uld_no='AKE12345CA' AND released_at IS NOT NULL",
                Integer.class);
        assertThat(releasedRows).isEqualTo(1);
    }

    @Test
    void exactCompartmentLimitWithZeroTareIsLegal() {
        toLoaded(F1);
        // 皮重 0、净重 5000：非负皮重、正净重，含皮重总重恰达 5000 限额
        CargoPlanResponse plan = cargo.submitPlan(F1,
                submitReq(0, List.of(new UldItemInput("AKE99999CA", "11P", 0, 5000))));
        assertThat(plan.cargoGrossKg()).isEqualTo(5000);
        assertThat(plan.withinLimits()).isTrue();
    }

    @Test
    void replanReleasesOldOccupancyKeepsReadOnlyHistoryAndBumpsVersion() {
        toLoaded(F1);
        cargo.submitPlan(F1, submitReq(0, oneAkeAtLimit()));
        // 改图：器号更换，旧占用原子释放，历史图只读保留
        List<UldItemInput> v2 = List.of(new UldItemInput("AKH77777CA", "11P", 50, 4950));
        CargoPlanResponse plan2 = cargo.submitPlan(F1, submitReq(1, v2));
        assertThat(plan2.planVersion()).isEqualTo(2);

        Integer oldActive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_uld_occupancy WHERE uld_no='AKE12345CA' AND active_flag=1",
                Integer.class);
        assertThat(oldActive).isZero();
        Integer newActive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_uld_occupancy WHERE uld_no='AKH77777CA' AND active_flag=1",
                Integer.class);
        assertThat(newActive).isEqualTo(1);

        List<CargoPlanResponse> history = cargo.listPlanHistory(F1);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).planVersion()).isEqualTo(1);
        assertThat(history.get(1).planVersion()).isEqualTo(2);
        assertThat(cargo.getActivePlan(F1).planVersion()).isEqualTo(2);
    }

    // ------------------------------------------------------------- 整版拒绝

    @Test
    void grossWeightMismatchRejectsWholePlan422AndWritesNothing() {
        toLoaded(F1);
        List<UldItemInput> bad = List.of(new UldItemInput("AKE12345CA", "11P", 100, 4899));
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0, bad)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("货运业载");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cargo_loading_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cargo_uld_occupancy", Integer.class)).isZero();
    }

    @Test
    void duplicateUldAndDuplicateCompartmentAndOverLimitAndWrongTypeAreRejected() {
        toLoaded(F1);
        // 同器号重复
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0, List.of(
                new UldItemInput("AKE10001CA", "11P", 0, 2500),
                new UldItemInput("AKE10001CA", "21A", 0, 2500)))))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("重复");
        // 同舱位两器
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0, List.of(
                new UldItemInput("AKE10001CA", "11P", 0, 2500),
                new UldItemInput("AKH10002CA", "11P", 0, 2500)))))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("同舱位最多一器");
        // 含皮重总重超舱位限额（11P 限额 5000）
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0,
                List.of(new UldItemInput("AKE12345CA", "11P", 1, 5000)))))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("限额");
        // 类型不允许：11P 不接受 PAG
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0,
                List.of(new UldItemInput("PAG20001CA", "11P", 0, 5000)))))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("不允许集装器类型");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cargo_loading_plan", Integer.class)).isZero();
    }

    @Test
    void unknownCompartmentIs400AndMalformedUldIs400() {
        toLoaded(F1);
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0,
                List.of(new UldItemInput("AKE12345CA", "99X", 0, 5000)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("舱位 99X");
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0,
                List.of(new UldItemInput("AK", "11P", 0, 5000)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("类型代码");
    }

    @Test
    void noPlanBeforeCheckinAndNoReplanAfterClose() {
        wb.createManifest(new CreateManifestRequest(rid(), F1, new BigDecimal("5000"), "lc001", items()));
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0, oneAkeAtLimit())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("值机");

        toLoaded(F2);
        cargo.submitPlan(F2, submitReq(0, oneAkeAtLimit()));
        wb.review(F2, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        wb.review(F2, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
        wb.close(F2, new CloseRequest(rid(), "captain"));
        assertThatThrownBy(() -> cargo.submitPlan(F2, submitReq(1, oneAkeAtLimit())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("历史只读");
    }

    @Test
    void getPlanOnFlightWithoutPlanIs404AndUnknownFlightIs404() {
        toLoaded(F1);
        assertThatThrownBy(() -> cargo.getActivePlan(F1))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("尚未提交");
        assertThatThrownBy(() -> cargo.getActivePlan(999))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("航班 999");
    }

    @Test
    void physicalCompartmentArmDrivesCgRatherThanManifestCargoStation() {
        // 舱单货运登记在 CARGO_F(500in)，实物装入 12P(13716mm=540in)：重心须按物理舱位力矩。
        wb.createManifest(new CreateManifestRequest(rid(), F1, new BigDecimal("5000"), "lc001", List.of(
                new LoadItem("PASSENGER", "PAX_F", 90, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 3, new BigDecimal("1000")))));
        wb.checkin(F1, new CheckinRequest(rid(), List.of(
                new LoadItem("PASSENGER", "PAX_F", 90, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 3, new BigDecimal("1000"))),
                new BigDecimal("5000"), "agent01"));
        wb.confirmLoad(F1, new StageRequest(rid(), "loader01"));

        CargoPlanResponse plan = cargo.submitPlan(F1,
                submitReq(0, List.of(new UldItemInput("AKH00031CA", "12P", 0, 3000))));
        // 力矩 3000kg × 13716mm = 41,148,000 kg·mm（540in）
        assertThat(plan.totalMomentKgMm()).isEqualTo(41_148_000L);
        // ZFW=50200：40000@700 + 7200@600 + 3000@540 → arm 676.0956 → 20.4382%MAC（非登记站位的 19.4821）
        assertThat(plan.cgZfwPct()).isEqualByComparingTo("20.4382");
        assertThat(wb.getActive(F1).cgZfwPct()).isEqualByComparingTo("20.4382");
    }

    // ------------------------------------------------------------- 复核/关闭对应最新舱单

    @Test
    void fuelChangeAfterPlanMakesReviewRequireResubmitButPlanStaysTraceable() {
        toLoaded(F1);
        cargo.submitPlan(F1, submitReq(0, oneAkeAtLimit()));

        // 加油 +1000kg（同版 LMC），装配未变化
        wb.applyLmc(F1, new LmcRequest(rid(), 0, "PAX_F", new BigDecimal("80"),
                BigDecimal.ZERO, "CARGO", "CARGO_F", new BigDecimal("1000"), "lc001"));

        CargoPlanResponse stale = cargo.getActivePlan(F1);
        assertThat(stale.manifestVersionMatch()).isFalse();

        // 加油后未重提装配图：任一岗位复核都在签字前被拒（复核必须对应最新舱单），两岗均不落库
        assertThatThrownBy(() -> wb.review(F1, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("重新提交装配图");
        assertThatThrownBy(() -> wb.review(F1, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("重新提交装配图");
        Integer reviews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_confirmation WHERE manifest_id IN "
                        + "(SELECT id FROM manifest_version WHERE flight_id=1)", Integer.class);
        assertThat(reviews).isZero();

        // 历史图仍可追溯
        assertThat(cargo.listPlanHistory(F1)).hasSize(1);

        // 按最新舱单重提同一张图（航班内图版本 +1 到 2，不重置）
        CargoPlanResponse v2 = cargo.submitPlan(F1, submitReq(1, oneAkeAtLimit()));
        assertThat(v2.planVersion()).isEqualTo(2);
        assertThat(v2.manifestVersionMatch()).isTrue();
        wb.review(F1, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        wb.review(F1, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
        var closed = wb.close(F1, new CloseRequest(rid(), "captain"));
        assertThat(closed.status()).isEqualTo("CLOSED");
    }

    @Test
    void resubmittingPlanInvalidatesPriorReviewsAndUnfreezes() {
        toLoaded(F1);
        cargo.submitPlan(F1, submitReq(0, oneAkeAtLimit()));
        wb.review(F1, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        wb.review(F1, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
        assertThat(wb.getActive(F1).status()).isEqualTo("REVIEWED");

        // 改图：旧双岗复核整表失效，状态回到 LOADED，不得沿用旧复核
        cargo.submitPlan(F1, submitReq(1,
                List.of(new UldItemInput("AKH77777CA", "11P", 50, 4950))));
        assertThat(wb.getActive(F1).status()).isEqualTo("LOADED");
        assertThat(wb.getActive(F1).confirmedRoles()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_confirmation", Integer.class)).isZero();
        // 未重新双岗不能关闭
        assertThatThrownBy(() -> wb.close(F1, new CloseRequest(rid(), "captain")))
                .isInstanceOf(ConflictException.class);
    }

    // ------------------------------------------------------------- 跨航班实物占用

    @Test
    void sameUldOnTwoOpenFlightsOnlyOneSucceedsAndReleasedAfterClose() {
        toLoaded(F1);
        toLoaded(F2);
        cargo.submitPlan(F1, submitReq(0, oneAkeAtLimit()));
        assertThatThrownBy(() -> cargo.submitPlan(F2, submitReq(0, oneAkeAtLimit())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已被未关闭航班");
        // 失败方不产生图与占用
        assertThat(cargo.listPlanHistory(F2)).isEmpty();

        // F1 关闭释放后，F2 可占用同一器
        wb.review(F1, new ReviewRequest(rid(), "LOAD_CONTROLLER", "lc001"));
        wb.review(F1, new ReviewRequest(rid(), "FLIGHT_DISPATCHER", "fd002"));
        wb.close(F1, new CloseRequest(rid(), "captain"));
        CargoPlanResponse f2plan = cargo.submitPlan(F2, submitReq(0, oneAkeAtLimit()));
        assertThat(f2plan.planVersion()).isEqualTo(1);
    }

    // ------------------------------------------------------------- 幂等

    @Test
    void sameKeySameParamsReplaysAndDifferentParamsConflicts() {
        toLoaded(F1);
        String key = rid();
        var req1 = new CargoPlanSubmitRequest(key, 0, now(), "lc001", oneAkeAtLimit());
        CargoPlanResponse first = cargo.submitPlan(F1, req1);
        CargoPlanResponse replay = cargo.submitPlan(F1, req1);
        assertThat(replay.planVersion()).isEqualTo(first.planVersion()).isEqualTo(1);
        assertThat(replay.totalMomentKgMm()).isEqualTo(first.totalMomentKgMm());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cargo_loading_plan", Integer.class)).isEqualTo(1);

        // 异参（时刻不同）→ 409
        var tampered = new CargoPlanSubmitRequest(key, 0, now().plusSeconds(1), "lc001", oneAkeAtLimit());
        assertThatThrownBy(() -> cargo.submitPlan(F1, tampered))
                .isInstanceOf(ConflictException.class).hasMessageContaining("不同业务参数");
    }

    @Test
    void failedSubmitDoesNotOccupyKeySoSameKeyCanSucceedAfterwards() {
        toLoaded(F1);
        String key = rid();
        // 首次业务失败：含皮重总重 4999 与货运业载 5000 不一致 → 422
        var bad = new CargoPlanSubmitRequest(key, 0, now(), "lc001",
                List.of(new UldItemInput("AKE12345CA", "11P", 100, 4899)));
        assertThatThrownBy(() -> cargo.submitPlan(F1, bad))
                .isInstanceOf(UnprocessableException.class);
        // 失败不占键：修正净重后同键成功，且幂等表仅有一条（成功）记录
        var fixed = new CargoPlanSubmitRequest(key, 0, now(), "lc001",
                List.of(new UldItemInput("AKE12345CA", "11P", 100, 4900)));
        CargoPlanResponse ok = cargo.submitPlan(F1, fixed);
        assertThat(ok.planVersion()).isEqualTo(1);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id=?", Integer.class, key);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void canonicalFingerprintDistinguishesStructuredValuesContainingDelimiters() {
        // 规范化 JSON 是结构化的：扁平拼接会碰撞的两组值，指纹必须不同；
        // 合法业务文本即便含分隔符 '|' 也按字段/集合边界区分。
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        List<String> splitLeft = List.of("AKE|11P", "21A");
        List<String> splitRight = List.of("AKE", "11P|21A");
        String flattened = "AKE|11P|21A";
        String fpLeft = com.example.starter.wb.support.FingerprintUtil.sha256("k", splitLeft, mapper);
        String fpRight = com.example.starter.wb.support.FingerprintUtil.sha256("k", splitRight, mapper);
        String fpFlat = com.example.starter.wb.support.FingerprintUtil.sha256("k", flattened, mapper);
        assertThat(fpLeft).isNotEqualTo(fpRight);
        assertThat(fpLeft).isNotEqualTo(fpFlat);
        // 同参同指纹（幂等回放前提）
        assertThat(com.example.starter.wb.support.FingerprintUtil.sha256("k", splitLeft, mapper))
                .isEqualTo(fpLeft);
    }

    @Test
    void staleExpectedVersionConflictsWithoutWriting() {
        toLoaded(F1);
        cargo.submitPlan(F1, submitReq(0, oneAkeAtLimit()));
        assertThatThrownBy(() -> cargo.submitPlan(F1, submitReq(0,
                List.of(new UldItemInput("AKH77777CA", "11P", 50, 4950)))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("expectedVersion");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_loading_plan WHERE plan_version=2", Integer.class)).isZero();
    }

    // ------------------------------------------------------------- 并发

    @Test
    void concurrentReplanSameFlightHasExactlyOneSuccess() throws Exception {
        toLoaded(F1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    cargo.submitPlan(F1, submitReq(0,
                            List.of(new UldItemInput("AKE%05dCA".formatted(n), "11P", 0, 5000))));
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
                "SELECT COUNT(*) FROM cargo_loading_plan WHERE flight_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_uld_occupancy WHERE flight_id=1 AND active_flag=1",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentTwoFlightsGrabbingSameUldHasExactlyOneWinner() throws Exception {
        toLoaded(F1);
        toLoaded(F2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        java.util.function.LongConsumer grab = flight -> {
            await(start);
            try {
                cargo.submitPlan(flight, submitReq(0, oneAkeAtLimit()));
            } catch (ConflictException e) {
                errors.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(() -> grab.accept(F1));
        Future<?> f2 = pool.submit(() -> grab.accept(F2));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 实物占用仅一条有效；败方整版事务回滚（图也不留）
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_uld_occupancy WHERE uld_no='AKE12345CA' AND active_flag=1",
                Integer.class);
        assertThat(active).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cargo_loading_plan", Integer.class)).isEqualTo(1);
        assertThat(errors.get()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeySameParamsSubmitsOnePlanAndOthersReplay() throws Exception {
        toLoaded(F1);
        int threads = 5;
        String key = rid();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sameVersion = new AtomicInteger();
        AtomicInteger otherError = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                try {
                    var req = new CargoPlanSubmitRequest(key, 0, now(), "lc001", oneAkeAtLimit());
                    if (cargo.submitPlan(F1, req).planVersion() == 1) {
                        sameVersion.incrementAndGet();
                    }
                } catch (ConflictException | UnprocessableException | BadRequestException e) {
                    otherError.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(otherError.get()).isZero();
        assertThat(sameVersion.get()).isEqualTo(threads);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cargo_loading_plan WHERE flight_id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id=?", Integer.class, key)).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
