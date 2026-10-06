package com.example.starter.waste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 并发边界：同一车辆并发登记仅一条执行中联单；同键同参并发提交仅落一条联单。
 */
class ConcurrencyTest extends WasteLedgerTestSupport {

    @Autowired
    private WeighingService weighingService;

    /**
     * 并发提交若干进场登记，返回成功响应与业务异常两类结果。
     */
    private record Outcomes(List<ManifestResponse> successes, List<ApiException> conflicts) {
    }

    private Outcomes runConcurrentEntries(List<EntryWeighingRequest> requests) throws Exception {
        int threads = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (EntryWeighingRequest request : requests) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return weighingService.registerEntry(request);
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        List<ManifestResponse> successes = new ArrayList<>();
        List<ApiException> conflicts = new ArrayList<>();
        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            if (outcome instanceof ManifestResponse response) {
                successes.add(response);
            } else {
                conflicts.add((ApiException) outcome);
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return new Outcomes(successes, conflicts);
    }

    @Test
    @DisplayName("同一车辆并发登记仅一条成功，其余 409 一车一单")
    void concurrentEntriesSameVehicleOnlyOneWins() throws Exception {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());

        List<EntryWeighingRequest> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(entryRequest(newRequestId("entry"), plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null));
        }

        Outcomes outcomes = runConcurrentEntries(requests);

        assertEquals(1, outcomes.successes().size());
        assertEquals(5, outcomes.conflicts().size());
        for (ApiException conflict : outcomes.conflicts()) {
            assertEquals(HttpStatus.CONFLICT, conflict.status());
            assertEquals("VEHICLE_BUSY", conflict.code());
        }
        assertEquals(1, countRows("manifest"));
        Long inProgress = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest WHERE status = 'IN_PROGRESS'", Long.class);
        assertEquals(1, inProgress);
    }

    @Test
    @DisplayName("同键同参并发提交幂等收敛为一条联单")
    void concurrentSameRequestIdConvergesToSingleManifest() throws Exception {
        String plate = newPlate();
        givenVehicle(plate, "8000", LocalDateTime.now());
        String requestId = newRequestId("entry");
        EntryWeighingRequest request = entryRequest(requestId, plate, "厨余垃圾", FACILITY_KITCHEN, "9000", null);

        List<EntryWeighingRequest> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(request);
        }

        Outcomes outcomes = runConcurrentEntries(requests);

        assertEquals(4, outcomes.successes().size());
        long firstId = outcomes.successes().get(0).manifestId();
        for (ManifestResponse response : outcomes.successes()) {
            assertEquals(firstId, response.manifestId());
        }
        assertEquals(1, countRows("manifest"));
    }
}
