package com.example.starter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.error.BusinessException;
import com.example.starter.service.RouteService;
import com.example.starter.web.dto.RouteDtos.CreateRouteRequest;
import com.example.starter.web.dto.RouteDtos.RouteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等测试：同键同参返回首次结果，同键异参 409，并发同键仅执行一次。
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private RouteService routeService;

    @Test
    void 同键同参返回首次结果() {
        long vehicleId = insertVehicle("PLATE-I-1", 40);
        CreateRouteRequest request = new CreateRouteRequest("req-same-1", "RC-I-1", "合成线路",
                vehicleId, 800, 2, 30);

        RouteResponse first = routeService.createRoute(request);
        RouteResponse second = routeService.createRoute(request);

        assertThat(second).isEqualTo(first);
        assertThat(count("route")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }

    @Test
    void 同键异参返回409() {
        long vehicleId = insertVehicle("PLATE-I-2", 40);
        routeService.createRoute(new CreateRouteRequest("req-conflict-1", "RC-I-2", "合成线路",
                vehicleId, 800, 2, 30));

        CreateRouteRequest different = new CreateRouteRequest("req-conflict-1", "RC-I-3",
                "另一条线路", vehicleId, 900, 2, 30);
        assertThatThrownBy(() -> routeService.createRoute(different))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
        assertThat(count("route")).isEqualTo(1);
    }

    @Test
    void 并发同键同参仅执行一次业务() throws Exception {
        long vehicleId = insertVehicle("PLATE-I-3", 40);
        CreateRouteRequest request = new CreateRouteRequest("req-concurrent-1", "RC-I-4",
                "合成线路", vehicleId, 800, 2, 30);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<RouteResponse>> tasks = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<RouteResponse>) () -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return routeService.createRoute(request);
                    })
                    .toList();
            List<Future<RouteResponse>> futures = tasks.stream().map(pool::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<RouteResponse> future : futures) {
                RouteResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.routeCode()).isEqualTo("RC-I-4");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("route")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(1);
    }
}
