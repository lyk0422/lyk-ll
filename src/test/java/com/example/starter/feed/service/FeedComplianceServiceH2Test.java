package com.example.starter.feed.service;

import com.example.starter.feed.api.ApproveFormulaRequest;
import com.example.starter.feed.api.BatchView;
import com.example.starter.feed.api.CreateBatchRequest;
import com.example.starter.feed.api.FeedingRecordRequest;
import com.example.starter.feed.api.FeedingRecordView;
import com.example.starter.feed.api.FormulaView;
import com.example.starter.feed.api.ProductionRequest;
import com.example.starter.feed.api.ProductionView;
import com.example.starter.feed.api.SlaughterRequest;
import com.example.starter.feed.api.SlaughterView;
import com.example.starter.feed.api.SubmitFormulaRequest;
import com.example.starter.feed.error.IdempotencyConflictException;
import com.example.starter.feed.repo.FeedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于 H2（MODE=MySQL）真实事务的集成测试，覆盖题干全部主流程、失败分支、幂等与并发边界。
 */
@SpringBootTest
class FeedComplianceServiceH2Test {

    @Autowired
    private FeedComplianceService service;
    @Autowired
    private FeedRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessTables() {
        // 合成目录（ingredient/contraindication）保留，仅清理业务数据，保证场景间无顺序依赖。
        jdbc.update("DELETE FROM compliance_alert");
        jdbc.update("DELETE FROM batch_medication");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM formula_item");
        jdbc.update("DELETE FROM formula_version");
        jdbc.update("DELETE FROM formula");
        jdbc.update("DELETE FROM farm_batch");
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private SubmitFormulaRequest.Item item(String code, String ratio) {
        return new SubmitFormulaRequest.Item(code, new BigDecimal(ratio));
    }

    private String createBatch(String species) {
        String batchNo = unique("BATCH");
        service.createBatch(new CreateBatchRequest(batchNo, species));
        return batchNo;
    }

    private String submitAndApprove(String formulaCode, String species, List<SubmitFormulaRequest.Item> items,
                                    String submitReqId, String approveReqId) {
        FormulaView submitted = service.submit(
                new SubmitFormulaRequest(submitReqId, formulaCode, species, items));
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        FormulaView approved = service.approve(
                new ApproveFormulaRequest(approveReqId, formulaCode, submitted.versionNo()));
        assertThat(approved.status()).isEqualTo("APPROVED");
        return formulaCode;
    }

    // ---------------- 主流程与失败分支 ----------------

    @Test
    void exactUpperBoundPassesFullFlowAndWithdrawalBoundaryHolds() {
        String batchNo = createBatch("POULTRY");
        String formulaCode = unique("F");
        // 阿莫西林上限 0.002，恰等于上限；休药 7 天
        submitAndApprove(formulaCode, "POULTRY",
                List.of(item("CORN", "0.70"), item("AMOXICILLIN", "0.002")),
                unique("REQ-S"), unique("REQ-A"));

        Instant lastFeeding = Instant.parse("2026-09-01T10:00:00Z");
        ProductionView production = service.produce(
                new ProductionRequest(unique("REQ-P"), batchNo, formulaCode, null, lastFeeding));
        assertThat(production.versionNo()).isEqualTo(1);
        assertThat(production.maxWithdrawalDays()).isEqualTo(7);
        // 09-01 + 7 天当日 24:00 后 → 最早 09-09（UTC）
        assertThat(production.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 9));

        // 休药期内出栏被拒并提示最早合法出栏日
        SlaughterView rejected = service.slaughter(
                new SlaughterRequest(unique("REQ-X"), batchNo, Instant.parse("2026-09-08T23:59:59Z")));
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.status()).isEqualTo("ACTIVE");
        assertThat(rejected.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(rejected.rejectReason()).contains("最早合法出栏日");

        // 恰满当日 24:00（== 次日 00:00 UTC）出栏合法
        SlaughterView accepted = service.slaughter(
                new SlaughterRequest(unique("REQ-X2"), batchNo, Instant.parse("2026-09-09T00:00:00Z")));
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.repeated()).isFalse();
        assertThat(accepted.status()).isEqualTo("SLAUGHTERED");
        assertThat(accepted.slaughteredAt()).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"));
    }

    @Test
    void contraindicatedPairIsRejectedAndCannotBeApproved() {
        String formulaCode = unique("F");
        FormulaView view = service.submit(new SubmitFormulaRequest(
                unique("REQ-S"), formulaCode, "POULTRY",
                List.of(item("AMOXICILLIN", "0.001"), item("OXYTETRACYCLINE", "0.001"))));
        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.medicated()).isTrue();
        assertThat(view.rejectReasons()).anyMatch(r -> r.contains("配伍禁忌"));

        assertThatThrownBy(() -> service.approve(
                new ApproveFormulaRequest(unique("REQ-A"), formulaCode, view.versionNo())))
                .hasMessageContaining("REJECTED");
    }

    @Test
    void medicatedFormulaMissingSpeciesIsRejectedAtSubmit() {
        String formulaCode = unique("F");
        FormulaView view = service.submit(new SubmitFormulaRequest(
                unique("REQ-S"), formulaCode, null,
                List.of(item("AMOXICILLIN", "0.001"))));
        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.rejectReasons()).containsExactly("含药配方必须绑定目标动物种类");
    }

    @Test
    void speciesMismatchProductionIsRejected() {
        String poultryBatch = createBatch("POULTRY");
        String formulaCode = unique("F");
        submitAndApprove(formulaCode, "SWINE",
                List.of(item("AMOXICILLIN", "0.001")), unique("REQ-S"), unique("REQ-A"));

        assertThatThrownBy(() -> service.produce(new ProductionRequest(
                unique("REQ-P"), poultryBatch, formulaCode, null, Instant.parse("2026-09-01T10:00:00Z"))))
                .isInstanceOf(com.example.starter.feed.error.FeedBusinessException.class)
                .hasMessageContaining("物种错配");
        // 被拒绝后不产生义务行
        assertThat(repository.findActiveMedications(poultryBatch)).isEmpty();
    }

    // ---------------- 版本冻结不追溯 ----------------

    @Test
    void approvedVersionIsFrozenAndLaterVersionDoesNotReduceObligation() {
        String batchNo = createBatch("POULTRY");
        String formulaCode = unique("F");

        // v1：土霉素休药 10 天，批准并投产
        submitAndApprove(formulaCode, "POULTRY",
                List.of(item("CORN", "0.70"), item("OXYTETRACYCLINE", "0.001")),
                unique("REQ-S1"), unique("REQ-A1"));
        service.produce(new ProductionRequest(unique("REQ-P1"), batchNo, formulaCode, 1,
                Instant.parse("2026-09-01T10:00:00Z")));

        // 修改配方必须提交新版本：v2 改为休药 5 天的恩拉霉素，独立校验通过并批准
        FormulaView v2 = service.submit(new SubmitFormulaRequest(unique("REQ-S2"), formulaCode, "POULTRY",
                List.of(item("CORN", "0.70"), item("ENRAMYCIN", "0.0005"))));
        assertThat(v2.versionNo()).isEqualTo(2);
        service.approve(new ApproveFormulaRequest(unique("REQ-A2"), formulaCode, 2));

        // v1 仍为 APPROVED 且快照内容不变；批次既有义务按 v1（10 天）计算，不因 v2 减轻
        BatchView batch = service.getBatch(batchNo);
        assertThat(batch.medications()).hasSize(1);
        assertThat(batch.medications().get(0).versionNo()).isEqualTo(1);
        assertThat(batch.medications().get(0).maxWithdrawalDays()).isEqualTo(10);
        assertThat(batch.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 12));

        // 版本轨迹可复算
        List<FormulaView> versions = service.listVersions(formulaCode);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isOne();
        assertThat(versions.get(0).status()).isEqualTo("APPROVED");
        assertThat(versions.get(0).items()).extracting(FormulaView.Item::ingredientCode)
                .contains("OXYTETRACYCLINE");
        assertThat(versions.get(1).items()).extracting(FormulaView.Item::ingredientCode)
                .contains("ENRAMYCIN");
    }

    // ---------------- 多配方休药期合并 ----------------

    @Test
    void concurrentProductionOfTwoFormulasMergesByMaxDaysAndLatestFeeding() throws Exception {
        String batchNo = createBatch("POULTRY");
        String f1 = unique("F1");
        String f2 = unique("F2");
        submitAndApprove(f1, "POULTRY", List.of(item("AMOXICILLIN", "0.001")),
                unique("REQ-S"), unique("REQ-A"));
        submitAndApprove(f2, "POULTRY", List.of(item("SULFAMONOMETHOXINE", "0.001")),
                unique("REQ-S"), unique("REQ-A"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Future<ProductionView>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return service.produce(new ProductionRequest(unique("REQ-P"), batchNo, f1, null,
                    Instant.parse("2026-09-01T10:00:00Z")));
        }));
        futures.add(pool.submit(() -> {
            ready.countDown();
            start.await();
            return service.produce(new ProductionRequest(unique("REQ-P"), batchNo, f2, null,
                    Instant.parse("2026-09-03T06:00:00Z")));
        }));
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (Future<ProductionView> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 恰好两行义务，且合并口径唯一：最大 14 天（磺胺）、最晚饲喂 09-03 → 最早 09-18
        BatchView batch = service.getBatch(batchNo);
        assertThat(batch.medications()).hasSize(2);
        assertThat(batch.maxWithdrawalDays()).isEqualTo(14);
        assertThat(batch.latestFeedingTime()).isEqualTo(Instant.parse("2026-09-03T06:00:00Z"));
        assertThat(batch.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(failure.get()).isNull();
    }

    // ---------------- 幂等：同键重放 / 同键异参 409 ----------------

    @Test
    void sameRequestIdSameParamsReplaysFirstResultWithoutNewVersion() {
        String formulaCode = unique("F");
        String requestId = unique("REQ-IDEM");
        SubmitFormulaRequest first = new SubmitFormulaRequest(requestId, formulaCode, "POULTRY",
                List.of(item("AMOXICILLIN", "0.001")));
        FormulaView v1 = service.submit(first);
        FormulaView replay = service.submit(first);

        assertThat(replay.versionNo()).isEqualTo(v1.versionNo()).isOne();
        // 只生成一个版本
        assertThat(service.listVersions(formulaCode)).hasSize(1);
        Integer versionRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM formula_version WHERE formula_code = ?", Integer.class, formulaCode);
        assertThat(versionRows).isOne();
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndChangesNothing() {
        String formulaCode = unique("F");
        String requestId = unique("REQ-CONFLICT");
        service.submit(new SubmitFormulaRequest(requestId, formulaCode, "POULTRY",
                List.of(item("AMOXICILLIN", "0.001"))));

        // 同键异参（比例变化）→ 409
        assertThatThrownBy(() -> service.submit(new SubmitFormulaRequest(requestId, formulaCode, "POULTRY",
                List.of(item("AMOXICILLIN", "0.0015")))))
                .isInstanceOf(IdempotencyConflictException.class);
        // 同键异参（配方编码变化）→ 同样 409
        assertThatThrownBy(() -> service.submit(new SubmitFormulaRequest(requestId, unique("OTHER"), "POULTRY",
                List.of(item("AMOXICILLIN", "0.001")))))
                .isInstanceOf(IdempotencyConflictException.class);

        // 状态未改变：仍只有 v1 首次结果
        List<FormulaView> versions = service.listVersions(formulaCode);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).items().get(0).ratio()).isEqualByComparingTo("0.001");
    }

    @Test
    void concurrentSameRequestIdSubmitsCreatesExactlyOneVersion() throws Exception {
        String formulaCode = unique("F");
        String requestId = unique("REQ-RACE");
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<FormulaView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return service.submit(new SubmitFormulaRequest(requestId, formulaCode, "POULTRY",
                        List.of(item("AMOXICILLIN", "0.001"))));
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (Future<FormulaView> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS).versionNo()).isOne();
        }
        pool.shutdown();
        assertThat(service.listVersions(formulaCode)).hasSize(1);
    }

    // ---------------- 出栏终态与迟到告警 ----------------

    @Test
    void slaughterIsTerminalRepeatedRequestIdempotentAndLateFeedingRaisesAlert() {
        String batchNo = createBatch("POULTRY");
        String formulaCode = unique("F");
        submitAndApprove(formulaCode, "POULTRY", List.of(item("AMOXICILLIN", "0.001")),
                unique("REQ-S"), unique("REQ-A"));
        service.produce(new ProductionRequest(unique("REQ-P"), batchNo, formulaCode, null,
                Instant.parse("2026-09-01T10:00:00Z")));

        Instant slaughterTime = Instant.parse("2026-09-09T00:00:00Z");
        String slaughterReqId = unique("REQ-SL");
        SlaughterView first = service.slaughter(
                new SlaughterRequest(slaughterReqId, batchNo, slaughterTime));
        assertThat(first.accepted()).isTrue();
        assertThat(first.repeated()).isFalse();

        // 同键重放：返回首次结果（幂等成功）
        SlaughterView replaySameKey = service.slaughter(
                new SlaughterRequest(slaughterReqId, batchNo, slaughterTime));
        assertThat(replaySameKey.accepted()).isTrue();
        assertThat(replaySameKey.slaughteredAt()).isEqualTo(slaughterTime);

        // 新 requestId 的重复出栏申请：终态幂等成功
        SlaughterView repeat = service.slaughter(
                new SlaughterRequest(unique("REQ-SL2"), batchNo, Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(repeat.accepted()).isTrue();
        assertThat(repeat.repeated()).isTrue();
        assertThat(repeat.status()).isEqualTo("SLAUGHTERED");
        // 出栏时刻不被重复申请改写
        assertThat(repeat.slaughteredAt()).isEqualTo(slaughterTime);

        // 出栏后迟到的含药饲喂：只记迟到事件 + 告警，不改终态
        FeedingRecordView late = service.recordFeeding(new FeedingRecordRequest(
                batchNo, formulaCode, null, Instant.parse("2026-09-10T08:00:00Z")));
        assertThat(late.late()).isTrue();
        assertThat(late.alertRaised()).isTrue();

        BatchView batch = service.getBatch(batchNo);
        assertThat(batch.status()).isEqualTo("SLAUGHTERED");
        assertThat(batch.slaughteredAt()).isEqualTo(slaughterTime);
        assertThat(batch.alerts()).hasSize(1);
        assertThat(batch.alerts().get(0).alertType()).isEqualTo("LATE_FEEDING");
        // 迟到行不参与休药期复算：最早合法出栏日仍为原窗口
        assertThat(batch.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(batch.medications()).filteredOn(BatchView.Medication::late).hasSize(1);

        // 出栏后不可再投产
        assertThatThrownBy(() -> service.produce(new ProductionRequest(
                unique("REQ-P2"), batchNo, formulaCode, null, Instant.parse("2026-09-10T08:00:00Z"))))
                .hasMessageContaining("已出栏");
    }

    @Test
    void activeBatchFeedingRecordParticipatesInWithdrawalMerge() {
        String batchNo = createBatch("POULTRY");
        String formulaCode = unique("F");
        submitAndApprove(formulaCode, "POULTRY", List.of(item("AMOXICILLIN", "0.001")),
                unique("REQ-S"), unique("REQ-A"));
        service.produce(new ProductionRequest(unique("REQ-P1"), batchNo, formulaCode, null,
                Instant.parse("2026-09-01T10:00:00Z")));
        // 在栏期间补报一条更晚的含药饲喂（同一已批准版本），窗口应顺延
        FeedingRecordView onTime = service.recordFeeding(new FeedingRecordRequest(
                batchNo, formulaCode, null, Instant.parse("2026-09-05T10:00:00Z")));
        assertThat(onTime.late()).isFalse();
        BatchView batch = service.getBatch(batchNo);
        assertThat(batch.latestFeedingTime()).isEqualTo(Instant.parse("2026-09-05T10:00:00Z"));
        assertThat(batch.earliestSlaughterDate()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    void nonMedicatedFormulaProductionIsRejected() {
        String batchNo = createBatch("POULTRY");
        String formulaCode = unique("F");
        FormulaView submitted = service.submit(new SubmitFormulaRequest(
                unique("REQ-S"), formulaCode, null, List.of(item("CORN", "0.90"))));
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.medicated()).isFalse();
        service.approve(new ApproveFormulaRequest(unique("REQ-A"), formulaCode, submitted.versionNo()));

        assertThatThrownBy(() -> service.produce(new ProductionRequest(
                unique("REQ-P"), batchNo, formulaCode, null, Instant.parse("2026-09-01T10:00:00Z"))))
                .isInstanceOf(com.example.starter.feed.error.FeedBusinessException.class)
                .hasMessageContaining("不含药物添加剂");
        assertThat(repository.findActiveMedications(batchNo)).isEmpty();
    }

    @Test
    void unapprovedVersionCannotBeProduced() {
        String batchNo = createBatch("POULTRY");
        String formulaCode = unique("F");
        service.submit(new SubmitFormulaRequest(
                unique("REQ-S"), formulaCode, "POULTRY", List.of(item("AMOXICILLIN", "0.001"))));

        assertThatThrownBy(() -> service.produce(new ProductionRequest(
                unique("REQ-P"), batchNo, formulaCode, 1, Instant.parse("2026-09-01T10:00:00Z"))))
                .isInstanceOf(com.example.starter.feed.error.FeedBusinessException.class)
                .hasMessageContaining("仅已批准冻结版本");
    }

    @Test
    void approveAndProductionAreIdempotentOnReplay() {
        String batchNo = createBatch("POULTRY");
        String formulaCode = unique("F");
        service.submit(new SubmitFormulaRequest(
                unique("REQ-S"), formulaCode, "POULTRY", List.of(item("AMOXICILLIN", "0.001"))));

        String approveReqId = unique("REQ-AI");
        FormulaView approved1 = service.approve(
                new ApproveFormulaRequest(approveReqId, formulaCode, 1));
        FormulaView approved2 = service.approve(
                new ApproveFormulaRequest(approveReqId, formulaCode, 1));
        assertThat(approved2.status()).isEqualTo("APPROVED");
        assertThat(approved2.versionNo()).isEqualTo(approved1.versionNo());

        String productionReqId = unique("REQ-PI");
        ProductionRequest productionRequest = new ProductionRequest(productionReqId, batchNo, formulaCode, 1,
                Instant.parse("2026-09-01T10:00:00Z"));
        ProductionView p1 = service.produce(productionRequest);
        ProductionView p2 = service.produce(productionRequest);
        assertThat(p2.earliestSlaughterDate()).isEqualTo(p1.earliestSlaughterDate());
        // 重放不新增义务行
        assertThat(repository.findActiveMedications(batchNo)).hasSize(1);
    }

    @Test
    void batchWithoutMedicationCanSlaughterFreely() {
        String batchNo = createBatch("POULTRY");
        SlaughterView view = service.slaughter(
                new SlaughterRequest(unique("REQ-X"), batchNo, Instant.parse("2026-09-01T00:00:00Z")));
        assertThat(view.accepted()).isTrue();
        assertThat(view.status()).isEqualTo("SLAUGHTERED");
    }

    @Test
    void unknownBatchProductionIsRejectedAndNoStateChanges() {
        String formulaCode = unique("F");
        submitAndApprove(formulaCode, "POULTRY", List.of(item("AMOXICILLIN", "0.001")),
                unique("REQ-S"), unique("REQ-A"));
        String ghostBatch = unique("GHOST");
        // 同一 requestId 先打到不存在的批次：业务异常，事务回滚，不落幂等账
        String requestId = unique("REQ-PGHOST");
        assertThatThrownBy(() -> service.produce(new ProductionRequest(
                requestId, ghostBatch, formulaCode, null, Instant.parse("2026-09-01T10:00:00Z"))))
                .isInstanceOf(com.example.starter.feed.error.FeedBusinessException.class)
                .hasMessageContaining("养殖批次不存在");
        // 同一 requestId 改打真实批次（异参）：因未落账不触发 409，正常受理
        String realBatch = createBatch("POULTRY");
        ProductionView production = service.produce(new ProductionRequest(
                requestId, realBatch, formulaCode, null, Instant.parse("2026-09-01T10:00:00Z")));
        assertThat(production.batchNo()).isEqualTo(realBatch);
        assertThat(repository.findActiveMedications(realBatch)).hasSize(1);
    }
}
