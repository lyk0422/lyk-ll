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
import com.example.starter.service.RiskService;
import com.example.starter.service.TruckService;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.JudgmentRequest.JudgmentItem;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.TruckCreateRequest;
import com.example.starter.web.dto.TruckManifestActionRequest;
import com.example.starter.web.dto.TruckManifestResponse;
import com.example.starter.web.dto.TruckProgressView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分车出厂并发边界（真实并发线程 + H2 行锁/唯一约束）：同头只争车只一方成功且缺项全单不占、
 * 撤销与发车不能既释放又出厂、末车并发逐车核销恰收口、抽检阳性/最后阴性与发车遵守原门禁竞态。
 */
class TruckConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private TruckService truckService;

    @Autowired
    private RiskService riskService;

    private static List<String> heads(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(String.format("H%02d", i));
        }
        return list;
    }

    private List<JudgmentItem> qualifiedItems(int n) {
        return heads(n).stream().map(h -> new JudgmentItem(h, "QUALIFIED", null, null)).toList();
    }

    private void readyQualified(String batchNo, int n) throws Exception {
        judgeAll(batchNo, batchNo.toLowerCase(), qualifiedItems(n));
    }

    @Test
    @DisplayName("同头只争车：并发建两车单都含H01，恰好一方成功，失败方全单不占")
    void contendedHeadsOnlyOneTruckWinsAllOrNothing() throws Exception {
        readyQualified("B-WIN", 10);
        TruckCreateRequest a = new TruckCreateRequest("tc-a", "T-A", List.of("H01", "H02"),
                clock.instant().toString());
        TruckCreateRequest b = new TruckCreateRequest("tc-b", "T-B", List.of("H01", "H03"),
                clock.instant().toString());

        List<Outcome<TruckManifestResponse>> outcomes = runConcurrently(
                () -> truckService.createManifest("B-WIN", a),
                () -> truckService.createManifest("B-WIN", b));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        assertEquals(1, successes, "同头只争车只一方成功");
        assertEquals(1, conflicts, "未占用方须 409");
        // 成功车单仅一行，且失败方没有任何头只落库（缺一项全单不占）
        assertEquals(1, countRows("truck_manifest"));
        assertEquals(2, countRows("truck_manifest_head"));
    }

    @Test
    @DisplayName("撤销与发车并发：只一个结局，不会既释放又出厂")
    void revokeAndDispatchMutuallyExclusive() throws Exception {
        readyQualified("B-RD", 4);
        createTruck("tc-1", "B-RD", "T-1", List.of("H01", "H02"));
        TruckManifestActionRequest revoke = new TruckManifestActionRequest("tr-1", 1,
                clock.instant().toString());
        TruckManifestActionRequest dispatch = new TruckManifestActionRequest("td-1", 1,
                clock.instant().toString());

        List<Outcome<TruckManifestResponse>> outcomes = runConcurrently(
                () -> truckService.revokeManifest("B-RD", "T-1", revoke),
                () -> truckService.dispatchManifest("B-RD", "T-1", dispatch));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        assertEquals(1, successes, "撤销与发车互斥，恰好一方成功");
        assertEquals(1, conflicts);

        // 终态唯一：要么 REVOKED（占用全删），要么 RELEASED（占用全部随车出厂），二者不可兼得
        TruckProgressView view = getTrucks("B-RD");
        TruckProgressView.ManifestView manifest = view.manifests().get(0);
        if ("REVOKED".equals(manifest.status())) {
            assertEquals(2, manifest.version());
            assertEquals(0, countRows("truck_manifest_head"), "撤销成功须释放全部预占");
            assertEquals(List.of("H01", "H02", "H03", "H04"), view.unshipped());
        } else {
            assertEquals("RELEASED", manifest.status());
            assertEquals(2, manifest.version());
            assertEquals(2, countRows("truck_manifest_head"), "发车成功不得删除预占，占用转 RELEASED");
            assertEquals(List.of("H01", "H02"), view.shipped());
            assertEquals(List.of("H03", "H04"), view.unshipped());
        }
        assertEquals("JUDGED", getView("B-RD").status(), "仍有合格头只未出厂，批次不得收口");
    }

    @Test
    @DisplayName("不重叠两车并发逐车核销：均成功且末车恰核销完批次收口")
    void concurrentDisjointTrucksCloseBatchOnLastTruck() throws Exception {
        readyQualified("B-LAST", 4);
        createTruck("tc-1", "B-LAST", "T-1", List.of("H01", "H02"));
        createTruck("tc-2", "B-LAST", "T-2", List.of("H03", "H04"));
        TruckManifestActionRequest d1 = new TruckManifestActionRequest("td-1", 1,
                clock.instant().toString());
        TruckManifestActionRequest d2 = new TruckManifestActionRequest("td-2", 1,
                clock.instant().toString());

        List<Outcome<TruckManifestResponse>> outcomes = runConcurrently(
                () -> truckService.dispatchManifest("B-LAST", "T-1", d1),
                () -> truckService.dispatchManifest("B-LAST", "T-2", d2));

        assertTrue(outcomes.stream().allMatch(o -> o.error() == null), "不重叠两车并发核销均须成功");
        TruckProgressView view = getTrucks("B-LAST");
        assertTrue(view.manifests().stream().allMatch(m -> "RELEASED".equals(m.status())));
        assertEquals(4, view.progress().shippedCount());
        assertEquals(0, view.progress().unshippedCount());
        assertTrue(view.progress().batchReleased(), "全部合格头只恰核销完，末车收口转 RELEASED");
        assertEquals("RELEASED", getView("B-LAST").status());
    }

    @Test
    @DisplayName("阳性结果与末车发车并发：绝不放行阳性批次，失败发车保留原预占")
    void positiveVersusDispatchNeverReleases() throws Exception {
        readyQualified("B-POSD", 10);
        bindRisk("rb-1", "B-POSD", "NORMAL", "RULE-1", heads(10), 0);
        createTruck("tc-1", "B-POSD", "T-1", heads(10));

        RiskResultRequest positive = new RiskResultRequest("rr-pos", "H01", "POSITIVE", 1,
                clock.instant().toString());
        TruckManifestActionRequest dispatch = new TruckManifestActionRequest("td-1", 1,
                clock.instant().toString());

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.submitResult("B-POSD", positive),
                () -> truckService.dispatchManifest("B-POSD", "T-1", dispatch));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream().filter(o -> o.error() != null && o.error().status() == 409)
                .count();
        assertEquals(1, successes, "阳性提交必成功，发车须被门禁拒绝");
        assertEquals(1, conflicts);

        BatchView batch = getView("B-POSD");
        assertEquals("SPECIAL_QUARANTINE", batch.status(), "阳性批次必须停留在专项隔离");
        TruckProgressView view = getTrucks("B-POSD");
        assertEquals("RESERVED", view.manifests().get(0).status(), "被拒发车不得改变车单");
        assertEquals(10, countRows("truck_manifest_head"), "发车失败保留原预占");
    }

    @Test
    @DisplayName("最后阴性结果与末车发车并发：放行当且仅当门禁已满足，否则不漏检")
    void lastNegativeVersusDispatchConsistent() throws Exception {
        readyQualified("B-NEGD", 10);
        bindRisk("rb-1", "B-NEGD", "NORMAL", "RULE-1", heads(10), 0);
        createTruck("tc-1", "B-NEGD", "T-1", heads(10));

        RiskResultRequest negative = new RiskResultRequest("rr-neg", "H01", "NEGATIVE", 1,
                clock.instant().toString());
        TruckManifestActionRequest dispatch = new TruckManifestActionRequest("td-1", 1,
                clock.instant().toString());

        List<Outcome<Object>> outcomes = runConcurrently(
                () -> riskService.submitResult("B-NEGD", negative),
                () -> truckService.dispatchManifest("B-NEGD", "T-1", dispatch));

        BatchView batch = getView("B-NEGD");
        if ("RELEASED".equals(batch.status())) {
            // 发车后到：抽检必已完成且全阴
            TruckManifestResponse resp = outcomes.stream()
                    .filter(o -> o.error() == null && o.value() instanceof TruckManifestResponse)
                    .map(o -> (TruckManifestResponse) o.value())
                    .filter(v -> "RELEASED".equals(v.status())).findFirst().orElseThrow();
            assertEquals(10, resp.progress().shippedCount());
        } else {
            // 发车先到：门禁未完成被拒，阴性随后落库，车单仍 RESERVED，不存在漏检放行
            assertEquals("JUDGED", batch.status());
            assertEquals("RESERVED", getTrucks("B-NEGD").manifests().get(0).status());
            // 抽检既已完成全阴，再次发车必成功收口，证明门禁只在条件满足时放行
            TruckManifestResponse again = truckService.dispatchManifest("B-NEGD", "T-1",
                    new TruckManifestActionRequest("td-2", 1, clock.instant().toString()));
            assertEquals("RELEASED", again.status());
            assertTrue(again.progress().batchReleased());
        }
        assertEquals("RELEASED", getView("B-NEGD").status());
    }

    // ---------- 并发辅助（与 RiskConcurrencyTest 同构） ----------

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
