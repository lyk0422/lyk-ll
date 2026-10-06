package com.example.starter;

import com.example.starter.service.ApiException;
import com.example.starter.web.dto.WindReadingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等与并发测试：同键同参返回首次结果、同键异参 409、并发同键只执行一次、
 * 同线路同时刻只允许一个进行中管制事件。
 */
class IdempotencyConcurrencyTest extends BaseIntegrationTest {

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        String requestId = UUID.randomUUID().toString();
        ResponseEntity<String> first = windIngestionService.ingest(LINE_ID,
                new WindReadingRequest(requestId, STATION_1, T0, 8.0));
        ResponseEntity<String> second = windIngestionService.ingest(LINE_ID,
                new WindReadingRequest(requestId, STATION_1, T0, 8.0));
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(countRows("wind_reading")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsConflicts() {
        String requestId = UUID.randomUUID().toString();
        windIngestionService.ingest(LINE_ID, new WindReadingRequest(requestId, STATION_1, T0, 8.0));
        assertThatThrownBy(() -> windIngestionService.ingest(LINE_ID,
                new WindReadingRequest(requestId, STATION_1, T0, 9.0)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        // 业务字段任一变化都算异参（此处为站点）
        assertThatThrownBy(() -> windIngestionService.ingest(LINE_ID,
                new WindReadingRequest(requestId, STATION_2, T0, 8.0)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("wind_reading")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyExecutesOnce() throws Exception {
        String requestId = UUID.randomUUID().toString();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return windIngestionService.ingest(LINE_ID,
                        new WindReadingRequest(requestId, STATION_1, T0, 8.0));
            }));
        }
        gate.countDown();
        List<String> bodies = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            bodies.add(response.getBody());
        }
        pool.shutdown();
        assertThat(bodies).allMatch(body -> body.equals(bodies.get(0)));
        assertThat(countRows("wind_reading")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(1);
    }

    @Test
    void concurrentTriggersCreateOnlyOneActiveEventPerLine() throws Exception {
        // 两站数据齐备到第 9 分钟，最后一分钟数据由两个线程并发上报
        feedMinutes(STATION_3, T0, 9, 20.0);
        feedMinutes(STATION_4, T0, 9, 20.0);
        Instant triggerAt = T0.plusSeconds(600);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ResponseEntity<String>> f1 = pool.submit(() -> {
            gate.await();
            return windIngestionService.ingest(LINE_ID, new WindReadingRequest(
                    UUID.randomUUID().toString(), STATION_3, triggerAt, 20.0));
        });
        Future<ResponseEntity<String>> f2 = pool.submit(() -> {
            gate.await();
            return windIngestionService.ingest(LINE_ID, new WindReadingRequest(
                    UUID.randomUUID().toString(), STATION_4, triggerAt, 20.0));
        });
        gate.countDown();
        assertThat(f1.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        assertThat(f2.get(30, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        pool.shutdown();

        assertThat(countRows("control_event")).isEqualTo(1);
        assertThat(lineStatus()).isEqualTo("EMERGENCY_STOP");
    }

    @Test
    void secondTriggerIsIgnoredWhileEventInProgress() {
        // 触发缓停后，另一分段恰达 20m/s 也不再产生新事件
        feedMinutes(STATION_3, T0, 10, 17.0);
        feedMinutes(STATION_4, T0, 10, 17.0);
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");

        Instant t1 = T0.plusSeconds(1200);
        feedMinutes(STATION_1, t1, 10, 20.0);
        feedMinutes(STATION_2, t1, 10, 20.0);
        assertThat(countRows("control_event")).isEqualTo(1);
        assertThat(lineStatus()).isEqualTo("SLOW_STOP");
    }
}
