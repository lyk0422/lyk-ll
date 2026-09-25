package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ledger.domain.BatteryStatus;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.web.TransferDispatchRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 并发调拨：同一电池并发发起调拨（不同 requestId）唯一成功，其余 409，电池进入在途。
 */
class ConcurrentTransferDispatchTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Test
    void concurrentDispatchExactlyOneSucceeds() throws Exception {
        String code = "BAT-CONCURRENT-DISPATCH";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    TransferDispatchRequest request = new TransferDispatchRequest();
                    request.setRequestId("cd-" + index);
                    request.setBatteryCode(code);
                    request.setFromStation(LedgerFixtures.STATION_A);
                    request.setToStation(index % 2 == 0 ? LedgerFixtures.STATION_B : "ST-C");
                    try {
                        ledgerService.dispatchTransfer(request);
                        success.incrementAndGet();
                        return true;
                    } catch (RuntimeException e) {
                        conflict.incrementAndGet();
                        return false;
                    }
                }));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(ledgerService.getBattery(code).getStatus()).isEqualTo(BatteryStatus.IN_TRANSIT.name());
    }
}
