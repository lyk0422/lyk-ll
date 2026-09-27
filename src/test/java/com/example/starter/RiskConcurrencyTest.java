package com.example.starter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.starter.error.ApiException;
import com.example.starter.service.BatchService;
import com.example.starter.service.RiskService;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.RiskBindRequest;
import com.example.starter.web.dto.RiskChangeRequest;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.RiskSummaryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险抽检并发边界：阳性与出厂核销、风险变更与最后结果两两竞争，
 * 经真实并发线程 + H2 行锁验证，断言不会出现已放行但漏检/阳性的批次，版本与结果唯一一致。
 * 本类用例对加锁顺序敏感，重复整类执行亦须稳定通过。
 */
class RiskConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private RiskService riskService;

    @Autowired
    private BatchService batchService;

    private static List<String> heads(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(String.format("H%02d", i));
        }
        return list;
    }

    private void readyJudgedQualified(String batchNo, int n) throws Exception {
        declareOk("d-" + batchNo, batchNo, heads(n));
        preQuarantineOk(batchNo, "pq-" + batchNo);
        for (int i = 1; i <= n; i++) {
            inspectOk(batchNo, "i-" + batchNo + "-" + i, String.format("H%02d", i));
        }
        JudgmentRequest.JudgmentItem[] items = heads(n).stream()
                .map(h -> new JudgmentRequest.JudgmentItem(h, "QUALIFIED", null, null))
                .toArray(JudgmentRequest.JudgmentItem[]::new);
        postOk("/api/batches/" + batchNo + "/judgments",
                new JudgmentRequest("j-" + batchNo, List.of(items)),
                com.example.starter.web.dto.JudgmentResponse.class);
    }

    @Test
    @DisplayName("阳性结果与出厂核销并发：绝不出现已放行且阳性/漏检的批次")
    void positiveVersusReleaseNeverReleasesPositiveOrMissing() throws Exception {
        readyJudgedQualified("B-CPOS", 10);
        riskService.bind("B-CPOS", new RiskBindRequest("rb-1", "NORMAL", "RULE-1", heads(10), 0,
                clock.instant().toString()));
        // 抽检清单仅 H01 且尚无结果：并发提交阳性与出厂核销
        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.submitResult("B-CPOS",
                        new RiskResultRequest("rr-pos", "H01", "POSITIVE", 1,
                                clock.instant().toString())),
                () -> batchService.release("B-CPOS",
                        new ReleaseRequest("rel-1", "核销员甲")));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        assertEquals(1, successes, "阳性提交必成功，核销须被拒绝");
        assertEquals(1, conflicts);

        BatchView view = getView("B-CPOS");
        assertEquals("SPECIAL_QUARANTINE", view.status(), "阳性批次必须留在专项隔离，不得放行");
        RiskSummaryView risk = getRisk("B-CPOS");
        assertEquals("POSITIVE", risk.reasons().get(0).result());
        assertTrue(risk.quarantineActive());
    }

    @Test
    @DisplayName("最后阴性结果与出厂核销并发：放行当且仅当抽检已完成且全阴，否则不漏检")
    void lastNegativeVersusReleaseConsistent() throws Exception {
        readyJudgedQualified("B-CNEG", 10);
        riskService.bind("B-CNEG", new RiskBindRequest("rb-1", "NORMAL", "RULE-1", heads(10), 0,
                clock.instant().toString()));

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.submitResult("B-CNEG",
                        new RiskResultRequest("rr-neg", "H01", "NEGATIVE", 1,
                                clock.instant().toString())),
                () -> batchService.release("B-CNEG",
                        new ReleaseRequest("rel-1", "核销员甲")));

        BatchView view = getView("B-CNEG");
        RiskSummaryView risk = getRisk("B-CNEG");
        if ("RELEASED".equals(view.status())) {
            // 若放行，必须抽检完成且全部阴性
            assertTrue(risk.samplingCompleted());
            assertTrue(risk.allNegative());
            assertEquals(1, countRows("risk_sample"));
        } else {
            // 核销先拿到锁时门禁未完成被拒；结果随后必已落库，不存在漏检
            assertEquals("JUDGED", view.status());
            assertTrue(risk.samplingCompleted());
            assertEquals("NEGATIVE", risk.checklists().get(0).samples().get(0).result());
        }
        // 再次核销：抽检既已完成全阴，必定成功（幂等地到达终态），证明门禁只在条件满足时放行
        var secondRelease = batchService.release("B-CNEG", new ReleaseRequest("rel-2", "核销员甲"));
        assertEquals("RELEASED", secondRelease.status());
        assertEquals(1, countRows("risk_sample"));
        assertEquals("RELEASED", getView("B-CNEG").status(), "二次确认后批次稳定停留在终态");
    }

    @Test
    @DisplayName("提高等级原子扩充与最后结果并发：版本串行，恰好一个成功，结果与清单一致")
    void expandVersusLastResultSerialized() throws Exception {
        readyJudgedQualified("B-CEXP", 20);
        riskService.bind("B-CEXP", new RiskBindRequest("rb-1", "NORMAL", "RULE-1", heads(20), 0,
                clock.instant().toString()));
        // NORMAL 20 只抽 H01,H02；先登记 H01 阴性，版本为 2
        riskService.submitResult("B-CEXP", new RiskResultRequest("rr-1", "H01", "NEGATIVE", 1,
                clock.instant().toString()));

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.changeLevel("B-CEXP",
                        new RiskChangeRequest("rc-1", "TIGHTENED", "RULE-2", heads(20), 2,
                                clock.instant().toString())),
                () -> riskService.submitResult("B-CEXP",
                        new RiskResultRequest("rr-2", "H02", "NEGATIVE", 2,
                                clock.instant().toString())));

        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        assertEquals(1, successes, "乐观版本下恰好一个成功，另一个 409");
        assertEquals(1, conflicts);

        RiskSummaryView risk = getRisk("B-CEXP");
        assertEquals(3, risk.version());
        assertEquals(1L, risk.currentSequenceNo(), "原子扩充沿用原序号，结果提交不改序号");
        RiskSummaryView.ChecklistView checklist = risk.checklists().get(0);
        boolean expandedFirst = "EXPANDED".equals(checklist.kind());
        if (expandedFirst) {
            // 扩充先成功：清单扩至 6 只仍 OPEN，H02 无结果，既有 H01 阴性保留
            assertEquals(6, checklist.samples().size());
            assertEquals("OPEN", checklist.status());
            assertEquals("RULE-2", risk.currentRuleVersion());
            assertTrue(checklist.samples().stream()
                    .noneMatch(s -> "H02".equals(s.headNo()) && s.result() != null));
            assertEquals(1, countRows("risk_sample"));
        } else {
            // 结果先成功：原 2 只清单完成 COMPLETED，扩充因版本不符被拒，无部分扩充
            assertEquals(2, checklist.samples().size());
            assertEquals("COMPLETED", checklist.status());
            assertEquals("NORMAL", risk.currentLevel());
            assertEquals(2, countRows("risk_sample"));
        }
    }

    @Test
    @DisplayName("同头只阴/阳结果并发：唯一结果落库，异结果方冲突，版本与结果自洽")
    void concurrentDifferentResultsOnSameHeadOnlyOneWins() throws Exception {
        readyJudgedQualified("B-CRES", 20);
        riskService.bind("B-CRES", new RiskBindRequest("rb-1", "TIGHTENED", "RULE-1", heads(20), 0,
                clock.instant().toString()));

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.submitResult("B-CRES",
                        new RiskResultRequest("rr-neg", "H01", "NEGATIVE", 1,
                                clock.instant().toString())),
                () -> riskService.submitResult("B-CRES",
                        new RiskResultRequest("rr-pos", "H01", "POSITIVE", 1,
                                clock.instant().toString())));

        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        assertEquals(1, conflicts, "同头只异结果并发，后到者须 409");
        assertEquals(1, countRows("risk_sample"), "逐只结果唯一");

        RiskSummaryView risk = getRisk("B-CRES");
        String onlyResult = risk.checklists().get(0).samples().stream()
                .filter(s -> s.headNo().equals("H01")).findFirst().orElseThrow().result();
        if ("POSITIVE".equals(onlyResult)) {
            assertEquals("SPECIAL_QUARANTINE", getView("B-CRES").status());
            assertTrue(risk.quarantineActive());
        } else {
            assertEquals("NEGATIVE", onlyResult);
        }
    }

    @Test
    @DisplayName("同键同参并发绑定：均重放首次结果，只冻结一份清单")
    void concurrentSameRequestIdBindReplaysOnce() throws Exception {
        declareOk("d-1", "B-CBIND", heads(10));
        preQuarantineOk("B-CBIND", "pq-1");
        RiskBindRequest request = new RiskBindRequest("rb-same", "NORMAL", "RULE-1", heads(10), 0,
                clock.instant().toString());

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.bind("B-CBIND", request),
                () -> riskService.bind("B-CBIND", request));

        assertTrue(outcomes.stream().allMatch(o -> o.error() == null), "同键同参并发须均成功重放");
        assertEquals(1, countRows("risk_binding"));
        assertEquals(1, countRows("risk_checklist"));
        assertEquals(1, countRows("risk_change_event"));
        Integer riskBindKeys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE endpoint = 'risk-bind'", Integer.class);
        assertEquals(1, riskBindKeys, "同键并发只登记一条风险绑定幂等记录");
    }

    // ---------- 并发辅助（与 ConcurrencyTest 同构） ----------

    private record Outcome<T>(T value, ApiException error) {
    }

    private <T> List<Outcome<T>> runConcurrently(Supplier<T> first, Supplier<T> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Outcome<T>> taskFirst = () -> awaitAndRun(ready, start, first);
            Callable<Outcome<T>> taskSecond = () -> awaitAndRun(ready, start, second);
            Future<Outcome<T>> futureFirst = pool.submit(taskFirst);
            Future<Outcome<T>> futureSecond = pool.submit(taskSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();
            Outcome<T> outcomeFirst = futureFirst.get(15, TimeUnit.SECONDS);
            Outcome<T> outcomeSecond = futureSecond.get(15, TimeUnit.SECONDS);
            assertNotNull(outcomeFirst);
            assertNotNull(outcomeSecond);
            return List.of(outcomeFirst, outcomeSecond);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发线程未在超时内结束");
        }
    }

    private static <T> Outcome<T> awaitAndRun(CountDownLatch ready, CountDownLatch start,
                                              Supplier<T> action) throws Exception {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            return new Outcome<>(action.get(), null);
        } catch (ApiException e) {
            return new Outcome<>(null, e);
        }
    }
}
