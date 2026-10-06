package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.service.ReceiptService;
import com.example.starter.service.WeighingService;
import com.example.starter.support.BizException;

/**
 * 处理设施回执闭环：2% 偏差边界（恰 2% 一致、超过转异常）、幂等与并发唯一回执。
 */
class ReceiptServiceTest extends LedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    @Autowired
    private ReceiptService receiptService;

    /**
     * 登记一张进场净重 5000kg 的执行中联单（毛重 13000 - 皮重 8000）。
     */
    private long registerInTransitManifest(String requestId) {
        long vehicleId = createVehicle();
        insertTare(vehicleId, new BigDecimal("8000.000"), clock.instant());
        return weighingService.registerEntry(entryCommand(requestId, vehicleId,
                new BigDecimal("13000.000"), null)).manifestId();
    }

    @Test
    void deviationExactlyTwoPercentIsConsistent() {
        long manifestId = registerInTransitManifest("REQ-E2-ENTRY");

        // 进场 5000kg，回执 5100kg -> 偏差恰 2%，视为一致
        var result = receiptService.registerReceipt(manifestId, "REQ-E2-RECEIPT", new BigDecimal("5100.000"));

        assertThat(result.result()).isEqualTo("MATCH");
        assertThat(result.deviationPct()).isEqualByComparingTo("2.000000");
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
    }

    @Test
    void deviationAboveTwoPercentGoesToException() {
        long manifestId = registerInTransitManifest("REQ-G2-ENTRY");

        // 回执 5101kg -> 偏差 2.02% > 2%，转异常核查
        var result = receiptService.registerReceipt(manifestId, "REQ-G2-RECEIPT", new BigDecimal("5101.000"));

        assertThat(result.result()).isEqualTo("EXCEPTION");
        assertThat(result.deviationPct()).isEqualByComparingTo("2.020000");
        assertThat(manifestStatus(manifestId)).isEqualTo("EXCEPTION");
    }

    @Test
    void receiptIsIdempotentByRequestId() {
        long manifestId = registerInTransitManifest("REQ-RIDEM-ENTRY");
        var first = receiptService.registerReceipt(manifestId, "REQ-RIDEM", new BigDecimal("5050.000"));

        var replay = receiptService.registerReceipt(manifestId, "REQ-RIDEM", new BigDecimal("5050.000"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(first.receiptId());
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
    }

    @Test
    void receiptSameRequestIdDifferentParamsIsConflict() {
        long manifestId = registerInTransitManifest("REQ-R409-ENTRY");
        receiptService.registerReceipt(manifestId, "REQ-R409", new BigDecimal("5050.000"));

        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-R409", new BigDecimal("9999.000")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void receiptOnClosedManifestIsRejected() {
        long manifestId = registerInTransitManifest("REQ-CLOSED-ENTRY");
        receiptService.registerReceipt(manifestId, "REQ-CLOSED-R1", new BigDecimal("5000.000"));

        assertThatThrownBy(() -> receiptService.registerReceipt(manifestId, "REQ-CLOSED-R2",
                new BigDecimal("5000.000")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MANIFEST_NOT_IN_TRANSIT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void receiptOnMissingManifestIsNotFound() {
        assertThatThrownBy(() -> receiptService.registerReceipt(999999L, "REQ-NOPE", new BigDecimal("100.000")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("MANIFEST_NOT_FOUND");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void concurrentReceiptsForSameManifestAllowExactlyOne() throws Exception {
        long manifestId = registerInTransitManifest("REQ-RCONC-ENTRY");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    receiptService.registerReceipt(manifestId, "REQ-RCONC-" + index, new BigDecimal("5050.000"));
                    return "OK";
                } catch (BizException e) {
                    return e.getCode();
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        Integer receiptRows = jdbc.sql("SELECT COUNT(*) FROM receipt WHERE manifest_id = :id")
                .param("id", manifestId)
                .query(Integer.class)
                .single();
        assertThat(receiptRows).isEqualTo(1);
        assertThat(manifestStatus(manifestId)).isEqualTo("RECEIVED");
    }
}
