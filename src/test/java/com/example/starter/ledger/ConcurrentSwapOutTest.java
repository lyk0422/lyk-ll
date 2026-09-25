package com.example.starter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ledger.domain.BatteryStatus;
import com.example.starter.ledger.domain.EventType;
import com.example.starter.ledger.po.LedgerEventPO;
import com.example.starter.ledger.repo.EventRepository;
import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.web.BatteryResponse;
import com.example.starter.ledger.web.SwapOutRequest;
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
 * 并发换出：同一电池多请求并发换出，唯一成功；在途期间并发换出/调拨互斥。
 */
class ConcurrentSwapOutTest extends AbstractLedgerIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private EventRepository eventRepository;

    private SwapOutRequest swapRequest(String requestId, String code, String vehicle) {
        SwapOutRequest request = new SwapOutRequest();
        request.setRequestId(requestId);
        request.setBatteryCode(code);
        request.setStationCode(LedgerFixtures.STATION_A);
        request.setVehicleCode(vehicle);
        return request;
    }

    @Test
    void concurrentSwapOutExactlyOneSucceeds() throws Exception {
        String code = "BAT-CONCURRENT";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                final String requestId = "swap-" + i;
                final String vehicle = "VEH-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        var result = ledgerService.swapOut(swapRequest(requestId, code, vehicle));
                        success.incrementAndGet();
                        return result.getData().getBoundVehicle().equals(vehicle);
                    } catch (RuntimeException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isIn(true, false);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(1);
        BatteryResponse battery = ledgerService.getBattery(code);
        assertThat(battery.getStatus()).isEqualTo(BatteryStatus.IN_USE.name());
        assertThat(battery.getCurrentStation()).isNull();
        assertThat(battery.getBoundVehicle()).startsWith("VEH-");

        List<LedgerEventPO> swapEvents = eventRepository.findByBattery(code).stream()
                .filter(event -> EventType.SWAP_OUT.name().equals(event.getEventType()))
                .toList();
        assertThat(swapEvents).hasSize(1);
    }

    @Test
    void concurrentSwapOutWithSameRequestIdAllReturnFirstResult() throws Exception {
        String code = "BAT-IDEMPOTENT-CONCURRENT";
        LedgerFixtures.readyAvailableAtA(ledgerService, code);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    var result = ledgerService.swapOut(swapRequest("same-request", code, "VEH-X"));
                    return result.getData().getBoundVehicle();
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo("VEH-X");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        List<LedgerEventPO> swapEvents = eventRepository.findByBattery(code).stream()
                .filter(event -> EventType.SWAP_OUT.name().equals(event.getEventType()))
                .toList();
        assertThat(swapEvents).hasSize(1);
    }
}
