package com.example.starter.cosmetic.child.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.child.testsupport.ChildFilingFixtures;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitFilingRequest;
import com.example.starter.cosmetic.service.ConflictException;
import com.example.starter.cosmetic.support.FingerprintSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 儿童分龄 H2 真实并发/幂等测试：
 * <ul>
 *   <li>补正（旧修订 VOID）与公示期满生效（旧修订 EFFECTIVE）并发竞争，必须唯一终态；</li>
 *   <li>同一 requestId 并发补正，仅落一个新修订，两个调用都拿首次冻结结果；</li>
 *   <li>结构化指纹无歧义：合法文本含分隔符也能区分集合/字段边界。</li>
 * </ul>
 */
class ChildFilingConcurrencyIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private ChildFilingService childService;

    @Autowired
    private ChildFilingPort childPort;

    @Autowired
    private ChildPublicNoticeEffector effector;

    private static final Instant EXPIRES = Instant.parse("2026-07-10T00:00:00Z");

    /**
     * 公示中修订在恰期满时刻同时发生“期满生效”和“材料补正使旧修订失效”，
     * 多轮执行确认：旧修订只能是 EFFECTIVE 或 VOID 之一，补正新修订存在与否与之严格对应。
     */
    @Test
    void expiryAndCorrectionRaceProducesExactlyOneOutcome() throws Exception {
        int rounds = 15;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            String product = "CP-RACE-" + round;
            // 首次跨档缺大龄档 → 驳回；补正为合格跨档材料 → v2 进入公示
            ChildFilingResultResponse rejected = childService.submit(
                    ChildFilingFixtures.crossTierMissingOldReport("REQ-R-" + round + "A", product));
            String no = rejected.filing().filingNo();
            childService.correct(no, ChildFilingFixtures.crossTierCorrection("REQ-R-" + round + "B", 1));
            ChildFilingRecord publicV2 = childPort.findVersion(no, 2).orElseThrow();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger expiryWins = new AtomicInteger();
            AtomicInteger correctionWins = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            Runnable expiryTask = () -> {
                try {
                    start.await();
                    if (effector.effectuate(publicV2, EXPIRES)) {
                        expiryWins.incrementAndGet();
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };
            Runnable correctionTask = () -> {
                try {
                    start.await();
                    // 对公示中的 v2 再补正（材料仍合格）→ v3，旧 v2 应 VOID
                    ChildCorrectFilingRequest req =
                            ChildFilingFixtures.crossTierCorrection("REQ-R-" + round + "C", 2);
                    childService.correct(no, req);
                    correctionWins.incrementAndGet();
                } catch (ConflictException | ChildBusinessRejectionException
                         | org.springframework.dao.DataIntegrityViolationException e) {
                    // 期满分支已获胜：v2 已 EFFECTIVE，补正被拒/回滚，不计异常
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            };

            Thread t1 = new Thread(expiryTask, "expiry-" + round);
            Thread t2 = new Thread(correctionTask, "correction-" + round);
            t1.start();
            t2.start();
            start.countDown();
            t1.join(15_000);
            t2.join(15_000);

            assertThat(error.get()).as("第 %d 轮无未预期异常: %s", round, error.get()).isNull();
            assertThat(expiryWins.get() + correctionWins.get())
                    .as("第 %d 轮期满与补正必须恰好一个获胜", round).isEqualTo(1);

            ChildFilingStatus v2Status = childPort.findVersion(no, 2).orElseThrow().status();
            if (expiryWins.get() == 1) {
                assertThat(v2Status).isEqualTo(ChildFilingStatus.EFFECTIVE);
                assertThat(childPort.findActiveSlot(product)).isPresent();
            } else {
                assertThat(v2Status).isEqualTo(ChildFilingStatus.VOID);
                // 补正产生 v3 公示件；旧 v2 失效，槽位仍空（v3 尚未期满）
                assertThat(childPort.findVersion(no, 3).orElseThrow().status())
                        .isEqualTo(ChildFilingStatus.PUBLIC);
                assertThat(childPort.findActiveSlot(product)).isEmpty();
            }
        }
    }

    /**
     * 同一 requestId 同参数并发补正：只生成一个新修订，两个调用都返回同一首次冻结结果。
     */
    @Test
    void concurrentSameKeyCorrectionsAreIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String product = "CP-CONC-" + round;
                childService.submit(
                        ChildFilingFixtures.crossTierMissingOldReport("REQ-CC-" + round + "A", product));
                String no = childPort.findByProduct(product).get(0).filingNo();
                ChildCorrectFilingRequest req =
                        ChildFilingFixtures.crossTierCorrection("REQ-CC-" + round + "K", 1);

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<ChildFilingResultResponse> responses =
                        Collections.synchronizedList(new ArrayList<>());
                AtomicReference<Throwable> error = new AtomicReference<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        responses.add(childService.correct(no, req));
                    } catch (Throwable t) {
                        error.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                };
                Future<?> f1 = pool.submit(task);
                Future<?> f2 = pool.submit(task);
                start.countDown();
                boolean finished = done.await(30, TimeUnit.SECONDS);
                f1.get(1, TimeUnit.SECONDS);
                f2.get(1, TimeUnit.SECONDS);

                assertThat(finished).as("第 %d 轮并发补正在超时前完成", round).isTrue();
                assertThat(error.get()).isNull();
                assertThat(responses).hasSize(2);
                long replays = responses.stream().filter(ChildFilingResultResponse::idempotentReplay).count();
                long firsts = responses.stream().filter(r -> !r.idempotentReplay()).count();
                assertThat(replays).isEqualTo(1);
                assertThat(firsts).isEqualTo(1);
                assertThat(responses.get(0).filing().versionNo())
                        .isEqualTo(responses.get(1).filing().versionNo())
                        .isEqualTo(2);
                assertThat(childPort.findByProduct(product)).hasSize(2);
                Integer idem = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM child_idempotency_record WHERE request_id = ?",
                        Integer.class, "REQ-CC-" + round + "K");
                assertThat(idem).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 结构化指纹无歧义：把分隔符从一个字段挪入文本内容，指纹必须不同；
     * 合法文本含逗号/引号/花括号也不会与多值集合碰撞。
     */
    @Test
    void structuredFingerprintDistinguishesDelimiterInsideLegalText() {
        // 场景A：单个宣称内含分隔符文本
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("claims", List.of("CHILD_MOISTURIZING,CHILD_SUNSCREEN"));
        a.put("label", "适用年龄 0-36 月");
        // 场景B：拆成两个宣称，label 不含分隔符 —— 语义不同，指纹必须不同
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("claims", List.of("CHILD_MOISTURIZING", "CHILD_SUNSCREEN"));
        b.put("label", "适用年龄 0-36 月");
        assertThat(FingerprintSupport.sha256(a)).isNotEqualTo(FingerprintSupport.sha256(b));

        // 文本中含 JSON 结构性字符（引号、花括号、方括号）也不影响字段边界
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("claims", List.of("CHILD_MOISTURIZING"));
        c.put("label", "适用年龄 0-36 月\", \"x\": [");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("claims", List.of("CHILD_MOISTURIZING"));
        d.put("label", "适用年龄 0-36 月");
        assertThat(FingerprintSupport.sha256(c)).isNotEqualTo(FingerprintSupport.sha256(d));
        // 相同结构化输入稳定同指纹
        assertThat(FingerprintSupport.sha256(new LinkedHashMap<>(c)))
                .isEqualTo(FingerprintSupport.sha256(new LinkedHashMap<>(c)));
    }

    /** 失败（业务拒绝 422）不占幂等键：事务回滚后无 child_idempotency_record 残留。 */
    @Test
    void rejectedCorrectionRollsBackClaimAndKeyCanBeReusedAfterStateFixed() {
        // 提交一个合格低龄件并生效
        ChildFilingResultResponse submitted =
                childService.submit(ChildFilingFixtures.youngSubmit("REQ-RB-1", "CP-RB"));
        clock.setInstant(EXPIRES);
        childService.expireDuePublicNotices(null);

        // 生效后补正被业务拒绝（422），事务回滚，键不占用
        ChildCorrectFilingRequest req = ChildFilingFixtures.crossTierCorrection("REQ-RB-K", 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> childService.correct(submitted.filing().filingNo(), req))
                .isInstanceOf(ChildBusinessRejectionException.class);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM child_idempotency_record WHERE request_id = ?",
                Integer.class, "REQ-RB-K");
        assertThat(rows).isZero();
    }
}
