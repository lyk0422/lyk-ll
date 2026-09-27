package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.AbstractCosmeticIntegrationTest;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.testsupport.FilingFixtures;
import com.example.starter.cosmetic.web.dto.CorrectionFilingRequest;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import com.example.starter.cosmetic.web.dto.SubmitFilingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 儿童备案 H2 真实并发测试：
 * <ul>
 *   <li>同一 requestId 同参数并发提交儿童备案：仅落一条记录，双方拿到同一首次结果；</li>
 *   <li>并发补正同一待补正备案：恰好一个新修订生效（唯一版本约束 + expectedVersion），
 *       旧修订仅被失效一次。</li>
 * </ul>
 */
class ChildFilingConcurrencyIntegrationTest extends AbstractCosmeticIntegrationTest {

    @Autowired
    private FilingService filingService;

    @Autowired
    private FilingPort filingPort;

    /**
     * 同一 requestId 同参数并发提交儿童备案：数据库主键冲突 + 等待回填保证只有一条备案，
     * 两个调用方都返回同一首次结果。
     */
    @Test
    void concurrentSameRequestChildSubmissionsAreIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                String requestId = "REQ-CCONC-" + round;
                String product = "P-CCONC-" + round;
                SubmitFilingRequest submit = FilingFixtures.childSubmitCrossBand(requestId, product);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<FilingResultResponse> responses =
                        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                AtomicReference<Throwable> error = new AtomicReference<>();

                Runnable task = () -> {
                    try {
                        start.await();
                        responses.add(filingService.submit(submit));
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

                assertThat(finished).as("第 %d 轮并发提交应在超时前完成", round).isTrue();
                assertThat(error.get()).isNull();
                assertThat(responses).hasSize(2);
                long replays = responses.stream().filter(FilingResultResponse::idempotentReplay).count();
                assertThat(replays).isEqualTo(1);
                assertThat(responses.get(0).filing().filingNo())
                        .isEqualTo(responses.get(1).filing().filingNo());
                assertThat(filingPort.findByProduct(product)).hasSize(1);
                Integer idemCount = jdbc.queryForObject(
                        "SELECT COUNT(1) FROM idempotency_record WHERE request_id = ?",
                        Integer.class, requestId);
                assertThat(idemCount).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 并发补正同一待补正儿童备案（不同 requestId、相同 expectedVersion=1）：
     * 恰好一个补正成功生成 v2；另一个因版本唯一约束或 expectedVersion 失配失败回滚。
     * 最终旧修订 v1 恰好被失效一次，公示修订唯一。
     */
    @Test
    void concurrentCorrectionsProduceExactlyOneNewRevision() throws Exception {
        for (int round = 0; round < 8; round++) {
            String product = "P-CRACE-" + round;
            FilingResultResponse rejected = filingService.submit(new SubmitFilingRequest(
                    "REQ-CRACE-S-" + round, product, "合成儿童乳",
                    FilingFixtures.ordinaryFormulation(), List.of("MOISTURIZING"), List.of(),
                    "CHILD", 0, 36, List.of()));
            assertThat(rejected.filing().status()).isEqualTo(FilingStatus.FORM_REJECTED.name());
            String filingNo = rejected.filing().filingNo();

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();

            final int roundNo = round;
            Runnable taskA = () -> runCorrection(filingNo, "REQ-CRACE-A-" + roundNo, start,
                    success, conflicts, error);
            Runnable taskB = () -> runCorrection(filingNo, "REQ-CRACE-B-" + roundNo, start,
                    success, conflicts, error);

            Thread t1 = new Thread(taskA);
            Thread t2 = new Thread(taskB);
            t1.start();
            t2.start();
            start.countDown();
            t1.join(15_000);
            t2.join(15_000);

            assertThat(error.get()).isNull();
            // 恰好一个补正成功；另一个失败（版本冲突/唯一约束），事务回滚
            assertThat(success.get() + conflicts.get()).isEqualTo(2);
            assertThat(success.get()).isEqualTo(1);

            // 最终：v1 旧修订失效（SUPERSEDED），v2 为唯一新修订（公示中）
            assertThat(filingPort.findAllVersions(filingNo)).hasSize(2);
            assertThat(filingPort.findVersion(filingNo, 1).orElseThrow().status())
                    .isEqualTo(FilingStatus.SUPERSEDED);
            assertThat(filingPort.findVersion(filingNo, 2).orElseThrow().status())
                    .isEqualTo(FilingStatus.PUBLIC);
        }
    }

    private void runCorrection(String filingNo, String requestId, CountDownLatch start,
                               AtomicInteger success, AtomicInteger conflicts,
                               AtomicReference<Throwable> error) {
        try {
            start.await();
            filingService.correct(filingNo, new CorrectionFilingRequest(requestId, 1,
                    null, null, null, null, null, null, null,
                    List.of(FilingFixtures.band036Dossier("CS-" + requestId, 0, 36))));
            success.incrementAndGet();
        } catch (ConflictException | UnprocessableException | DataIntegrityViolationException e) {
            // 版本失配（409）、状态已迁移（422）或 (filing_no, version_no) 唯一约束竞争：输家事务回滚
            conflicts.incrementAndGet();
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        }
    }
}
