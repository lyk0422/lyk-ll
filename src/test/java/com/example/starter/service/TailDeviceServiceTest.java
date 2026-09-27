package com.example.starter.service;

import com.example.starter.domain.TailReleaseType;
import com.example.starter.repo.JobRow;
import com.example.starter.repo.TailBindingRow;
import com.example.starter.repo.TailConfirmationRow;
import com.example.starter.repo.TailDeviceRepository;
import com.example.starter.repo.TailDeviceRow;
import com.example.starter.repo.TrainJobRepository;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.CarDto;
import com.example.starter.web.dto.ClosureResponse;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.DepartureResponse;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.web.dto.TailBindRequest;
import com.example.starter.web.dto.TailBindResponse;
import com.example.starter.web.dto.TailConfirmRequest;
import com.example.starter.web.dto.TailConfirmResponse;
import com.example.starter.web.dto.TailDeviceRegisterRequest;
import com.example.starter.web.dto.TailDeviceResponse;
import com.example.starter.web.dto.TailEvidenceResponse;
import com.example.starter.web.dto.TailUnbindRequest;
import com.example.starter.web.dto.TailUnbindResponse;
import com.example.starter.domain.TestType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列尾装置控制服务测试：主流程、失败分支、版本/幂等边界，以及基于真实 H2 事务的并发与门禁验证。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_tail_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TailDeviceServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-28T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-28T12:00:00Z");

    @Autowired
    private TailDeviceService tailService;
    @Autowired
    private TrainJobService jobService;
    @Autowired
    private TailDeviceRepository deviceRepository;
    @Autowired
    private TrainJobRepository trainJobRepository;
    @Autowired
    private BusinessClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM tail_confirmation");
        jdbc.update("DELETE FROM tail_binding");
        jdbc.update("DELETE FROM tail_device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM pressure_reading");
        jdbc.update("DELETE FROM brake_test");
        jdbc.update("DELETE FROM consist_check");
        jdbc.update("DELETE FROM train_car");
        jdbc.update("DELETE FROM train_job");
        clock.pin(T0);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    private List<CarDto> cars(int total) {
        List<CarDto> cars = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            cars.add(new CarDto("C" + i, new BigDecimal("80.0"), new BigDecimal("1.3"), false));
        }
        return cars;
    }

    /** 接入作业（10 辆，尾车 C10）。 */
    private long acceptJob(String key, String track) {
        return jobService.accept(new AcceptJobRequest(key, "T-" + key, track, cars(10))).id();
    }

    /** 完成运统1核对 + 感度 + 安定，使制动试验门禁满足。 */
    private void prepareBrakeChecks(long jobId, String keyPrefix) {
        jobService.checkConsist(jobId, new ConsistCheckRequest(keyPrefix + "-c", 10,
                new BigDecimal("13.0"), new BigDecimal("800.0")));
        jobService.brakeTest(jobId, new BrakeTestRequest(keyPrefix + "-s", TestType.SENSITIVITY,
                50, 500, true, null));
        jobService.brakeTest(jobId, new BrakeTestRequest(keyPrefix + "-t", TestType.STABILITY,
                140, 500, null, false));
    }

    private TailDeviceResponse register(String key, String deviceNo, Instant expires) {
        return tailService.register(new TailDeviceRegisterRequest(key, deviceNo, expires));
    }

    private TailBindResponse bind(long jobId, String key, String deviceNo, int version, String tailCar) {
        return tailService.bind(jobId, new TailBindRequest(key, version, deviceNo, tailCar,
                T0.plusSeconds(60)));
    }

    private TailConfirmResponse confirm(long jobId, String key, int version, String p1, String p2) {
        return tailService.confirm(jobId, new TailConfirmRequest(key, version, p1, p2,
                T0.plusSeconds(120)));
    }

    @Test
    void 建档版本从1开始且空闲() {
        TailDeviceResponse device = register("r1", "D1", EXPIRES);
        assertEquals("D1", device.deviceNo());
        assertEquals(1, device.version());
        assertFalse(device.occupied());
        assertNull(device.boundJobId());
        assertEquals(EXPIRES, device.selfCheckExpiresAt());
    }

    @Test
    void 重复建档编号冲突409() {
        register("r2a", "D2", EXPIRES);
        // 同编号新请求键：409，且失败不占键——换编号同键可成功建档
        assertThrows(ConflictException.class,
                () -> tailService.register(new TailDeviceRegisterRequest("r2b", "D2", EXPIRES)));
        TailDeviceResponse other = tailService.register(
                new TailDeviceRegisterRequest("r2b", "D3", EXPIRES));
        assertEquals("D3", other.deviceNo());
    }

    @Test
    void 主流程绑定双人确认发车销记释放并保留历史() {
        long jobId = acceptJob("m-a", "M1");
        prepareBrakeChecks(jobId, "m");
        register("m-r", "MD", EXPIRES);

        TailBindResponse bound = bind(jobId, "m-b", "MD", 1, "C10");
        assertEquals(1, bound.consistVersion());
        assertEquals("C10", bound.tailCarNo());
        assertEquals(2, bound.deviceVersion());

        TailDeviceRow occupied = deviceRepository.findByDeviceNo("MD").orElseThrow();
        assertEquals(2, occupied.version());
        assertEquals(jobId, occupied.boundJobId());

        TailConfirmResponse confirmed = confirm(jobId, "m-f", 2, "zhang", "wang");
        assertEquals(1, confirmed.consistVersion());
        assertEquals("C10", confirmed.tailCarNo());
        assertEquals(3, confirmed.deviceVersion());

        DepartureResponse departure = jobService.confirmDeparture(jobId, new RequestIdOnly("m-d"));
        assertTrue(departure.approved());

        ClosureResponse closure = jobService.close(jobId, new RequestIdOnly("m-x"));
        assertTrue(closure.closed());

        // 销记原子释放装置占用且版本加1
        TailDeviceRow released = deviceRepository.findByDeviceNo("MD").orElseThrow();
        assertNull(released.boundJobId());
        assertEquals(4, released.version());

        // 证据：绑定历史保留为 CLOSURE，确认失效原因为 CLOSURE
        TailEvidenceResponse evidence = tailService.getEvidence("MD");
        assertEquals(1, evidence.bindings().size());
        TailEvidenceResponse.BindingEvidence b = evidence.bindings().get(0);
        assertFalse(b.active());
        assertEquals(TailReleaseType.CLOSURE.name(), b.releaseType());
        assertEquals(1, b.confirmations().size());
        TailEvidenceResponse.ConfirmationEvidence c = b.confirmations().get(0);
        assertFalse(c.valid());
        assertEquals(TailDeviceService.REASON_CLOSURE, c.invalidatedReason());
    }

    @Test
    void 编号未知绑定拒绝422() {
        long jobId = acceptJob("u-a", "U1");
        BusinessException e = assertThrows(BusinessException.class,
                () -> bind(jobId, "u-b", "GHOST", 1, "C10"));
        assertTrue(e.getMessage().contains("未知"));
    }

    @Test
    void 自检恰到截止即失效不得绑定() {
        long jobId = acceptJob("e1-a", "E1");
        register("e1-r", "E1D", EXPIRES);
        // now 恰等于截止时刻：失效
        clock.pin(EXPIRES);
        assertThrows(BusinessException.class, () -> bind(jobId, "e1-b", "E1D", 1, "C10"));
        // now 晚于截止时刻：失效
        clock.pin(EXPIRES.plusSeconds(1));
        assertThrows(BusinessException.class, () -> bind(jobId, "e1-b2", "E1D", 1, "C10"));
        // 装置仍空闲
        assertNull(deviceRepository.findByDeviceNo("E1D").orElseThrow().boundJobId());
        // 提前一秒：有效，可绑定
        clock.pin(EXPIRES.minusSeconds(1));
        assertEquals(2, bind(jobId, "e1-b3", "E1D", 1, "C10").deviceVersion());
    }

    @Test
    void 装置已占用拒绝其他作业绑定() {
        long j1 = acceptJob("o1-a", "O1");
        long j2 = acceptJob("o2-a", "O2");
        register("o-r", "OD", EXPIRES);
        bind(j1, "o-b1", "OD", 1, "C10");
        BusinessException e = assertThrows(BusinessException.class,
                () -> bind(j2, "o-b2", "OD", 2, "C10"));
        assertTrue(e.getMessage().contains("占用"));
        assertEquals(j1, deviceRepository.findByDeviceNo("OD").orElseThrow().boundJobId());
    }

    @Test
    void 绑定版本不匹配拒绝422() {
        long jobId = acceptJob("v-a", "V1");
        register("v-r", "VD", EXPIRES);
        assertThrows(BusinessException.class, () -> bind(jobId, "v-b", "VD", 2, "C10"));
        assertThrows(BusinessException.class, () -> bind(jobId, "v-b2", "VD", 0, "C10"));
    }

    @Test
    void 绑定尾车与当前编组尾车不一致拒绝() {
        long jobId = acceptJob("w-a", "W1");
        register("w-r", "WD", EXPIRES);
        assertThrows(BusinessException.class, () -> bind(jobId, "w-b", "WD", 1, "C9"));
    }

    @Test
    void 两名确认人员相同拒绝422() {
        long jobId = acceptJob("p-a", "P1");
        register("p-r", "PD", EXPIRES);
        bind(jobId, "p-b", "PD", 1, "C10");
        assertThrows(BusinessException.class, () -> confirm(jobId, "p-f", 2, "li", "li"));
    }

    @Test
    void 无生效绑定不得共同确认() {
        long jobId = acceptJob("nb-a", "NB1");
        register("nb-r", "NBD", EXPIRES);
        assertThrows(BusinessException.class,
                () -> confirm(jobId, "nb-f", 1, "li", "wang"));
    }

    @Test
    void 有绑定但无确认发车拒绝() {
        long jobId = acceptJob("nc-a", "NC1");
        prepareBrakeChecks(jobId, "nc");
        register("nc-r", "NCD", EXPIRES);
        bind(jobId, "nc-b", "NCD", 1, "C10");
        BusinessException e = assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(jobId, new RequestIdOnly("nc-d")));
        assertTrue(e.getMessage().contains("一致性确认"));
        assertEquals("CHECKED", jobService.getJob(jobId).status());
    }

    @Test
    void 发车时自检过期拒绝不得用旧确认放行() {
        long jobId = acceptJob("ex-a", "EX1");
        prepareBrakeChecks(jobId, "ex");
        register("ex-r", "EXD", EXPIRES);
        bind(jobId, "ex-b", "EXD", 1, "C10");
        confirm(jobId, "ex-f", 2, "zhang", "wang");
        // 装置自检在发车前到期：旧确认不得放行
        clock.pin(EXPIRES);
        assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(jobId, new RequestIdOnly("ex-d")));
    }

    @Test
    void 摘挂后绑定保留但确认立即失效须重新双人确认新版本尾车() {
        long jobId = acceptJob("ch-a", "CH1");
        prepareBrakeChecks(jobId, "ch");
        register("ch-r", "CHD", EXPIRES);
        bind(jobId, "ch-b", "CHD", 1, "C10");
        confirm(jobId, "ch-f", 2, "zhang", "wang");

        // 摘下尾车 C10：尾车变为 C9、编组版本升至 2；绑定保留，确认失效
        jobService.changeConsist(jobId, new ConsistChangeRequest("ch-g",
                List.of(), List.of("C10")));

        TailEvidenceResponse evidenceBefore = tailService.getEvidence("CHD");
        TailEvidenceResponse.BindingEvidence bindingBefore = evidenceBefore.bindings().get(0);
        assertTrue(bindingBefore.active(), "摘挂不得解除绑定");
        assertFalse(bindingBefore.confirmations().get(0).valid());
        assertEquals(TailDeviceService.REASON_CONSIST_CHANGED,
                bindingBefore.confirmations().get(0).invalidatedReason());

        // 旧确认放行被拒
        assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(jobId, new RequestIdOnly("ch-d1")));

        // 重新核对、重做试验并按新尾车 C9/版本2共同确认后可发车
        jobService.checkConsist(jobId, new ConsistCheckRequest("ch-c2", 9,
                new BigDecimal("11.7"), new BigDecimal("720.0")));
        jobService.brakeTest(jobId, new BrakeTestRequest("ch-s2", TestType.SENSITIVITY,
                50, 500, true, null));
        jobService.brakeTest(jobId, new BrakeTestRequest("ch-t2", TestType.STABILITY,
                140, 500, null, false));
        TailConfirmResponse reconfirmed = confirm(jobId, "ch-f2", 3, "zhang", "chen");
        assertEquals(2, reconfirmed.consistVersion());
        assertEquals("C9", reconfirmed.tailCarNo());
        assertEquals(4, reconfirmed.deviceVersion());

        DepartureResponse departure = jobService.confirmDeparture(jobId, new RequestIdOnly("ch-d2"));
        assertTrue(departure.approved());
    }

    @Test
    void 发车前提前解绑撤销确认释放装置且发车被拒() {
        long jobId = acceptJob("ub-a", "UB1");
        prepareBrakeChecks(jobId, "ub");
        register("ub-r", "UBD", EXPIRES);
        bind(jobId, "ub-b", "UBD", 1, "C10");
        confirm(jobId, "ub-f", 2, "zhang", "wang");

        TailUnbindResponse unbound = tailService.unbind(jobId,
                new TailUnbindRequest("ub-u", 3, T0.plusSeconds(300)));
        assertEquals(1, unbound.revokedConfirmations());
        assertEquals(4, unbound.deviceVersion());

        TailDeviceRow free = deviceRepository.findByDeviceNo("UBD").orElseThrow();
        assertNull(free.boundJobId());

        // 受控作业已提前解绑，无生效绑定不得放行，须重新绑定确认
        assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(jobId, new RequestIdOnly("ub-d")));

        // 重新绑定（版本4）+确认（版本5）后可发车
        bind(jobId, "ub-b2", "UBD", 4, "C10");
        confirm(jobId, "ub-f2", 5, "li", "wang");
        assertTrue(jobService.confirmDeparture(jobId, new RequestIdOnly("ub-d2")).approved());

        // 绑定历史保留两条，首条 UNBIND
        TailEvidenceResponse evidence = tailService.getEvidence("UBD");
        assertEquals(2, evidence.bindings().size());
        assertEquals(TailReleaseType.UNBIND.name(), evidence.bindings().get(0).releaseType());
    }

    @Test
    void 发车后不得提前解绑() {
        long jobId = acceptJob("ua-a", "UA1");
        prepareBrakeChecks(jobId, "ua");
        register("ua-r", "UAD", EXPIRES);
        bind(jobId, "ua-b", "UAD", 1, "C10");
        confirm(jobId, "ua-f", 2, "zhang", "wang");
        jobService.confirmDeparture(jobId, new RequestIdOnly("ua-d"));
        assertThrows(BusinessException.class, () -> tailService.unbind(jobId,
                new TailUnbindRequest("ua-u", 3, T0.plusSeconds(300))));
    }

    @Test
    void 解绑版本不匹配拒绝且不改变占用() {
        long jobId = acceptJob("uv-a", "UV1");
        register("uv-r", "UVD", EXPIRES);
        bind(jobId, "uv-b", "UVD", 1, "C10");
        confirm(jobId, "uv-f", 2, "a", "b");
        assertThrows(BusinessException.class, () -> tailService.unbind(jobId,
                new TailUnbindRequest("uv-u", 2, T0.plusSeconds(300))));
        assertEquals(jobId, deviceRepository.findByDeviceNo("UVD").orElseThrow().boundJobId());
    }

    @Test
    void 同键同参重放首次冻结结果异参409失败不占键() {
        long jobId = acceptJob("id-a", "ID1");
        register("id-r", "IDD", EXPIRES);

        TailBindRequest bindRequest = new TailBindRequest("id-b", 1, "IDD", "C10",
                T0.plusSeconds(60));
        TailBindResponse first = tailService.bind(jobId, bindRequest);
        TailBindResponse replay = tailService.bind(jobId, bindRequest);
        assertEquals(first.bindingId(), replay.bindingId());
        assertEquals(first.deviceVersion(), replay.deviceVersion());

        // 同键异参：409，且未新增绑定
        assertThrows(ConflictException.class, () -> tailService.bind(jobId,
                new TailBindRequest("id-b", 1, "IDD", "C9", T0.plusSeconds(60))));

        // 失败不占键：同键先以错误版本失败（同参重试仍失败），随后不影响其他键
        assertThrows(BusinessException.class, () -> tailService.bind(jobId,
                new TailBindRequest("id-bad", 99, "IDD", "C10", T0.plusSeconds(60))));
        assertThrows(BusinessException.class, () -> tailService.bind(jobId,
                new TailBindRequest("id-bad", 99, "IDD", "C10", T0.plusSeconds(60))));

        // 失败不占键：同键先以“同一人确认”失败，再以同键不同合法参数成功（结构化指纹区分，键未被失败占用）
        assertThrows(BusinessException.class, () -> tailService.confirm(jobId,
                new TailConfirmRequest("id-f", 2, "same", "same", T0.plusSeconds(120))));
        TailConfirmResponse confirmed = tailService.confirm(jobId,
                new TailConfirmRequest("id-f", 2, "zhang", "wang", T0.plusSeconds(120)));
        assertEquals("zhang", confirmed.confirmer1());
    }

    @Test
    void 指纹结构化区分合法文本含分隔符的不同请求() {
        long jobId = acceptJob("fp-a", "FP1");
        register("fp-r", "FPD", EXPIRES);
        bind(jobId, "fp-b", "FPD", 1, "C10");
        // 人员名含分隔符 | 与逗号仍为合法文本
        TailConfirmResponse a = confirm(jobId, "fp-f1", 2, "a|b", "c,d");
        assertNotEquals(0, a.confirmationId());
        // 首次成功：confirmer1="ab", confirmer2="c"
        tailService.confirm(jobId, new TailConfirmRequest("fp-f2", 3, "ab", "c",
                T0.plusSeconds(120)));
        // 同键、同期望版本3、同时刻，仅跨字段切分为 "a" + "bc"（朴素拼接 ab|c 与 a|bc 会同值）：
        // JSON 结构化指纹必须判为异参 409
        assertThrows(ConflictException.class, () -> tailService.confirm(jobId,
                new TailConfirmRequest("fp-f2", 3, "a", "bc", T0.plusSeconds(120))));
    }

    @Test
    void 重复共同确认取代旧确认但保留历史() {
        long jobId = acceptJob("sup-a", "SUP1");
        prepareBrakeChecks(jobId, "sup");
        register("sup-r", "SUPD", EXPIRES);
        bind(jobId, "sup-b", "SUPD", 1, "C10");
        confirm(jobId, "sup-f1", 2, "zhang", "wang");
        confirm(jobId, "sup-f2", 3, "li", "chen");

        TailEvidenceResponse evidence = tailService.getEvidence("SUPD");
        var confirmations = evidence.bindings().get(0).confirmations();
        assertEquals(2, confirmations.size());
        assertFalse(confirmations.get(0).valid());
        assertEquals(TailDeviceService.REASON_SUPERSEDED, confirmations.get(0).invalidatedReason());
        assertTrue(confirmations.get(1).valid());
    }

    @Test
    void 证据查询未知装置404() {
        assertThrows(NotFoundException.class, () -> tailService.getEvidence("NOPE"));
    }

    @Test
    void 并发绑定同一装置仅唯一成功() throws Exception {
        long j1 = acceptJob("cc1-a", "CC1");
        long j2 = acceptJob("cc2-a", "CC2");
        register("cc-r", "CCD", EXPIRES);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        long[] jobIds = {j1, j2};
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                awaitBarrier(ready, start);
                try {
                    bind(jobIds[idx], "cc-b" + idx, "CCD", 1, "C10");
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, success.get());
        assertEquals(1, rejected.get());
        TailDeviceRow device = deviceRepository.findByDeviceNo("CCD").orElseThrow();
        assertEquals(2, device.version());
        Integer activeBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tail_binding WHERE device_id = ? AND released_at IS NULL",
                Integer.class, device.id());
        assertEquals(1, activeBindings);
    }

    @Test
    void 发车与发车前解绑并发保持一致终态() throws Exception {
        long jobId = acceptJob("race-a", "RC1");
        prepareBrakeChecks(jobId, "race");
        register("race-r", "RCD", EXPIRES);
        bind(jobId, "race-b", "RCD", 1, "C10");
        confirm(jobId, "race-f", 2, "zhang", "wang");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger departureOk = new AtomicInteger();
        AtomicInteger unbindOk = new AtomicInteger();
        Runnable departure = () -> {
            awaitBarrier(ready, start);
            try {
                jobService.confirmDeparture(jobId, new RequestIdOnly("race-d"));
                departureOk.incrementAndGet();
            } catch (BusinessException ignored) {
                // 被解绑先行时预期失败
            }
        };
        Runnable unbind = () -> {
            awaitBarrier(ready, start);
            try {
                tailService.unbind(jobId, new TailUnbindRequest("race-u", 3,
                        T0.plusSeconds(300)));
                unbindOk.incrementAndGet();
            } catch (BusinessException ignored) {
                // 被发车确认先行时预期失败
            }
        };
        Future<?> f1 = pool.submit(departure);
        Future<?> f2 = pool.submit(unbind);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 二者恰有其一成功，终态一致
        assertEquals(1, departureOk.get() + unbindOk.get());
        JobRow job = trainJobRepository.findById(jobId).orElseThrow();
        TailDeviceRow device = deviceRepository.findByDeviceNo("RCD").orElseThrow();
        Integer activeBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tail_binding WHERE job_id = ? AND released_at IS NULL",
                Integer.class, jobId);
        if (departureOk.get() == 1) {
            assertEquals("DEPARTURE_CONFIRMED", job.status().name());
            assertEquals(jobId, device.boundJobId());
            assertEquals(1, activeBindings);
        } else {
            assertEquals("CHECKED", job.status().name());
            assertNull(device.boundJobId());
            assertEquals(0, activeBindings);
        }
    }

    @Test
    void 销记不影响无绑定历史作业的既有流程() {
        // 未启用列尾控制：旧流程无新门禁（既有套件已覆盖，这里额外确认证据侧无绑定）
        long jobId = acceptJob("leg-a", "LG1");
        prepareBrakeChecks(jobId, "leg");
        assertTrue(jobService.confirmDeparture(jobId, new RequestIdOnly("leg-d")).approved());
        jobService.close(jobId, new RequestIdOnly("leg-x"));
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tail_binding WHERE job_id = ?", Integer.class, jobId);
        assertEquals(0, rows);
    }

    private void awaitBarrier(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
