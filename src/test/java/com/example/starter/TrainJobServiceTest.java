package com.example.starter.service;

import com.example.starter.domain.TestType;
import com.example.starter.repo.PressureReadingRepository;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.BrakeTestResponse;
import com.example.starter.web.dto.CarDto;
import com.example.starter.web.dto.ClosureResponse;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.ConsistChangeResponse;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.ConsistCheckResponse;
import com.example.starter.web.dto.DepartureResponse;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.PressureReadingRequest;
import com.example.starter.web.dto.PressureReadingResponse;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.web.dto.TestCurveResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列车制动机试验与摘挂核对服务测试（H2 内存库 MySQL 兼容模式，真实事务与约束）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_svc_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrainJobServiceTest {

    @Autowired
    private TrainJobService service;

    @Autowired
    private PressureReadingRepository pressureReadingRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM pressure_reading");
        jdbc.update("DELETE FROM brake_test");
        jdbc.update("DELETE FROM consist_check");
        jdbc.update("DELETE FROM train_car");
        jdbc.update("DELETE FROM train_job");
    }

    @AfterAll
    void releaseDatabase() {
        // 测试上下文结束后释放命名内存库（DB_CLOSE_DELAY=-1 不会自动释放）
        jdbc.execute("SHUTDOWN");
    }

    private List<CarDto> cars(int total, int closed) {
        List<CarDto> cars = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            cars.add(new CarDto("C" + i, new BigDecimal("80.0"), new BigDecimal("1.3"), i <= closed));
        }
        return cars;
    }

    private AcceptJobRequest acceptRequest(String requestId, String track, int total, int closed) {
        return new AcceptJobRequest(requestId, "T" + requestId, track, cars(total, closed));
    }

    /** 完成接入与运统1核对（一致），返回作业视图。 */
    private JobResponse acceptAndCheck(String key, String track, int total, int closed) {
        JobResponse job = service.accept(acceptRequest(key + "-a", track, total, closed));
        ConsistCheckResponse check = service.checkConsist(job.id(), new ConsistCheckRequest(
                key + "-c", total, new BigDecimal("1.3").multiply(BigDecimal.valueOf(total)),
                new BigDecimal("80.0").multiply(BigDecimal.valueOf(total))));
        assertTrue(check.matched());
        return service.getJob(job.id());
    }

    private BrakeTestRequest sensitivity(String key, int reduction, int pipe, boolean allBraked) {
        return new BrakeTestRequest(key, TestType.SENSITIVITY, reduction, pipe, allBraked, null);
    }

    private BrakeTestRequest stability(String key, int reduction, int pipe, boolean emergency) {
        return new BrakeTestRequest(key, TestType.STABILITY, reduction, pipe, null, emergency);
    }

    @Test
    void 运统1核对不一致拒绝发车() {
        JobResponse job = service.accept(acceptRequest("k1-a", "G1", 10, 0));
        ConsistCheckResponse check = service.checkConsist(job.id(), new ConsistCheckRequest(
                "k1-c", 9, new BigDecimal("13.0"), new BigDecimal("800.0")));
        assertFalse(check.matched());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirmDeparture(job.id(), new RequestIdOnly("k1-d")));
        assertTrue(e.getMessage().contains("运统1"));
    }

    @Test
    void 关门车恰6百分之合法超过则拒绝() {
        // 50 辆 3 辆关门车 = 恰 6%，合法
        JobResponse job = service.accept(acceptRequest("k2-a", "G2", 50, 3));
        assertEquals(50, job.carCount());
        assertEquals(3, job.closedCarCount());
        assertEquals(0.06, job.closedCarRatio(), 1e-9);
        // 50 辆 4 辆关门车 = 8%，拒绝接入
        assertThrows(BusinessException.class,
                () -> service.accept(acceptRequest("k2-b", "G3", 50, 4)));
    }

    @Test
    void 感度试验减压50全列制动合格否则不合格() {
        JobResponse job = acceptAndCheck("k3", "G4", 10, 0);
        BrakeTestResponse fail = service.brakeTest(job.id(), sensitivity("k3-s1", 50, 500, false));
        assertEquals("FAIL", fail.result());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirmDeparture(job.id(), new RequestIdOnly("k3-d")));
        assertTrue(e.getMessage().contains("SENSITIVITY"));
        BrakeTestResponse pass = service.brakeTest(job.id(), sensitivity("k3-s2", 50, 500, true));
        assertEquals("PASS", pass.result());
        // 减压量非 50kPa 拒绝
        assertThrows(BusinessException.class,
                () -> service.brakeTest(job.id(), sensitivity("k3-s3", 70, 500, true)));
    }

    @Test
    void 安定试验恰140kPa合格紧急制动不合格() {
        JobResponse job = acceptAndCheck("k4", "G5", 10, 0);
        service.brakeTest(job.id(), sensitivity("k4-s", 50, 500, true));
        // 恰 140kPa 且无紧急制动：合格
        BrakeTestResponse pass = service.brakeTest(job.id(), stability("k4-t1", 140, 500, false));
        assertEquals("PASS", pass.result());

        JobResponse job2 = acceptAndCheck("k5", "G6", 10, 0);
        service.brakeTest(job2.id(), sensitivity("k5-s", 50, 500, true));
        // 发生紧急制动：不合格
        BrakeTestResponse fail = service.brakeTest(job2.id(), stability("k5-t1", 140, 500, true));
        assertEquals("FAIL", fail.result());
        // 减压量非 140kPa 拒绝
        assertThrows(BusinessException.class,
                () -> service.brakeTest(job2.id(), stability("k5-t2", 141, 500, false)));
    }

    @Test
    void 安定试验须先完成感度合格() {
        JobResponse job = acceptAndCheck("k6", "G7", 10, 0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.brakeTest(job.id(), stability("k6-t", 140, 500, false)));
        assertTrue(e.getMessage().contains("SENSITIVITY"));
    }

    @Test
    void 两项之间风压恰达定压允许低于定压拒绝() {
        JobResponse job = acceptAndCheck("k7", "G8", 10, 0);
        service.brakeTest(job.id(), sensitivity("k7-s", 50, 500, true));
        // 499kPa 未恢复定压：拒绝
        assertThrows(BusinessException.class,
                () -> service.brakeTest(job.id(), stability("k7-t1", 140, 499, false)));
        // 恰达定压 500kPa：允许
        BrakeTestResponse pass = service.brakeTest(job.id(), stability("k7-t2", 140, 500, false));
        assertEquals("PASS", pass.result());
    }

    @Test
    void 编组变更后试验结论作废须重做() {
        JobResponse job = acceptAndCheck("k8", "G9", 10, 0);
        service.brakeTest(job.id(), sensitivity("k8-s", 50, 500, true));
        service.brakeTest(job.id(), stability("k8-t", 140, 500, false));

        ConsistChangeResponse change = service.changeConsist(job.id(), new ConsistChangeRequest(
                "k8-g", List.of(new CarDto("C11", new BigDecimal("80.0"), new BigDecimal("1.3"), false)),
                List.of("C1")));
        assertEquals(2, change.voidedTests());
        assertEquals(job.consistVersion() + 1, change.consistVersion());
        assertEquals(10, change.carCount());

        // 变更后不得沿用旧结论发车
        assertThrows(BusinessException.class,
                () -> service.confirmDeparture(job.id(), new RequestIdOnly("k8-d1")));
        // 试验曲线中旧结论标记作废
        TestCurveResponse curve = service.getTestCurve(job.id());
        assertEquals(2, curve.tests().stream().filter(TestCurveResponse.TestPoint::voided).count());

        // 重新核对并重做试验后方可发车
        ConsistCheckResponse recheck = service.checkConsist(job.id(), new ConsistCheckRequest(
                "k8-c2", 10, new BigDecimal("13.0"), new BigDecimal("800.0")));
        assertTrue(recheck.matched());
        service.brakeTest(job.id(), sensitivity("k8-s2", 50, 500, true));
        service.brakeTest(job.id(), stability("k8-t2", 140, 500, false));
        DepartureResponse departure = service.confirmDeparture(job.id(), new RequestIdOnly("k8-d2"));
        assertTrue(departure.approved());
    }

    @Test
    void 同一股道并发接入唯一受理() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    service.accept(acceptRequest("k9-" + idx, "G10", 10, 0));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        Integer jobs = jdbc.queryForObject("SELECT COUNT(*) FROM train_job WHERE track = 'G10'",
                Integer.class);
        assertEquals(1, jobs);
    }

    @Test
    void 同键同参重放返回首次结果同键异参409且状态不变() {
        AcceptJobRequest request = acceptRequest("k10-a", "G11", 10, 0);
        JobResponse first = service.accept(request);
        JobResponse replay = service.accept(request);
        assertEquals(first.id(), replay.id());
        Integer jobs = jdbc.queryForObject("SELECT COUNT(*) FROM train_job", Integer.class);
        assertEquals(1, jobs);

        // 同键异参：409 且不改变作业状态
        AcceptJobRequest tampered = new AcceptJobRequest("k10-a", "T-other", "G11", cars(10, 0));
        assertThrows(ConflictException.class, () -> service.accept(tampered));
        JobResponse current = service.getJob(first.id());
        assertEquals(first.trainNo(), current.trainNo());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM train_job", Integer.class));
    }

    @Test
    void 销记为终态重复销记幂等成功迟到风压只记事件() {
        JobResponse job = acceptAndCheck("k11", "G12", 10, 0);
        service.brakeTest(job.id(), sensitivity("k11-s", 50, 500, true));
        service.brakeTest(job.id(), stability("k11-t", 140, 500, false));
        service.confirmDeparture(job.id(), new RequestIdOnly("k11-d"));

        ClosureResponse closed = service.close(job.id(), new RequestIdOnly("k11-x"));
        assertTrue(closed.closed());
        assertFalse(closed.repeated());
        assertEquals("CLOSED", service.getJob(job.id()).status());

        // 重复销记（新幂等键）：幂等成功
        ClosureResponse repeated = service.close(job.id(), new RequestIdOnly("k11-x2"));
        assertTrue(repeated.closed());
        assertTrue(repeated.repeated());

        // 销记后迟到风压数据只记迟到事件
        PressureReadingResponse late = service.reportPressure(job.id(), new PressureReadingRequest(
                "k11-p", 480, LocalDateTime.of(2026, 9, 26, 12, 0)));
        assertTrue(late.late());
        assertFalse(late.recorded());
        assertEquals(1, pressureReadingRepository.countLateEvents(job.id()));
        assertEquals(0, service.getTestCurve(job.id()).readings().size());

        // 终态后业务操作拒绝
        assertThrows(BusinessException.class,
                () -> service.brakeTest(job.id(), sensitivity("k11-s2", 50, 500, true)));
        assertThrows(BusinessException.class,
                () -> service.changeConsist(job.id(), new ConsistChangeRequest(
                        "k11-g", List.of(), List.of("C1"))));
    }

    @Test
    void 风压读数进入试验曲线可复算() {
        JobResponse job = acceptAndCheck("k12", "G13", 10, 0);
        service.reportPressure(job.id(), new PressureReadingRequest(
                "k12-p1", 500, LocalDateTime.of(2026, 9, 26, 10, 0)));
        service.reportPressure(job.id(), new PressureReadingRequest(
                "k12-p2", 450, LocalDateTime.of(2026, 9, 26, 10, 1)));
        service.brakeTest(job.id(), sensitivity("k12-s", 50, 500, true));
        TestCurveResponse curve = service.getTestCurve(job.id());
        assertEquals(2, curve.readings().size());
        assertEquals(500, curve.readings().get(0).pressure());
        assertEquals(450, curve.readings().get(1).pressure());
        assertEquals(1, curve.tests().size());
        assertFalse(curve.tests().get(0).voided());
    }

    @Test
    void 关门车清单与编组版本可复算() {
        JobResponse job = acceptAndCheck("k13", "G14", 50, 3);
        var closedCars = service.getClosedCars(job.id());
        assertEquals(3, closedCars.closedCount());
        assertEquals(50, closedCars.totalCount());
        assertTrue(closedCars.withinLimit());
        assertEquals(List.of("C1", "C2", "C3"), closedCars.closedCarNos());

        var consist = service.getConsist(job.id());
        assertEquals(1, consist.consistVersion());
        assertEquals(50, consist.carCount());
        assertEquals(0, new BigDecimal("65.0").compareTo(consist.totalLength()));
        assertEquals(0, new BigDecimal("4000.0").compareTo(consist.totalWeight()));
    }

    @Test
    void 摘挂后辆数换长总重须重新核对() {
        JobResponse job = acceptAndCheck("k14", "G15", 10, 0);
        service.changeConsist(job.id(), new ConsistChangeRequest(
                "k14-g", List.of(), List.of("C1", "C2")));
        JobResponse changed = service.getJob(job.id());
        assertEquals(8, changed.carCount());
        assertEquals(2, changed.consistVersion());
        // 旧运统1数据不再一致
        ConsistCheckResponse stale = service.checkConsist(job.id(), new ConsistCheckRequest(
                "k14-c2", 10, new BigDecimal("13.0"), new BigDecimal("800.0")));
        assertFalse(stale.matched());
        // 新运统1数据一致
        ConsistCheckResponse fresh = service.checkConsist(job.id(), new ConsistCheckRequest(
                "k14-c3", 8, new BigDecimal("10.4"), new BigDecimal("640.0")));
        assertTrue(fresh.matched());
    }

    @Test
    void 未发车确认不得销记() {
        JobResponse job = acceptAndCheck("k15", "G16", 10, 0);
        assertThrows(BusinessException.class,
                () -> service.close(job.id(), new RequestIdOnly("k15-x")));
    }

    @Test
    void 作业不存在返回404() {
        assertThrows(NotFoundException.class, () -> service.getJob(999999));
    }

    @Test
    void 试验前未核对运统1拒绝试验() {
        JobResponse job = service.accept(acceptRequest("k16-a", "G17", 10, 0));
        assertThrows(BusinessException.class,
                () -> service.brakeTest(job.id(), sensitivity("k16-s", 50, 500, true)));
    }

    @Test
    void 不同股道可同时作业() {
        JobResponse first = service.accept(acceptRequest("k17-a", "G18", 10, 0));
        JobResponse second = service.accept(acceptRequest("k17-b", "G19", 10, 0));
        assertNotEquals(first.id(), second.id());
    }
}
