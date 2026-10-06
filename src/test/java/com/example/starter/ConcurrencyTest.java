package com.example.starter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.example.starter.error.BusinessException;
import com.example.starter.repo.QualificationRepository;
import com.example.starter.service.CheckInService;
import com.example.starter.service.RouteService;
import com.example.starter.web.dto.RouteDtos.CheckInResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试：打卡去重与车辆执行中线路排他。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private CheckInService checkInService;

    @Autowired
    private RouteService routeService;

    @Autowired
    private QualificationRepository qualificationRepository;

    @Test
    void 并发重复打卡仅落一条记录() throws Exception {
        long vehicleId = insertVehicle("PLATE-X-1", 40);
        jdbc.sql("INSERT INTO route (route_code, name, vehicle_id, station_spacing_m, road_grade,"
                        + " required_seats, status) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params("RC-X-1", "合成线路", vehicleId, 800, 2, 30, "IN_PROGRESS")
                .update();
        long routeId = jdbc.sql("SELECT MAX(id) FROM route WHERE route_code = 'RC-X-1'")
                .query(Long.class)
                .single();
        long studentId = insertStudent("STU-X-1", routeId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<CheckInResponse>> tasks = IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<CheckInResponse>) () -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return checkInService.checkIn(routeId, studentId, "BOARD",
                                "req-concurrent-checkin-" + i);
                    })
                    .toList();
            List<Future<CheckInResponse>> futures = tasks.stream().map(pool::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long created = 0;
            long deduplicated = 0;
            Long checkInId = null;
            for (Future<CheckInResponse> future : futures) {
                CheckInResponse response = future.get(30, TimeUnit.SECONDS);
                if (response.deduplicated()) {
                    deduplicated++;
                } else {
                    created++;
                }
                if (checkInId == null) {
                    checkInId = response.checkInId();
                } else {
                    assertThat(response.checkInId()).isEqualTo(checkInId);
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(deduplicated).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("check_in")).isEqualTo(1);
    }

    @Test
    void 同一车辆并发启动仅一条线路进入执行中() throws Exception {
        long vehicleId = insertVehicle("PLATE-X-2", 40);
        long routeA = insertQualifiedRoute("RC-X-2", vehicleId);
        long routeB = insertQualifiedRoute("RC-X-3", vehicleId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<String> startA = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return startAndClassify(routeA);
            };
            Callable<String> startB = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return startAndClassify(routeB);
            };
            Future<String> futureA = pool.submit(startA);
            Future<String> futureB = pool.submit(startB);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> results = List.of(futureA.get(30, TimeUnit.SECONDS),
                    futureB.get(30, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("STARTED", "VEHICLE_BUSY");
        } finally {
            pool.shutdownNow();
        }
        Long inProgress = jdbc.sql("SELECT COUNT(*) FROM route WHERE status = 'IN_PROGRESS'")
                .query(Long.class)
                .single();
        assertThat(inProgress).isEqualTo(1);
    }

    private String startAndClassify(long routeId) {
        try {
            routeService.startRoute(routeId, newRequestId());
            return "STARTED";
        } catch (BusinessException e) {
            if ("VEHICLE_BUSY".equals(e.code())) {
                return "VEHICLE_BUSY";
            }
            throw e;
        }
    }

    private long insertQualifiedRoute(String routeCode, long vehicleId) {
        jdbc.sql("INSERT INTO route (route_code, name, vehicle_id, station_spacing_m, road_grade,"
                        + " required_seats, status) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(routeCode, "合成线路", vehicleId, 800, 2, 30, "QUALIFIED")
                .update();
        long routeId = jdbc.sql("SELECT MAX(id) FROM route WHERE route_code = ?")
                .param(routeCode)
                .query(Long.class)
                .single();
        qualificationRepository.insert(routeId, TODAY, TODAY.plusYears(1).minusDays(1),
                "INITIAL", newRequestId());
        return routeId;
    }
}
