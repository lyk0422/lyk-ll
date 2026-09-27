package com.example.starter.service;

import com.example.starter.repo.EotBindingRepository;
import com.example.starter.repo.EotConfirmationRepository;
import com.example.starter.repo.EotDeviceRepository;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.CarDto;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.ConsistChangeResponse;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.ConsistCheckResponse;
import com.example.starter.web.dto.EotBindRequest;
import com.example.starter.web.dto.EotBindingEvidence;
import com.example.starter.web.dto.EotBindingResponse;
import com.example.starter.web.dto.EotConfirmRequest;
import com.example.starter.web.dto.EotConfirmationResponse;
import com.example.starter.web.dto.EotDeviceEvidence;
import com.example.starter.web.dto.EotDeviceRegisterRequest;
import com.example.starter.web.dto.EotDeviceResponse;
import com.example.starter.web.dto.EotUnbindRequest;
import com.example.starter.web.dto.EotUnbindResponse;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.domain.TestType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列尾装置核对服务测试：UTC 可控时钟、H2 真实事务/唯一约束、主流程/失败分支/并发与幂等边界。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_eot_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Import(EotServiceTest.TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EotServiceTest {

    static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant VALID_UNTIL = Instant.parse("2026-10-01T01:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Mockito.mock(Clock.class);
        }
    }

    @Autowired
    private EotService eotService;

    @Autowired
    private TrainJobService jobService;

    @Autowired
    private EotDeviceRepository deviceRepository;

    @Autowired
    private EotBindingRepository bindingRepository;

    @Autowired
    private EotConfirmationRepository confirmationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        Mockito.reset(clock);
        Mockito.when(clock.instant()).thenReturn(NOW);
        jdbc.update("DELETE FROM eot_consistency_confirmation");
        jdbc.update("DELETE FROM eot_binding");
        jdbc.update("DELETE FROM eot_device");
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
        jdbc.execute("SHUTDOWN");
    }

    // ---------- 合成数据构造 ----------

    private List<CarDto> cars(String prefix, int total) {
        List<CarDto> cars = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            cars.add(new CarDto(prefix + i, new BigDecimal("80.0"), new BigDecimal("1.3"), false));
        }
        return cars;
    }

    private JobResponse acceptJob(String key, String track) {
        return jobService.accept(new AcceptJobRequest(key + "-a", "T" + key, track, cars("C", 10)));
    }

    private JobResponse acceptAndCheck(String key, String track) {
        JobResponse job = acceptJob(key, track);
        ConsistCheckResponse check = jobService.checkConsist(job.id(), new ConsistCheckRequest(
                key + "-c", 10, new BigDecimal("13.0"), new BigDecimal("800.0")));
        assertTrue(check.matched());
        return jobService.getJob(job.id());
    }

    private void passBrakeTests(long jobId, String key) {
        assertEquals("PASS", jobService.brakeTest(jobId, new BrakeTestRequest(
                key + "-s", TestType.SENSITIVITY, 50, 500, true, null)).result());
        assertEquals("PASS", jobService.brakeTest(jobId, new BrakeTestRequest(
                key + "-t", TestType.STABILITY, 140, 500, null, false)).result());
    }

    private EotDeviceResponse registerDevice(String key, String deviceNo, Instant validUntil) {
        return eotService.register(new EotDeviceRegisterRequest(key + "-r", deviceNo, validUntil));
    }

    private EotDeviceResponse registerDevice(String key, String deviceNo) {
        return registerDevice(key, deviceNo, VALID_UNTIL);
    }

    private EotBindingResponse bind(String key, long jobId, String deviceNo, int expectedVersion) {
        return eotService.bind(jobId, new EotBindRequest(key + "-b", deviceNo, expectedVersion));
    }

    private EotConfirmationResponse confirm(String key, long jobId, int expectedVersion,
                                            String tailCarNo, int consistVersion,
                                            String p1, String p2) {
        return eotService.confirm(jobId, new EotConfirmRequest(key + "-f", expectedVersion,
                tailCarNo, consistVersion, p1, p2));
    }

    // ---------- 建档 ----------

    @Test
    void 装置建档编号唯一初始版本1且空闲() {
        EotDeviceResponse device = registerDevice("d1", "EOT-001");
        assertEquals(1, device.version());
        assertNull(device.boundJobId());
        assertEquals(VALID_UNTIL, device.selfCheckValidUntil());

        // 编号重复：409
        assertThrows(ConflictException.class,
                () -> registerDevice("d1x", "EOT-001"));
        // 失败不占用第二次的请求键（同键换编号可成功）
        assertNotNull(registerDevice("d1x", "EOT-002"));
    }

    // ---------- 绑定主流程与失败分支 ----------

    @Test
    void 绑定空闲装置冻结尾车与版本成功后版本加1() {
        JobResponse job = acceptAndCheck("b1", "T1");
        registerDevice("b1", "EOT-101");
        EotBindingResponse binding = bind("b1", job.id(), "EOT-101", 1);
        assertEquals("ACTIVE", binding.status());
        assertEquals("C10", binding.tailCarNo());
        assertEquals(1, binding.consistVersion());
        assertEquals(2, binding.deviceVersion());
        assertEquals(job.id(), deviceRepository.findByDeviceNo("EOT-101").orElseThrow().boundJobId());

        // 同作业重复绑定：422
        assertThrows(BusinessException.class,
                () -> bind("b1z", job.id(), "EOT-101", 2));
    }

    @Test
    void 编号未知404自检过期与恰截止422装置占用422() {
        JobResponse job = acceptAndCheck("b2", "T2");
        registerDevice("b2", "EOT-102");

        // 编号未知：404
        assertThrows(NotFoundException.class,
                () -> bind("b2a", job.id(), "EOT-UNKNOWN", 1));

        // 恰达有效截止时刻：失效，422
        Mockito.when(clock.instant()).thenReturn(VALID_UNTIL);
        BusinessException atDeadline = assertThrows(BusinessException.class,
                () -> bind("b2b", job.id(), "EOT-102", 1));
        assertTrue(atDeadline.getMessage().contains("自检"));

        // 超过截止时刻：422
        Mockito.when(clock.instant()).thenReturn(VALID_UNTIL.plusSeconds(1));
        assertThrows(BusinessException.class,
                () -> bind("b2c", job.id(), "EOT-102", 1));

        // 回到有效期绑定成功，随后装置被占用：其他作业再绑 422
        Mockito.when(clock.instant()).thenReturn(NOW);
        bind("b2d", job.id(), "EOT-102", 1);
        JobResponse other = acceptAndCheck("b2o", "T3");
        BusinessException occupied = assertThrows(BusinessException.class,
                () -> bind("b2e", other.id(), "EOT-102", 2));
        assertTrue(occupied.getMessage().contains("占用"));
    }

    @Test
    void 期望版本不匹配返回409且不改变占用() {
        JobResponse job = acceptAndCheck("b3", "T4");
        registerDevice("b3", "EOT-103");
        assertThrows(ConflictException.class,
                () -> bind("b3a", job.id(), "EOT-103", 9));
        // 未被占用，仍可按正确版本绑定
        assertEquals(2, bind("b3b", job.id(), "EOT-103", 1).deviceVersion());
    }

    // ---------- 双人一致性确认 ----------

    @Test
    void 双人确认匹配最新尾车版本后发车准许() {
        JobResponse job = acceptAndCheck("f1", "T5");
        passBrakeTests(job.id(), "f1");
        registerDevice("f1", "EOT-201");
        EotBindingResponse binding = bind("f1", job.id(), "EOT-201", 1);

        // 未确认前发车：422，不能用旧结论放行
        BusinessException noConfirm = assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(job.id(), new RequestIdOnly("f1-d0")));
        assertTrue(noConfirm.getMessage().contains("一致性"));

        // 同一名人员不能充当两人：422
        assertThrows(BusinessException.class,
                () -> confirm("f1x", job.id(), 2, "C10", 1, "张三", "张三"));

        // 尾车不匹配：422
        assertThrows(BusinessException.class,
                () -> confirm("f1y", job.id(), 2, "C9", 1, "张三", "李四"));

        // 编组版本不匹配：422
        assertThrows(BusinessException.class,
                () -> confirm("f1z", job.id(), 2, "C10", 2, "张三", "李四"));

        EotConfirmationResponse confirmation = confirm("f1", job.id(), 2, "C10", 1, "张三", "李四");
        assertTrue(confirmation.valid());
        assertEquals(3, confirmation.deviceVersion());
        assertEquals(binding.bindingId(), confirmation.bindingId());

        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("f1-d")).approved());
    }

    @Test
    void 摘挂改变尾车或版本后绑定保留但确认失效须双人重新确认() {
        JobResponse job = acceptAndCheck("f2", "T6");
        registerDevice("f2", "EOT-202");
        EotBindingResponse binding = bind("f2", job.id(), "EOT-202", 1);
        confirm("f2", job.id(), 2, "C10", 1, "张三", "李四");

        // 摘下尾车 C10、挂上新 C20：尾车变为 C20，编组版本升至 2
        ConsistChangeResponse change = jobService.changeConsist(job.id(), new ConsistChangeRequest(
                "f2-g", List.of(new CarDto("C20", new BigDecimal("80.0"), new BigDecimal("1.3"), false)),
                List.of("C10")));
        assertEquals(2, change.consistVersion());

        // 绑定保留
        assertTrue(bindingRepository.findActiveByJobId(job.id()).isPresent());
        assertEquals(binding.bindingId(), bindingRepository.findActiveByJobId(job.id()).orElseThrow().id());
        // 一致性确认立即失效
        assertTrue(confirmationRepository.findValidByJobId(job.id()).isEmpty());
        // 不能用旧合格结论放行
        assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(job.id(), new RequestIdOnly("f2-d0")));
        // 按旧尾车/旧版本重新确认：422
        assertThrows(BusinessException.class,
                () -> confirm("f2o1", job.id(), 3, "C10", 1, "张三", "李四"));
        assertThrows(BusinessException.class,
                () -> confirm("f2o2", job.id(), 3, "C10", 2, "张三", "李四"));

        // 重新核对运统1、重做制动试验、按新尾车新版本双人确认后方可发车
        ConsistCheckResponse recheck = jobService.checkConsist(job.id(), new ConsistCheckRequest(
                "f2-c2", 10, new BigDecimal("13.0"), new BigDecimal("800.0")));
        assertTrue(recheck.matched());
        passBrakeTests(job.id(), "f2b");
        EotConfirmationResponse reconfirmed = confirm("f2r", job.id(), 3, "C20", 2, "王五", "赵六");
        assertTrue(reconfirmed.valid());
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("f2-d")).approved());

        // 证据保留两条确认，仅最新一条有效
        EotDeviceEvidence evidence = eotService.getDeviceEvidence("EOT-202");
        assertEquals(1, evidence.bindings().size());
        assertEquals(2, evidence.bindings().get(0).confirmations().size());
        long validCount = evidence.bindings().get(0).confirmations().stream()
                .filter(c -> c.valid()).count();
        assertEquals(1, validCount);
    }

    @Test
    void 摘挂仅改变编组版本未改变尾车确认同样失效() {
        JobResponse job = acceptAndCheck("f3", "T7");
        registerDevice("f3", "EOT-203");
        bind("f3", job.id(), "EOT-203", 1);
        confirm("f3", job.id(), 2, "C10", 1, "张三", "李四");

        // 仅摘下中间车 C5（不挂车）：尾车仍为 C10，但编组版本升至 2
        ConsistChangeResponse change = jobService.changeConsist(job.id(), new ConsistChangeRequest(
                "f3-g", List.of(), List.of("C5")));
        assertEquals(2, change.consistVersion());
        assertTrue(confirmationRepository.findValidByJobId(job.id()).isEmpty());
        // 旧版本确认被拒
        assertThrows(BusinessException.class,
                () -> confirm("f3o", job.id(), 3, "C10", 1, "张三", "李四"));
        EotConfirmationResponse reconfirmed = confirm("f3r", job.id(), 3, "C10", 2, "张三", "李四");
        assertTrue(reconfirmed.valid());
    }

    @Test
    void 发车时装置自检过期拒绝发车() {
        JobResponse job = acceptAndCheck("f4", "T8");
        passBrakeTests(job.id(), "f4");
        registerDevice("f4", "EOT-204", NOW.plusSeconds(60));
        bind("f4", job.id(), "EOT-204", 1);
        confirm("f4", job.id(), 2, "C10", 1, "张三", "李四");

        // 时钟走到恰达截止：拒绝
        Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        BusinessException e = assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(job.id(), new RequestIdOnly("f4-d1")));
        assertTrue(e.getMessage().contains("自检"));

        // 提前 1 秒仍可发车
        Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(59));
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("f4-d2")).approved());
    }

    // ---------- 提前解绑 ----------

    @Test
    void 发车前提前解绑释放装置并撤销确认历史保留() {
        JobResponse job = acceptAndCheck("u1", "T9");
        registerDevice("u1", "EOT-301");
        EotBindingResponse binding = bind("u1", job.id(), "EOT-301", 1);
        confirm("u1", job.id(), 2, "C10", 1, "张三", "李四");

        // 期望版本错误：409 且占用不变
        assertThrows(ConflictException.class,
                () -> eotService.unbind(job.id(), new EotUnbindRequest("u1-x0", 9)));
        assertTrue(bindingRepository.findActiveByJobId(job.id()).isPresent());

        EotUnbindResponse unbind = eotService.unbind(job.id(), new EotUnbindRequest("u1-x1", 3));
        assertTrue(unbind.released());
        assertEquals(1, unbind.voidedConfirmations());
        assertEquals(4, unbind.deviceVersion());
        assertNull(deviceRepository.findByDeviceNo("EOT-301").orElseThrow().boundJobId());
        assertEquals("RELEASED", bindingRepository.findById(binding.bindingId()).orElseThrow().status().name());
        assertTrue(confirmationRepository.findValidByJobId(job.id()).isEmpty());

        // 作业回到未启用列尾控制状态：制动合格即可发车，无新增门禁
        passBrakeTests(job.id(), "u1");
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("u1-d")).approved());

        // 发车后不得再提前解绑
        assertThrows(BusinessException.class,
                () -> eotService.unbind(job.id(), new EotUnbindRequest("u1-x2", 4)));

        // 装置可被重新绑定；旧绑定历史保留
        JobResponse other = acceptAndCheck("u1o", "T10");
        EotBindingResponse rebind = bind("u1r", other.id(), "EOT-301", 4);
        assertEquals(5, rebind.deviceVersion());
        EotDeviceEvidence evidence = eotService.getDeviceEvidence("EOT-301");
        assertEquals(2, evidence.bindings().size());
        assertEquals("RELEASED", evidence.bindings().get(0).status());
        assertEquals("ACTIVE", evidence.bindings().get(1).status());
        assertEquals(1, evidence.bindings().get(0).confirmations().size());
    }

    @Test
    void 无生效绑定时解绑422() {
        JobResponse job = acceptAndCheck("u2", "T11");
        assertThrows(BusinessException.class,
                () -> eotService.unbind(job.id(), new EotUnbindRequest("u2-x", 1)));
    }

    // ---------- 销记联动 ----------

    @Test
    void 销记原子释放装置占用并保留全部绑定历史() {
        JobResponse job = acceptAndCheck("c1", "T12");
        passBrakeTests(job.id(), "c1");
        registerDevice("c1", "EOT-401");
        EotBindingResponse binding = bind("c1", job.id(), "EOT-401", 1);
        confirm("c1", job.id(), 2, "C10", 1, "张三", "李四");
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly("c1-d")).approved());

        jobService.close(job.id(), new RequestIdOnly("c1-x"));
        assertNull(deviceRepository.findByDeviceNo("EOT-401").orElseThrow().boundJobId());
        assertEquals("RELEASED", bindingRepository.findById(binding.bindingId()).orElseThrow().status().name());
        assertTrue(confirmationRepository.findValidByJobId(job.id()).isEmpty());
        // 历史仍可查询
        EotDeviceEvidence evidence = eotService.getDeviceEvidence("EOT-401");
        assertEquals(1, evidence.bindings().size());
        assertNotNull(evidence.bindings().get(0).releasedAt());
        assertEquals(1, evidence.bindings().get(0).confirmations().size());
        assertFalse(evidence.bindings().get(0).confirmations().get(0).valid());
        // 作业证据同样可查
        List<EotBindingEvidence> jobEvidence = eotService.getJobEvidence(job.id());
        assertEquals(1, jobEvidence.size());
    }

    // ---------- 幂等与结构化指纹 ----------

    @Test
    void 同键同参重放首次冻结结果同键异参409失败不占键() {
        JobResponse job = acceptAndCheck("i1", "T13");
        registerDevice("i1", "EOT-501");
        EotBindRequest request = new EotBindRequest("i1-b", "EOT-501", 1);
        EotBindingResponse first = eotService.bind(job.id(), request);
        EotBindingResponse replay = eotService.bind(job.id(), request);
        assertEquals(first.bindingId(), replay.bindingId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM eot_binding", Integer.class));

        // 同键异参（版本号不同）：409，不新增绑定
        assertThrows(ConflictException.class,
                () -> eotService.bind(job.id(), new EotBindRequest("i1-b", "EOT-501", 2)));
        // 同键异参（装置不同）：409
        registerDevice("i1b", "EOT-502");
        assertThrows(ConflictException.class,
                () -> eotService.bind(job.id(), new EotBindRequest("i1-b", "EOT-502", 1)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM eot_binding", Integer.class));

        // 失败不占键：未知装置绑定失败后，同键可用于合法装置
        JobResponse other = acceptAndCheck("i1o", "T14");
        assertThrows(NotFoundException.class,
                () -> eotService.bind(other.id(), new EotBindRequest("i1-fail", "EOT-MISSING", 1)));
        EotBindingResponse afterFailure = eotService.bind(other.id(),
                new EotBindRequest("i1-fail", "EOT-502", 1));
        assertNotNull(afterFailure.bindingId());
    }

    @Test
    void 幂等指纹结构化区分含分隔符与引号的合法文本() {
        // 合法编号中含逗号、引号等分隔符，仍可建档、重放且不破坏结构化指纹
        String trickyNo = "EOT,601\u0001a\":\"b";
        EotDeviceRegisterRequest request = new EotDeviceRegisterRequest("i2-r", trickyNo, VALID_UNTIL);
        EotDeviceResponse first = eotService.register(request);
        EotDeviceResponse replay = eotService.register(request);
        assertEquals(first.id(), replay.id());
        assertEquals(trickyNo, deviceRepository.findById(first.id()).orElseThrow().deviceNo());

        // 同键异参（仅编号不同，哪怕包含分隔符）：409
        assertThrows(ConflictException.class,
                () -> eotService.register(new EotDeviceRegisterRequest("i2-r",
                        trickyNo + "x", VALID_UNTIL)));
        // 同键异参（精确时刻不同）：409
        assertThrows(ConflictException.class,
                () -> eotService.register(new EotDeviceRegisterRequest("i2-r",
                        trickyNo, VALID_UNTIL.plusSeconds(1))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM eot_device", Integer.class));

        // 含分隔符的编号同样可走绑定/证据查询（查询参数形式）
        JobResponse job = acceptAndCheck("i2", "T15");
        EotBindingResponse binding = bind("i2", job.id(), trickyNo, 1);
        EotDeviceEvidence evidence = eotService.getDeviceEvidence(trickyNo);
        assertEquals(binding.bindingId(), evidence.bindings().get(0).bindingId());
    }

    // ---------- 并发边界 ----------

    @Test
    void 同一装置与其他列车并发绑定唯一成功() throws Exception {
        JobResponse j1 = acceptAndCheck("n1", "T20");
        JobResponse j2 = acceptAndCheck("n2", "T21");
        registerDevice("n1", "EOT-701");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        long[] jobIds = {j1.id(), j2.id()};
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    eotService.bind(jobIds[idx], new EotBindRequest("n1-b" + idx, "EOT-701", 1));
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(1, success.get());
        assertEquals(1, rejected.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM eot_binding WHERE status = 'ACTIVE'", Integer.class));
        assertNotNull(deviceRepository.findByDeviceNo("EOT-701").orElseThrow().boundJobId());
    }

    @Test
    void 摘挂与发车并发竞争最终状态一致() throws Exception {
        JobResponse job = acceptAndCheck("n3", "T22");
        passBrakeTests(job.id(), "n3");
        registerDevice("n3", "EOT-702");
        bind("n3", job.id(), "EOT-702", 1);
        confirm("n3", job.id(), 2, "C10", 1, "张三", "李四");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> changeFailure = new AtomicReference<>();
        AtomicReference<Throwable> departureFailure = new AtomicReference<>();
        Runnable await = () -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<?> changeFuture = pool.submit(() -> {
            await.run();
            try {
                jobService.changeConsist(job.id(), new ConsistChangeRequest("n3-g",
                        List.of(new CarDto("C11", new BigDecimal("80.0"), new BigDecimal("1.3"), false)),
                        List.of("C1")));
            } catch (Throwable t) {
                changeFailure.set(t);
            }
        });
        Future<?> departureFuture = pool.submit(() -> {
            await.run();
            try {
                jobService.confirmDeparture(job.id(), new RequestIdOnly("n3-d"));
            } catch (Throwable t) {
                departureFailure.set(t);
            }
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        changeFuture.get(20, TimeUnit.SECONDS);
        departureFuture.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        JobResponse finalJob = jobService.getJob(job.id());
        if ("DEPARTURE_CONFIRMED".equals(finalJob.status())) {
            // 发车先提交：摘挂必须失败，确认仍有效，装置仍占用
            assertNull(departureFailure.get());
            assertTrue(changeFailure.get() instanceof BusinessException);
            assertEquals(1, finalJob.consistVersion());
            assertTrue(confirmationRepository.findValidByJobId(job.id()).isPresent());
            assertNotNull(deviceRepository.findByDeviceNo("EOT-702").orElseThrow().boundJobId());
        } else {
            // 摘挂先提交：发车必须被拒，版本升至2，确认已失效
            assertEquals("ACCEPTED", finalJob.status());
            assertEquals(2, finalJob.consistVersion());
            assertTrue(departureFailure.get() instanceof BusinessException);
            assertTrue(confirmationRepository.findValidByJobId(job.id()).isEmpty());
            // 绑定仍保留
            assertTrue(bindingRepository.findActiveByJobId(job.id()).isPresent());
        }
    }

    // ---------- 查询与历史作业 ----------

    @Test
    void 证据查询与未启用列尾历史作业无门禁() {
        assertThrows(NotFoundException.class, () -> eotService.getDeviceEvidence("EOT-NONE"));

        JobResponse legacy = acceptAndCheck("q1", "T30");
        passBrakeTests(legacy.id(), "q1");
        // 未绑定列尾装置：不增加新门禁
        assertTrue(jobService.confirmDeparture(legacy.id(), new RequestIdOnly("q1-d")).approved());
        // 作业列尾证据为空列表
        assertTrue(eotService.getJobEvidence(legacy.id()).isEmpty());
        assertThrows(NotFoundException.class, () -> eotService.getJobEvidence(999999));
    }
}
