package com.example.starter.metro;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.metro.dto.EntryRequest;
import com.example.starter.metro.dto.EntryResponse;
import com.example.starter.metro.dto.ExitRequest;
import com.example.starter.metro.dto.ExitResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与幂等边界测试：真实多线程并发调用业务服务，验证唯一约束、
 * 幂等重放与批次汇总在并发下的一致性。
 */
@SpringBootTest
class MetroConcurrencyTest {

    @Autowired
    private MetroService metroService;

    @Autowired
    private MetroRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM clearing_detail");
        jdbcTemplate.update("DELETE FROM clearing_discrepancy");
        jdbcTemplate.update("DELETE FROM metro_transaction");
        jdbcTemplate.update("DELETE FROM clearing_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    private <T> List<T> runConcurrently(int threads, java.util.function.IntFunction<T> task)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return task.apply(index);
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void concurrentSameKeyEntryReplaysSingleResult() throws Exception {
        EntryRequest request = new EntryRequest("req-c1", "T-C1", "CARD-C1", "A",
                LocalDateTime.of(2026, 9, 25, 9, 0));
        List<EntryResponse> responses = runConcurrently(8, i -> metroService.entry(request));

        long fresh = responses.stream().filter(r -> !r.replayed()).count();
        assertEquals(1L, fresh, "恰好一个请求实际入账，其余为重放");
        for (EntryResponse response : responses) {
            assertEquals("T-C1", response.txnNo());
            assertEquals("ENTERED", response.status());
        }
        Integer txnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metro_transaction WHERE txn_no = 'T-C1'", Integer.class);
        assertEquals(1, txnCount);
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE scope = 'ENTRY' AND request_id = 'req-c1'",
                Integer.class);
        assertEquals(1, idemCount);
    }

    @Test
    void concurrentDuplicateTxnNoAllowsExactlyOne() throws Exception {
        List<Object> outcomes = runConcurrently(8, i -> {
            try {
                return metroService.entry(new EntryRequest("req-c2-" + i, "T-C2", "CARD-C2", "A",
                        LocalDateTime.of(2026, 9, 25, 9, 0)));
            } catch (MetroException ex) {
                return ex;
            }
        });
        long successes = outcomes.stream().filter(o -> o instanceof EntryResponse).count();
        assertEquals(1L, successes, "重复流水号并发下只允许一笔入账");
        long conflicts = outcomes.stream()
                .filter(o -> o instanceof MetroException)
                .map(o -> (MetroException) o)
                .filter(ex -> ex.getStatus() == HttpStatus.CONFLICT
                        && "DUPLICATE_TXN_NO".equals(ex.getCode()))
                .count();
        assertEquals(7L, conflicts);
        Integer txnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metro_transaction WHERE txn_no = 'T-C2'", Integer.class);
        assertEquals(1, txnCount);
    }

    @Test
    void concurrentExitsShareSingleBatchWithConsistentTotal() throws Exception {
        int trips = 6;
        for (int i = 0; i < trips; i++) {
            metroService.entry(new EntryRequest("req-c3-e" + i, "T-C3-" + i, "CARD-C3-" + i, "A",
                    LocalDateTime.of(2026, 9, 25, 9, 0)));
        }
        List<ExitResponse> responses = runConcurrently(trips, i -> metroService.exit(
                new ExitRequest("req-c3-x" + i, "T-C3-" + i, "D",
                        LocalDateTime.of(2026, 9, 25, 9, 30))));

        for (ExitResponse response : responses) {
            assertEquals(400L, response.fareCents());
            assertTrue(!response.lateDiscrepancy());
        }
        Integer batchCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clearing_batch WHERE biz_date = DATE '2026-09-25'",
                Integer.class);
        assertEquals(1, batchCount, "并发入账共享同一批次");
        Long total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM clearing_detail", Long.class);
        assertEquals(400L * trips, total);
        var batch = repository.findBatchByDate(LocalDate.of(2026, 9, 25)).orElseThrow();
        assertEquals(trips, repository.countTxnsInBatch(batch.id()));
    }
}
