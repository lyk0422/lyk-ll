package com.example.starter.fermentation;

import com.example.starter.fermentation.api.BatchDtos.TransferRequest;
import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiptRequest;
import com.example.starter.fermentation.error.ApiException;
import com.example.starter.fermentation.service.BatchService;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.ReceivingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合批接收 H2 并发测试：新旧核销竞争、两组争同源、同接收余量竞争，
 * 均按事务次序判定，断言最终结果与库内数据一致。
 */
@SpringBootTest
class MergeReceiptConcurrencyTest {

    private static final LocalDateTime T0 = BaseFermentationTest.T0;
    private static final LocalDateTime RECEIVE_AT = T0.plusDays(9);

    @Autowired
    private ReceivingService receivingService;

    @Autowired
    private BatchService batchService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM temperature_reading");
        jdbc.update("DELETE FROM diacetyl_test");
        jdbc.update("DELETE FROM yeast_recovery");
        jdbc.update("DELETE FROM yeast_unit");
        jdbc.update("DELETE FROM merge_receipt_source");
        jdbc.update("DELETE FROM merge_receipt");
        jdbc.update("DELETE FROM source_volume");
        jdbc.update("DELETE FROM receiving_tank");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM fermentation_batch");
    }

    @Test
    @DisplayName("新旧核销竞争：旧单笔转出与合批接收争同一受控源，唯一核销仅一方消费，结果一致")
    void oldTransferRacesMergeReceipt() throws Exception {
        long s1 = insertJudgedBatch("B-RC-1", "TANK-1");
        long s2 = insertJudgedBatch("B-RC-2", "TANK-2");
        insertVolume(s1, "40");
        insertVolume(s2, "60");
        insertTank("RC-RACE", "1000");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger mergeAccepted = new AtomicInteger();
        AtomicInteger mergeRejected = new AtomicInteger();

        Future<?> transferFuture = pool.submit((Callable<Void>) () -> {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
            // 旧单笔转出：无论竞争结果如何，终态幂等应返回 200
            StoredResponse response = batchService.transfer(s1,
                    new TransferRequest("req-race-transfer", RECEIVE_AT));
            assertEquals(200, response.status(), "旧单笔转出应返回 200（终态幂等）");
            return null;
        });
        Future<?> mergeFuture = pool.submit((Callable<Void>) () -> {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
            try {
                StoredResponse response = receivingService.merge(new MergeReceiptRequest(
                        "req-race-merge", "RC-RACE", List.of(s1, s2), RECEIVE_AT));
                assertEquals(201, response.status());
                mergeAccepted.incrementAndGet();
            } catch (ApiException e) {
                assertTrue(e.status().value() == 409 || e.status().value() == 422,
                        "合批竞争失败应为 409 或 422，实际 " + e.status().value());
                mergeRejected.incrementAndGet();
            }
            return null;
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        go.countDown();
        transferFuture.get(30, TimeUnit.SECONDS);
        mergeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池终止超时");

        assertEquals(1, mergeAccepted.get() + mergeRejected.get(), "合批仅受理一次");
        assertEquals("TRANSFERRED", batchStatus(s1), "争用的源批最终应已转出");
        BigDecimal received = receivedLiters("RC-RACE");
        if (mergeAccepted.get() == 1) {
            assertEquals(0, received.compareTo(new BigDecimal("100.000")),
                    "合批胜出时已接收量应为两源合计");
            assertEquals("TRANSFERRED", batchStatus(s2), "合批胜出时另一源应一并核销");
            assertEquals(1, receiptCount(), "合批胜出时应恰一条接收单");
        } else {
            assertEquals(0, received.compareTo(BigDecimal.ZERO),
                    "旧转出胜出时不得增加接收量");
            assertEquals("JUDGED", batchStatus(s2), "旧转出胜出时另一源应保持待转出");
            assertEquals(0, receiptCount(), "旧转出胜出时不得留下接收单");
        }
    }

    @Test
    @DisplayName("两组争同源：两组合批争同一源批，按事务次序仅一组受理，另一组全单拒绝")
    void twoGroupsRaceSameSource() throws Exception {
        long s1 = insertJudgedBatch("B-G1", "TANK-1");
        long s2 = insertJudgedBatch("B-G2", "TANK-2");
        long s3 = insertJudgedBatch("B-G3", "TANK-3");
        insertVolume(s1, "40");
        insertVolume(s2, "40");
        insertVolume(s3, "40");
        insertTank("RC-GROUP", "1000");

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runTwoConcurrent(
                () -> mergeOrReject("req-group-a", "RC-GROUP", List.of(s1, s2), accepted, rejected),
                () -> mergeOrReject("req-group-b", "RC-GROUP", List.of(s2, s3), accepted, rejected));

        assertEquals(1, accepted.get(), "争同源应仅一组受理");
        assertEquals(1, rejected.get(), "另一组应全单拒绝");
        assertEquals(0, receivedLiters("RC-GROUP").compareTo(new BigDecimal("80.000")),
                "已接收量应恰为胜出一组的合计");
        assertEquals(1, receiptCount(), "应恰一条接收单");
        assertEquals("TRANSFERRED", batchStatus(s2), "争用源应已核销");
        // 胜出一组的另一源已核销，落败一组的独有源保持 JUDGED
        int transferred = countBatchesByStatus("TRANSFERRED");
        assertEquals(2, transferred, "应恰两个源批被核销");
    }

    @Test
    @DisplayName("同接收余量竞争：两组各 60 升争 100 升容量，按事务次序仅一组受理")
    void twoGroupsRaceRemainingCapacity() throws Exception {
        long s1 = insertJudgedBatch("B-C1", "TANK-1");
        long s2 = insertJudgedBatch("B-C2", "TANK-2");
        long s3 = insertJudgedBatch("B-C3", "TANK-3");
        long s4 = insertJudgedBatch("B-C4", "TANK-4");
        insertVolume(s1, "30");
        insertVolume(s2, "30");
        insertVolume(s3, "30");
        insertVolume(s4, "30");
        insertTank("RC-MARGIN", "100");

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runTwoConcurrent(
                () -> mergeOrReject("req-margin-a", "RC-MARGIN", List.of(s1, s2), accepted, rejected),
                () -> mergeOrReject("req-margin-b", "RC-MARGIN", List.of(s3, s4), accepted, rejected));

        assertEquals(1, accepted.get(), "余量仅够一组，应恰一组受理");
        assertEquals(1, rejected.get(), "另一组应因超容量拒绝");
        assertEquals(0, receivedLiters("RC-MARGIN").compareTo(new BigDecimal("60.000")),
                "已接收量应恰为胜出一组的 60 升");
        assertEquals(1, receiptCount(), "应恰一条接收单");
        assertEquals(2, countBatchesByStatus("TRANSFERRED"), "应恰两个源批被核销");
        assertEquals(2, countBatchesByStatus("JUDGED"), "落败一组的源批应保持待转出");
        Integer version = jdbc.queryForObject(
                "SELECT version FROM receiving_tank WHERE tank_code = 'RC-MARGIN'", Integer.class);
        assertEquals(2, version, "接收落账后台账版本应恰加 1");
    }

    private void mergeOrReject(String requestId, String tankCode, List<Long> sources,
                               AtomicInteger accepted, AtomicInteger rejected) {
        try {
            StoredResponse response = receivingService.merge(
                    new MergeReceiptRequest(requestId, tankCode, sources, RECEIVE_AT));
            assertEquals(201, response.status());
            accepted.incrementAndGet();
        } catch (ApiException e) {
            assertTrue(e.status().value() == 409 || e.status().value() == 422,
                    "竞争失败应为 409 或 422，实际 " + e.status().value());
            rejected.incrementAndGet();
        }
    }

    private void runTwoConcurrent(ThrowingRunnable first, ThrowingRunnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> f1 = pool.submit((Callable<Void>) () -> {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
            first.run();
            return null;
        });
        Future<?> f2 = pool.submit((Callable<Void>) () -> {
            ready.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
            second.run();
            return null;
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程就绪超时");
        go.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池终止超时");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private long insertJudgedBatch(String batchNo, String tankCode) {
        jdbc.update("INSERT INTO fermentation_batch (batch_no, tank_id, curve_code, curve_version,"
                        + " status, filled_at, maturation_extended, judged_at, active_tank_key)"
                        + " VALUES (?, (SELECT id FROM fermentation_tank WHERE tank_code = ?),"
                        + " 'LAGER-CURVE', 1, 'JUDGED', ?, 0, ?, ?)",
                batchNo, tankCode, Timestamp.valueOf(T0),
                Timestamp.valueOf(T0.plusDays(8)), tankCode);
        Long id = jdbc.queryForObject(
                "SELECT id FROM fermentation_batch WHERE batch_no = ?", Long.class, batchNo);
        return id;
    }

    private void insertVolume(long batchId, String volumeLiters) {
        jdbc.update("INSERT INTO source_volume (batch_id, volume_liters, version, created_at)"
                        + " VALUES (?, ?, 1, ?)",
                batchId, new BigDecimal(volumeLiters), Timestamp.valueOf(T0));
    }

    private void insertTank(String tankCode, String capacityLiters) {
        jdbc.update("INSERT INTO receiving_tank (tank_code, capacity_liters, received_liters,"
                        + " version, created_at) VALUES (?, ?, 0, 1, ?)",
                tankCode, new BigDecimal(capacityLiters), Timestamp.valueOf(T0));
    }

    private String batchStatus(long batchId) {
        return jdbc.queryForObject("SELECT status FROM fermentation_batch WHERE id = ?",
                String.class, batchId);
    }

    private BigDecimal receivedLiters(String tankCode) {
        return jdbc.queryForObject("SELECT received_liters FROM receiving_tank WHERE tank_code = ?",
                BigDecimal.class, tankCode);
    }

    private int receiptCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM merge_receipt", Integer.class);
        return count;
    }

    private int countBatchesByStatus(String status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fermentation_batch WHERE status = ?", Integer.class, status);
        return count;
    }
}
