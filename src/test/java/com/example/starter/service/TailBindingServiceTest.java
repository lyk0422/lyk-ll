package com.example.starter.service;

import com.example.starter.domain.TestType;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.CarDto;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.RegisterTailDeviceRequest;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.web.dto.SelfTestRequest;
import com.example.starter.web.dto.TailBindRequest;
import com.example.starter.web.dto.TailBindingHistoryResponse;
import com.example.starter.web.dto.TailBindingResponse;
import com.example.starter.web.dto.TailConfirmRequest;
import com.example.starter.web.dto.TailDeviceResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 列尾装置绑定与编组变更发车联锁测试（H2 内存库 MySQL 兼容模式，真实事务、约束与可控时钟）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_tail_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TailBindingServiceTest {

    /** 可控时钟基准时刻（UTC）。 */
    private static final Instant T0 = Instant.parse("2026-09-27T02:00:00Z");
    /** 装置自检合格有效截止时刻（UTC）。 */
    private static final Instant VALID_UNTIL = Instant.parse("2026-09-27T06:00:00Z");

    @Autowired
    private TrainJobService jobService;

    @Autowired
    private TailDeviceService deviceService;

    @Autowired
    private TailBindingService tailService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM tail_consistency_confirmation");
        jdbc.update("DELETE FROM tail_binding");
        jdbc.update("DELETE FROM tail_device");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM pressure_reading");
        jdbc.update("DELETE FROM brake_test");
        jdbc.update("DELETE FROM consist_check");
        jdbc.update("DELETE FROM train_car");
        jdbc.update("DELETE FROM train_job");
        when(clock.instant()).thenReturn(T0);
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

    /** 受控作业接入（启用列尾控制）。 */
    private JobResponse acceptControlled(String key, String track, int total) {
        return jobService.accept(new AcceptJobRequest(key + "-a", "T" + key, track,
                cars(total, 0), true));
    }

    /** 受控作业接入并通过运统1核对。 */
    private JobResponse acceptControlledAndCheck(String key, String track, int total) {
        JobResponse job = acceptControlled(key, track, total);
        jobService.checkConsist(job.id(), new ConsistCheckRequest(key + "-c", total,
                new BigDecimal("1.3").multiply(BigDecimal.valueOf(total)),
                new BigDecimal("80.0").multiply(BigDecimal.valueOf(total))));
        return jobService.getJob(job.id());
    }

    /** 感度与安定试验均合格。 */
    private void passBrakeTests(String key, long jobId) {
        jobService.brakeTest(jobId, new BrakeTestRequest(key + "-s", TestType.SENSITIVITY,
                50, 500, true, null));
        jobService.brakeTest(jobId, new BrakeTestRequest(key + "-t", TestType.STABILITY,
                140, 500, null, false));
    }

    private TailDeviceResponse registerDevice(String key, String deviceNo, Instant validUntil) {
        return deviceService.register(new RegisterTailDeviceRequest(key + "-rd", deviceNo, validUntil));
    }

    private TailBindingResponse bind(String key, long jobId, String deviceNo, int expectedVersion) {
        return tailService.bind(jobId, new TailBindRequest(key + "-b", deviceNo, expectedVersion));
    }

    /** 两名不同人员确认当前尾车与编组版本。 */
    private void confirmByTwo(String key, long jobId, String tailCarNo, int consistVersion) {
        tailService.confirm(jobId, new TailConfirmRequest(key + "-cf1", "检车员甲", tailCarNo, consistVersion));
        tailService.confirm(jobId, new TailConfirmRequest(key + "-cf2", "检车员乙", tailCarNo, consistVersion));
    }

    @Test
    void 受控主流程绑定双人确认发车销记原子释放装置() {
        registerDevice("m1", "D1", VALID_UNTIL);
        JobResponse job = acceptControlledAndCheck("m1", "T1", 10);
        passBrakeTests("m1", job.id());

        TailBindingResponse binding = bind("m1", job.id(), "D1", 1);
        assertEquals("ACTIVE", binding.status());
        assertEquals("C10", binding.tailCarNo());
        assertEquals(1, binding.consistVersion());
        TailDeviceResponse occupied = deviceService.getDevice("D1");
        assertEquals(job.id(), occupied.currentJobId());
        assertEquals(2, occupied.version());

        confirmByTwo("m1", job.id(), "C10", 1);
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("m1-d")).approved());
        assertTrue(jobService.close(job.id(), new RequestIdOnly("m1-x")).closed());

        // 销记原子解除装置占用，版本加1，绑定历史保留
        TailDeviceResponse released = deviceService.getDevice("D1");
        assertNull(released.currentJobId());
        assertEquals(3, released.version());
        TailBindingHistoryResponse history = tailService.getHistory(job.id());
        assertEquals(1, history.bindings().size());
        assertEquals("RELEASED", history.bindings().get(0).status());
        assertEquals("CLOSURE", history.bindings().get(0).releaseReason());
        assertEquals(2, history.bindings().get(0).confirmations().size());
        assertFalse(history.bindings().get(0).confirmations().get(0).valid());

        // 装置释放后立即可被新受控作业绑定
        JobResponse job2 = acceptControlled("m1b", "T2", 10);
        TailBindingResponse rebound = bind("m1b", job2.id(), "D1", 3);
        assertEquals("ACTIVE", rebound.status());
    }

    @Test
    void 恰到自检截止时刻绑定失效提前一瞬可绑定() {
        JobResponse job = acceptControlled("m2", "T3", 10);
        // 恰达截止（now == validUntil）：失效，拒绝绑定
        registerDevice("m2", "D2", T0);
        BusinessException expired = assertThrows(BusinessException.class,
                () -> bind("m2a", job.id(), "D2", 1));
        assertTrue(expired.getMessage().contains("自检"));
        // 截止晚于当前一瞬：允许绑定
        registerDevice("m2b", "D3", T0.plusSeconds(1));
        assertEquals("ACTIVE", bind("m2c", job.id(), "D3", 1).status());
    }

    @Test
    void 编号未知404自检过期422装置已占用422非受控作业422() {
        JobResponse job = acceptControlled("m3", "T4", 10);
        assertThrows(NotFoundException.class, () -> bind("m3a", job.id(), "D-UNKNOWN", 1));

        registerDevice("m3", "D4", T0.minusSeconds(1));
        assertThrows(BusinessException.class, () -> bind("m3b", job.id(), "D4", 1));

        registerDevice("m3c", "D5", VALID_UNTIL);
        bind("m3d", job.id(), "D5", 1);
        JobResponse job2 = acceptControlled("m3e", "T5", 10);
        BusinessException occupied = assertThrows(BusinessException.class,
                () -> bind("m3f", job2.id(), "D5", 2));
        assertTrue(occupied.getMessage().contains("已占用"));

        // 未启用列尾控制的作业不得绑定
        JobResponse legacy = jobService.accept(new AcceptJobRequest("m3g-a", "T-m3g", "T6",
                cars(10, 0)));
        BusinessException notControlled = assertThrows(BusinessException.class,
                () -> tailService.bind(legacy.id(), new TailBindRequest("m3g-b", "D5", 2)));
        assertTrue(notControlled.getMessage().contains("未启用列尾控制"));
    }

    @Test
    void 摘挂后一致性确认立即失效须两名不同人员重新确认新尾车及版本() {
        registerDevice("m4", "D6", VALID_UNTIL);
        JobResponse job = acceptControlledAndCheck("m4", "T7", 10);
        passBrakeTests("m4", job.id());
        bind("m4", job.id(), "D6", 1);
        confirmByTwo("m4", job.id(), "C10", 1);

        // 摘挂改变尾车与编组版本：绑定保留，确认失效
        jobService.changeConsist(job.id(), new ConsistChangeRequest("m4-g",
                List.of(new CarDto("C11", new BigDecimal("80.0"), new BigDecimal("1.3"), false)),
                List.of("C1")));
        assertEquals(2, jobService.getJob(job.id()).consistVersion());
        TailBindingHistoryResponse afterChange = tailService.getHistory(job.id());
        assertEquals("ACTIVE", afterChange.bindings().get(0).status());
        assertEquals(2, afterChange.bindings().get(0).confirmations().stream()
                .filter(c -> !c.valid()).count());

        // 重新核对并重做试验后，列尾确认仍失效，拒绝发车
        jobService.checkConsist(job.id(), new ConsistCheckRequest("m4-c2", 10,
                new BigDecimal("13.0"), new BigDecimal("800.0")));
        passBrakeTests("m4b", job.id());
        BusinessException e = assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(job.id(), new RequestIdOnly("m4-d1")));
        assertTrue(e.getMessage().contains("列尾一致性"));

        // 两名不同人员确认新尾车与新版本后放行
        confirmByTwo("m4c", job.id(), "C11", 2);
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("m4-d2")).approved());
    }

    @Test
    void 同一确认人不得重复确认且仅一人确认不得发车() {
        registerDevice("m5", "D7", VALID_UNTIL);
        JobResponse job = acceptControlledAndCheck("m5", "T8", 10);
        passBrakeTests("m5", job.id());
        bind("m5", job.id(), "D7", 1);

        tailService.confirm(job.id(), new TailConfirmRequest("m5-cf1", "检车员甲", "C10", 1));
        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> tailService.confirm(job.id(),
                        new TailConfirmRequest("m5-cf2", "检车员甲", "C10", 1)));
        assertTrue(duplicate.getMessage().contains("两名不同人员"));

        BusinessException departure = assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(job.id(), new RequestIdOnly("m5-d1")));
        assertTrue(departure.getMessage().contains("列尾一致性"));

        tailService.confirm(job.id(), new TailConfirmRequest("m5-cf3", "检车员乙", "C10", 1));
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("m5-d2")).approved());
    }

    @Test
    void 确认尾车或版本与当前编组不一致拒绝() {
        registerDevice("m6", "D8", VALID_UNTIL);
        JobResponse job = acceptControlled("m6", "T9", 10);
        bind("m6", job.id(), "D8", 1);
        assertThrows(BusinessException.class, () -> tailService.confirm(job.id(),
                new TailConfirmRequest("m6-cf1", "检车员甲", "C9", 1)));
        assertThrows(BusinessException.class, () -> tailService.confirm(job.id(),
                new TailConfirmRequest("m6-cf2", "检车员甲", "C10", 99)));
    }

    @Test
    void 提前解绑仅发车前允许并同时撤销确认() {
        registerDevice("m7", "D9", VALID_UNTIL);
        JobResponse job = acceptControlledAndCheck("m7", "T10", 10);
        passBrakeTests("m7", job.id());
        bind("m7", job.id(), "D9", 1);
        confirmByTwo("m7", job.id(), "C10", 1);

        TailBindingResponse unbound = tailService.unbind(job.id(),
                new com.example.starter.web.dto.TailUnbindRequest("m7-u", 2));
        assertEquals("RELEASED", unbound.status());
        assertEquals("EARLY_UNBIND", unbound.releaseReason());
        TailDeviceResponse device = deviceService.getDevice("D9");
        assertNull(device.currentJobId());
        assertEquals(3, device.version());
        // 确认同步撤销
        TailBindingHistoryResponse history = tailService.getHistory(job.id());
        assertEquals(2, history.bindings().get(0).confirmations().stream()
                .filter(c -> !c.valid()).count());

        // 解绑后可重新绑定并走完整流程发车
        bind("m7b", job.id(), "D9", 3);
        confirmByTwo("m7c", job.id(), "C10", 1);
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("m7-d")).approved());

        // 发车确认后不得提前解绑
        BusinessException e = assertThrows(BusinessException.class,
                () -> tailService.unbind(job.id(),
                        new com.example.starter.web.dto.TailUnbindRequest("m7-u2", 4)));
        assertTrue(e.getMessage().contains("不得提前解绑"));
    }

    @Test
    void 自检过期后发车拒绝更新自检后放行() {
        registerDevice("m8", "D10", VALID_UNTIL);
        JobResponse job = acceptControlledAndCheck("m8", "T11", 10);
        passBrakeTests("m8", job.id());
        bind("m8", job.id(), "D10", 1);
        confirmByTwo("m8", job.id(), "C10", 1);

        // 时钟推进到恰达自检截止：发车拒绝
        when(clock.instant()).thenReturn(VALID_UNTIL);
        BusinessException e = assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(job.id(), new RequestIdOnly("m8-d1")));
        assertTrue(e.getMessage().contains("自检已过期"));

        // 自检更新延长有效期后放行
        deviceService.updateSelfTest("D10", new SelfTestRequest("m8-st", 2,
                VALID_UNTIL.plusSeconds(3600)));
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("m8-d2")).approved());
    }

    @Test
    void 未启用列尾控制的历史作业不增加新门禁() {
        JobResponse job = jobService.accept(new AcceptJobRequest("m9-a", "T-m9", "T12",
                cars(10, 0)));
        jobService.checkConsist(job.id(), new ConsistCheckRequest("m9-c", 10,
                new BigDecimal("13.0"), new BigDecimal("800.0")));
        passBrakeTests("m9", job.id());
        // 无列尾绑定也可发车（保持旧行为）
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("m9-d")).approved());
    }

    @Test
    void 装置版本自1起成功变更加1版本不符409() {
        registerDevice("m10", "D11", VALID_UNTIL);
        assertEquals(1, deviceService.getDevice("D11").version());

        assertThrows(ConflictException.class,
                () -> deviceService.updateSelfTest("D11",
                        new SelfTestRequest("m10-st1", 2, VALID_UNTIL.plusSeconds(3600))));
        TailDeviceResponse updated = deviceService.updateSelfTest("D11",
                new SelfTestRequest("m10-st2", 1, VALID_UNTIL.plusSeconds(3600)));
        assertEquals(2, updated.version());

        JobResponse job = acceptControlled("m10", "T13", 10);
        assertThrows(ConflictException.class, () -> bind("m10a", job.id(), "D11", 1));
        bind("m10b", job.id(), "D11", 2);
        assertEquals(3, deviceService.getDevice("D11").version());
    }

    @Test
    void 绑定幂等同键同参重放异参409失败不占键() {
        registerDevice("m11", "D12", VALID_UNTIL);
        JobResponse job = acceptControlled("m11", "T14", 10);

        TailBindingResponse first = tailService.bind(job.id(), new TailBindRequest("m11-b", "D12", 1));
        TailBindingResponse replay = tailService.bind(job.id(), new TailBindRequest("m11-b", "D12", 1));
        assertEquals(first.bindingId(), replay.bindingId());
        assertEquals(2, deviceService.getDevice("D12").version());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tail_binding WHERE job_id = " + job.id(), Integer.class));

        // 同键异参：409 且不改变状态
        assertThrows(ConflictException.class,
                () -> tailService.bind(job.id(), new TailBindRequest("m11-b", "D12", 99)));
        assertEquals(2, deviceService.getDevice("D12").version());

        // 失败不占键：过期装置绑定失败后，同键可随参数修正重用
        registerDevice("m11b", "D13", T0.minusSeconds(1));
        JobResponse job2 = acceptControlled("m11c", "T15", 10);
        assertThrows(BusinessException.class,
                () -> tailService.bind(job2.id(), new TailBindRequest("m11-f", "D13", 1)));
        deviceService.updateSelfTest("D13", new SelfTestRequest("m11-st", 1, VALID_UNTIL));
        TailBindingResponse retried = tailService.bind(job2.id(), new TailBindRequest("m11-f", "D13", 2));
        assertEquals("ACTIVE", retried.status());
    }

    @Test
    void 并发绑定同一装置唯一成功() throws Exception {
        registerDevice("m12", "D14", VALID_UNTIL);
        JobResponse job1 = acceptControlled("m12a", "T16", 10);
        JobResponse job2 = acceptControlled("m12b", "T17", 10);
        List<Long> jobIds = List.of(job1.id(), job1.id(), job2.id(), job2.id());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    tailService.bind(jobIds.get(idx),
                            new TailBindRequest("m12-b" + idx, "D14", 1));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    rejected.incrementAndGet();
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
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tail_binding WHERE status = 'ACTIVE'", Integer.class));
        Long currentJob = jdbc.queryForObject(
                "SELECT current_job_id FROM tail_device WHERE device_no = 'D14'", Long.class);
        assertTrue(currentJob != null && (currentJob == job1.id() || currentJob == job2.id()));
    }

    @Test
    void 确认人文本含分隔符指纹仍区分同键异参409() {
        registerDevice("m13", "D15", VALID_UNTIL);
        JobResponse job = acceptControlled("m13", "T18", 10);
        bind("m13", job.id(), "D15", 1);
        // 合法文本含分隔符（换行）：首次确认成功
        tailService.confirm(job.id(), new TailConfirmRequest("m13-cf", "甲\n乙", "C10", 1));
        // 同键异参（去掉分隔符）：指纹须区分，返回 409 而非重放
        assertThrows(ConflictException.class, () -> tailService.confirm(job.id(),
                new TailConfirmRequest("m13-cf", "甲乙", "C10", 1)));
    }
}
