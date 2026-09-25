package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.model.GeneratorStatus;
import com.example.starter.service.BusinessException;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;

/**
 * 并发派单测试：同油机同时刻只服务一站、同站同时刻只允许一张未完成调度单。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_concurrency;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ConcurrentDispatchTest extends BaseIntegrationTest {

    @Test
    void singleIdleGeneratorIsAllocatedToExactlyOneStationConcurrently() throws Exception {
        givenStation("BS-1", "50", "100");
        givenStation("BS-2", "50", "100");
        givenGenerator("GEN-1");
        givenReading("req-r1", "BS-1", T0.minus(Duration.ofMinutes(5)), "99");
        givenReading("req-r2", "BS-2", T0.minus(Duration.ofMinutes(5)), "99");

        List<Throwable> errors = new ArrayList<>();
        List<AlarmResponse> successes = new ArrayList<>();
        runConcurrently(List.of("BS-1", "BS-2"), successes, errors);

        // 并发分配唯一成功：一站拿到油机，另一站因无空闲油机被拒绝。
        assertThat(successes).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) errors.get(0)).code()).isEqualTo("NO_IDLE_GENERATOR");
        assertThat(count("dispatch_order")).isEqualTo(1);
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.ASSIGNED);
        Long busyKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatch_order WHERE busy_generator_key = 'GEN-1'",
                Long.class);
        assertThat(busyKeys).isEqualTo(1);
    }

    @Test
    void concurrentAlarmsOnSameStationProduceSingleEventAndSingleOrder() throws Exception {
        givenStation("BS-1", "50", "100");
        givenGenerator("GEN-1");
        givenGenerator("GEN-2");
        givenReading("req-r1", "BS-1", T0.minus(Duration.ofMinutes(5)), "99");

        List<Throwable> errors = new ArrayList<>();
        List<AlarmResponse> successes = new ArrayList<>();
        runConcurrently(List.of("BS-1", "BS-1", "BS-1", "BS-1"), successes, errors);

        assertThat(errors).isEmpty();
        assertThat(successes).hasSize(4);
        // 同站同时刻只允许一张未完成调度单，事件期内告警合并。
        assertThat(count("outage_event")).isEqualTo(1);
        assertThat(count("dispatch_order")).isEqualTo(1);
        assertThat(successes.stream().map(AlarmResponse::eventId).distinct().count()).isEqualTo(1);
        assertThat(successes.stream().filter(r -> r.dispatchOrder() != null).count()).isEqualTo(1);
        assertThat(successes.stream().filter(AlarmResponse::merged).count()).isEqualTo(3);
        // 第二台油机保持空闲。
        assertThat(generatorRepository.findByCode("GEN-2").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
    }

    private void runConcurrently(List<String> stationCodes, List<AlarmResponse> successes,
                                 List<Throwable> errors) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(stationCodes.size());
        CountDownLatch ready = new CountDownLatch(stationCodes.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<AlarmResponse> syncedSuccesses = java.util.Collections.synchronizedList(successes);
        List<Throwable> syncedErrors = java.util.Collections.synchronizedList(errors);
        try {
            for (int i = 0; i < stationCodes.size(); i++) {
                String station = stationCodes.get(i);
                String requestId = "req-concurrent-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        syncedSuccesses.add(givenAlarm(requestId, station, T0));
                    } catch (Throwable t) {
                        syncedErrors.add(t);
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
