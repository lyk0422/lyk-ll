package com.example.starter.business;

import com.example.starter.domain.ValveAction;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.support.AdjustmentGate;
import com.example.starter.service.BalanceService;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发测试：同一换热站同时刻并发下达首个调节令，active_station_id 唯一约束保证唯一受理。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String STATION = "ST-C";
    private static final List<String> UNITS = List.of("ST-C-U01", "ST-C-U02", "ST-C-U03");

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private AdjustmentGate gate;

    @Test
    void concurrentFirstAdjustments_onlyOneAccepted() throws Exception {
        createStation(STATION, 3);
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        gate.setBarrierForTest(barrier);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentLinkedQueue<Long> acceptedTaskIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> conflictCodes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        AdjustResponse response = balanceService.adjust(Requests.adjust(
                                "concurrent-adj-" + index, STATION,
                                Requests.step(UNITS.get(0), ValveAction.OPEN, "1")));
                        acceptedTaskIds.add(response.taskId());
                    } catch (ApiException e) {
                        conflictCodes.add(e.getCode());
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            gate.setBarrierForTest(null);
        }

        assertThat(errors).isEmpty();
        assertThat(acceptedTaskIds).hasSize(1);
        assertThat(conflictCodes).hasSize(threads - 1);
        assertThat(conflictCodes).allMatch("STATION_BUSY"::equals);
        // 全站仅一个执行中任务、一次开度版本、一次台账
        assertThat(count("adjustment_task")).isEqualTo(1);
        assertThat(count("valve_opening_version")).isEqualTo(1);
        assertThat(count("adjustment_record")).isEqualTo(1);
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("51.000");
        // 唯一受理任务后，同站可在该执行中任务内继续步进
        AdjustResponse followUp = balanceService.adjust(Requests.adjust("concurrent-adj-follow", STATION,
                Requests.step(UNITS.get(1), ValveAction.CLOSE, "2")));
        assertThat(followUp.taskCreated()).isFalse();
        assertThat(followUp.taskId()).isEqualTo(acceptedTaskIds.peek());
    }
}
