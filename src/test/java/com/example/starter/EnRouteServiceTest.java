package com.example.starter.service;

import com.example.starter.domain.DispositionStatus;
import com.example.starter.domain.TestType;
import com.example.starter.repo.DispositionRow;
import com.example.starter.repo.EnRouteDispositionRepository;
import com.example.starter.repo.EotDeviceRepository;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.CarDto;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.ConsistChangeResponse;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.EnRouteAbnormalityRequest;
import com.example.starter.web.dto.EnRouteConfirmationResponse;
import com.example.starter.web.dto.EnRouteConfirmRequest;
import com.example.starter.web.dto.EnRouteDispositionEvidence;
import com.example.starter.web.dto.EnRouteDispositionResponse;
import com.example.starter.web.dto.EnRouteResumeResponse;
import com.example.starter.web.dto.EnRouteResumeRequest;
import com.example.starter.web.dto.EnRouteTestRequest;
import com.example.starter.web.dto.EnRouteTestResponse;
import com.example.starter.web.dto.EotBindRequest;
import com.example.starter.web.dto.EotConfirmRequest;
import com.example.starter.web.dto.EotDeviceRegisterRequest;
import com.example.starter.web.dto.EotUnbindRequest;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.web.dto.TestCurveResponse;
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
import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 途中异常停车与续行许可服务测试：UTC 可控时钟、H2 真实事务/唯一约束、
 * 主流程/失败分支/编组变更/试验失效边界/续行竞争与幂等边界。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_enroute_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Import(EnRouteServiceTest.TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnRouteServiceTest {

    static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    static final Instant DEVICE_VALID_UNTIL = NOW.plus(Duration.ofHours(3));

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Mockito.mock(Clock.class);
        }
    }

    @Autowired
    private EnRouteService enRouteService;

    @Autowired
    private TrainJobService jobService;

    @Autowired
    private EotService eotService;

    @Autowired
    private EnRouteDispositionRepository dispositionRepository;

    @Autowired
    private EotDeviceRepository deviceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        Mockito.reset(clock);
        Mockito.when(clock.instant()).thenReturn(NOW);
        jdbc.update("DELETE FROM en_route_confirmation");
        jdbc.update("DELETE FROM en_route_test");
        jdbc.update("DELETE FROM en_route_disposition");
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
        // 测试上下文结束后释放命名内存库（DB_CLOSE_DELAY=-1 不会自动释放）
        jdbc.execute("SHUTDOWN");
    }

    // ---------- 合成数据构造 ----------

    private List<CarDto> cars(int total) {
        List<CarDto> cars = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            cars.add(new CarDto("C" + i, new BigDecimal("80.0"), new BigDecimal("1.3"), false));
        }
        return cars;
    }

    /** 接入、运统1核对、感度/安定合格并确认发车（未绑定列尾）。 */
    private JobResponse departJob(String key, String track) {
        JobResponse job = jobService.accept(new AcceptJobRequest(key + "-a", "T" + key, track, cars(10)));
        assertTrue(jobService.checkConsist(job.id(), new ConsistCheckRequest(
                key + "-c", 10, new BigDecimal("13.0"), new BigDecimal("800.0"))).matched());
        passBrakeTests(job.id(), key);
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly(key + "-d")).approved());
        return jobService.getJob(job.id());
    }

    /** 接入、绑定列尾并双人确认、试验合格并确认发车。 */
    private JobResponse departJobWithEot(String key, String track, String deviceNo) {
        JobResponse job = jobService.accept(new AcceptJobRequest(key + "-a", "T" + key, track, cars(10)));
        assertTrue(jobService.checkConsist(job.id(), new ConsistCheckRequest(
                key + "-c", 10, new BigDecimal("13.0"), new BigDecimal("800.0"))).matched());
        eotService.register(new EotDeviceRegisterRequest(key + "-er", deviceNo, DEVICE_VALID_UNTIL));
        eotService.bind(job.id(), new EotBindRequest(key + "-eb", deviceNo, 1));
        eotService.confirm(job.id(), new EotConfirmRequest(key + "-ef", 2, "C10", 1, "张三", "李四"));
        passBrakeTests(job.id(), key);
        assertTrue(jobService.confirmDeparture(job.id(), new RequestIdOnly(key + "-d")).approved());
        return jobService.getJob(job.id());
    }

    private void passBrakeTests(long jobId, String key) {
        assertEquals("PASS", jobService.brakeTest(jobId, new BrakeTestRequest(
                key + "-bs", TestType.SENSITIVITY, 50, 500, true, null)).result());
        assertEquals("PASS", jobService.brakeTest(jobId, new BrakeTestRequest(
                key + "-bt", TestType.STABILITY, 140, 500, null, false)).result());
    }

    private EnRouteDispositionResponse report(String key, long jobId) {
        return enRouteService.reportAbnormality(jobId,
                new EnRouteAbnormalityRequest(key, "途中制动异常（合成数据）"));
    }

    private EnRouteTestResponse enRouteSens(String key, long jobId, long dispositionId, int expectedVersion) {
        return enRouteService.recordTest(jobId, dispositionId, new EnRouteTestRequest(
                key, expectedVersion, TestType.SENSITIVITY, 50, 500, true, null));
    }

    private EnRouteTestResponse enRouteStab(String key, long jobId, long dispositionId, int expectedVersion) {
        return enRouteService.recordTest(jobId, dispositionId, new EnRouteTestRequest(
                key, expectedVersion, TestType.STABILITY, 140, 500, null, false));
    }

    private EnRouteConfirmationResponse enRouteConfirm(String key, long jobId, long dispositionId,
                                                       int expectedVersion) {
        return enRouteService.confirm(jobId, dispositionId,
                new EnRouteConfirmRequest(key, expectedVersion, "张三", "李四"));
    }

    private EnRouteResumeResponse resume(String key, long jobId, long dispositionId, int expectedVersion) {
        return enRouteService.resume(jobId, dispositionId, new EnRouteResumeRequest(key, expectedVersion));
    }

    /** 完成途中感度/安定/双人确认，返回下一处置版本。 */
    private int fullHandling(String key, long jobId, long dispositionId, int expectedVersion) {
        assertEquals("PASS", enRouteSens(key + "-s", jobId, dispositionId, expectedVersion).result());
        assertEquals("PASS", enRouteStab(key + "-t", jobId, dispositionId, expectedVersion + 1).result());
        assertTrue(enRouteConfirm(key + "-f", jobId, dispositionId, expectedVersion + 2).valid());
        return expectedVersion + 3;
    }

    // ---------- 主流程 ----------

    @Test
    void 途中异常主流程续行后证据保留可再报新异常() {
        JobResponse job = departJob("m1", "ER1");
        EnRouteDispositionResponse d = report("m1-r", job.id());
        assertEquals("ACTIVE", d.status());
        assertEquals(1, d.version());
        assertEquals(1, d.consistVersion());
        assertEquals("C10", d.frozenTailCarNo());
        assertNull(d.frozenEotBindingId());
        assertNull(d.resumedAt());
        // 作业保持发车确认状态，不重开原始发结论
        assertEquals("DEPARTURE_CONFIRMED", jobService.getJob(job.id()).status());

        EnRouteTestResponse sens = enRouteSens("m1-s", job.id(), d.id(), 1);
        assertEquals("PASS", sens.result());
        assertEquals(2, sens.dispositionVersion());
        assertEquals(NOW.plus(Duration.ofHours(2)), sens.validUntil());
        assertEquals(3, enRouteStab("m1-t", job.id(), d.id(), 2).dispositionVersion());
        EnRouteConfirmationResponse conf = enRouteConfirm("m1-f", job.id(), d.id(), 3);
        assertTrue(conf.valid());
        assertEquals(4, conf.dispositionVersion());
        assertEquals("C10", conf.tailCarNo());

        EnRouteResumeResponse resumed = resume("m1-re", job.id(), d.id(), 4);
        assertTrue(resumed.resumed());
        assertEquals(5, resumed.version());
        // 续行成功仅结束本次阻断：作业仍发车确认状态
        assertEquals("DEPARTURE_CONFIRMED", jobService.getJob(job.id()).status());
        EnRouteDispositionEvidence evidence = enRouteService.getDispositionEvidence(job.id(), d.id());
        assertEquals("RESUMED", evidence.status());
        assertNotNull(evidence.resumedAt());
        assertEquals(2, evidence.tests().size());
        assertEquals(1, evidence.confirmations().size());
        // 始发证据保留：原制动试验未作废
        TestCurveResponse curve = jobService.getTestCurve(job.id());
        assertEquals(2, curve.tests().size());
        assertTrue(curve.tests().stream().noneMatch(TestCurveResponse.TestPoint::voided));
        // 之后可另报新异常
        EnRouteDispositionResponse d2 = report("m1-r2", job.id());
        assertEquals("ACTIVE", d2.status());
        assertEquals(1, d2.version());
        assertNotEquals(d.id(), d2.id());
    }

    @Test
    void 阻断中不得销记不得旧发车入口绕过不得解绑列尾续行后可销记释放() {
        JobResponse job = departJobWithEot("g1", "ER2", "EOT-ER1");
        EnRouteDispositionResponse d = report("g1-r", job.id());
        assertNotNull(d.frozenEotBindingId());
        // 阻断中不得销记释放列尾
        BusinessException close = assertThrows(BusinessException.class,
                () -> jobService.close(job.id(), new RequestIdOnly("g1-x")));
        assertTrue(close.getMessage().contains("阻断"));
        // 不得经旧发车入口绕过
        BusinessException departure = assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(job.id(), new RequestIdOnly("g1-d2")));
        assertTrue(departure.getMessage().contains("阻断"));
        // 阻断中不得提前解绑列尾
        assertThrows(BusinessException.class,
                () -> eotService.unbind(job.id(), new EotUnbindRequest("g1-u", 3)));
        // 装置仍被本作业占用
        assertEquals(job.id(), deviceRepository.findByDeviceNo("EOT-ER1").orElseThrow().boundJobId());
        // 完成处置续行
        int ev = fullHandling("g1-h", job.id(), d.id(), 1);
        assertTrue(resume("g1-re", job.id(), d.id(), ev).resumed());
        // 续行后销记成功，装置随销记释放
        assertTrue(jobService.close(job.id(), new RequestIdOnly("g1-x2")).closed());
        assertNull(deviceRepository.findByDeviceNo("EOT-ER1").orElseThrow().boundJobId());
    }

    // ---------- 旧曲线拒绝与失败分支 ----------

    @Test
    void 始发曲线不能冒充新试验阻断中旧试验入口拒绝() {
        JobResponse job = departJob("o1", "ER3");
        EnRouteDispositionResponse d = report("o1-r", job.id());
        // 阻断中旧制动试验入口拒绝（状态已非 CHECKED）
        assertThrows(BusinessException.class, () -> jobService.brakeTest(job.id(),
                new BrakeTestRequest("o1-old-bt", TestType.SENSITIVITY, 50, 500, true, null)));
        // 始发试验合格也不能直接续行：须新途中试验与双人确认
        BusinessException noConfirm = assertThrows(BusinessException.class,
                () -> resume("o1-re1", job.id(), d.id(), 1));
        assertTrue(noConfirm.getMessage().contains("双人确认"));
        // 仅感度试验不足以确认（须安定亦合格）
        enRouteSens("o1-s", job.id(), d.id(), 1);
        BusinessException noStability = assertThrows(BusinessException.class,
                () -> enRouteConfirm("o1-f", job.id(), d.id(), 2));
        assertTrue(noStability.getMessage().contains("STABILITY"));
        // 风压未恢复定压拒绝
        assertThrows(BusinessException.class, () -> enRouteService.recordTest(job.id(), d.id(),
                new EnRouteTestRequest("o1-t-low", 2, TestType.STABILITY, 140, 499, null, false)));
    }

    @Test
    void 途中试验复用始发数值规则() {
        JobResponse job = departJob("n1", "ER4");
        EnRouteDispositionResponse d = report("n1-r", job.id());
        // 感度减压量须 50kPa
        assertThrows(BusinessException.class, () -> enRouteService.recordTest(job.id(), d.id(),
                new EnRouteTestRequest("n1-s0", 1, TestType.SENSITIVITY, 60, 500, true, null)));
        // 感度未全列制动：不合格；安定须先感度合格
        assertEquals("FAIL", enRouteService.recordTest(job.id(), d.id(),
                new EnRouteTestRequest("n1-s1", 1, TestType.SENSITIVITY, 50, 500, false, null)).result());
        BusinessException notYet = assertThrows(BusinessException.class, () -> enRouteService.recordTest(
                job.id(), d.id(), new EnRouteTestRequest("n1-t0", 2, TestType.STABILITY, 140, 500, null, false)));
        assertTrue(notYet.getMessage().contains("SENSITIVITY"));
        // 重做感度合格；安定恰 140kPa 无紧急制动合格
        assertEquals("PASS", enRouteSens("n1-s2", job.id(), d.id(), 2).result());
        assertEquals("PASS", enRouteStab("n1-t1", job.id(), d.id(), 3).result());
        // 安定发生紧急制动：不合格
        JobResponse job2 = departJob("n2", "ER5");
        EnRouteDispositionResponse d2 = report("n2-r", job2.id());
        enRouteSens("n2-s", job2.id(), d2.id(), 1);
        assertEquals("FAIL", enRouteService.recordTest(job2.id(), d2.id(),
                new EnRouteTestRequest("n2-t", 2, TestType.STABILITY, 140, 500, null, true)).result());
    }

    @Test
    void 途中确认须两名不同人员() {
        JobResponse job = departJob("p1", "ER6");
        EnRouteDispositionResponse d = report("p1-r", job.id());
        enRouteSens("p1-s", job.id(), d.id(), 1);
        enRouteStab("p1-t", job.id(), d.id(), 2);
        assertThrows(BusinessException.class, () -> enRouteService.confirm(job.id(), d.id(),
                new EnRouteConfirmRequest("p1-f", 3, "张三", "张三")));
        // 失败不占键不占版本：两名不同人员可确认成功
        assertTrue(enRouteConfirm("p1-f2", job.id(), d.id(), 3).valid());
    }

    // ---------- 活动处置唯一与引用边界 ----------

    @Test
    void 同作业仅一条活动处置() {
        JobResponse job = departJob("x1", "ER7");
        report("x1-r", job.id());
        ConflictException e = assertThrows(ConflictException.class, () -> report("x1-r2", job.id()));
        assertTrue(e.getMessage().contains("一条活动处置"));
    }

    @Test
    void 未知作业未发车已销记与跨作业引用拒绝() {
        // 未知作业：404
        assertThrows(NotFoundException.class, () -> report("u1-r", 999999));
        // 未发车确认：422
        JobResponse accepted = jobService.accept(new AcceptJobRequest("u1-a", "Tu1", "ER8", cars(10)));
        assertThrows(BusinessException.class, () -> report("u1-r2", accepted.id()));
        // 已销记：422
        JobResponse departed = departJob("u2", "ER9");
        jobService.close(departed.id(), new RequestIdOnly("u2-x"));
        BusinessException closed = assertThrows(BusinessException.class, () -> report("u2-r", departed.id()));
        assertTrue(closed.getMessage().contains("销记"));
        // 跨作业引用：404，且不影响各自处置
        JobResponse jobA = departJob("u3", "ER10");
        JobResponse jobB = departJob("u4", "ER11");
        EnRouteDispositionResponse dA = report("u3-r", jobA.id());
        EnRouteDispositionResponse dB = report("u4-r", jobB.id());
        assertThrows(NotFoundException.class, () -> enRouteSens("u3-s", jobA.id(), dB.id(), 1));
        assertThrows(NotFoundException.class, () -> resume("u3-re", jobA.id(), dB.id(), 1));
        assertThrows(NotFoundException.class, () -> enRouteService.getDispositionEvidence(jobA.id(), dB.id()));
        assertEquals("ACTIVE", enRouteService.getDispositionEvidence(jobA.id(), dA.id()).status());
        assertEquals("ACTIVE", enRouteService.getDispositionEvidence(jobB.id(), dB.id()).status());
    }

    // ---------- 途中编组变更 ----------

    @Test
    void 途中编组变更使待批准试验与确认失效须重做后方可续行() {
        JobResponse job = departJob("c1", "ER12");
        EnRouteDispositionResponse d = report("c1-r", job.id());
        int ev = fullHandling("c1-h", job.id(), d.id(), 1);
        assertEquals(4, ev);
        // 阻断中允许摘挂：挂一辆新车
        ConsistChangeResponse change = jobService.changeConsist(job.id(), new ConsistChangeRequest(
                "c1-g", List.of(new CarDto("C11", new BigDecimal("80.0"), new BigDecimal("1.3"), false)),
                List.of()));
        assertEquals(2, change.consistVersion());
        assertEquals(11, change.carCount());
        assertEquals(2, change.voidedTests());
        // 作业保持发车确认状态，原发车证据（始发试验）未作废
        assertEquals("DEPARTURE_CONFIRMED", jobService.getJob(job.id()).status());
        assertTrue(jobService.getTestCurve(job.id()).tests().stream()
                .noneMatch(TestCurveResponse.TestPoint::voided));
        // 处置版本已递增，旧版本 409
        assertThrows(ConflictException.class, () -> resume("c1-re1", job.id(), d.id(), 4));
        // 待批准途中试验与确认已失效
        EnRouteDispositionEvidence evidence = enRouteService.getDispositionEvidence(job.id(), d.id());
        assertEquals(5, evidence.version());
        assertTrue(evidence.tests().stream().allMatch(EnRouteDispositionEvidence.TestEvidence::voided));
        assertTrue(evidence.confirmations().stream()
                .noneMatch(EnRouteDispositionEvidence.ConfirmationEvidence::valid));
        BusinessException notReady = assertThrows(BusinessException.class,
                () -> resume("c1-re2", job.id(), d.id(), 5));
        assertTrue(notReady.getMessage().contains("双人确认"));
        // 重做途中试验与确认（新编组版本）后方可续行
        int ev2 = fullHandling("c1-h2", job.id(), d.id(), 5);
        assertTrue(resume("c1-re3", job.id(), d.id(), ev2).resumed());
        EnRouteDispositionEvidence after = enRouteService.getDispositionEvidence(job.id(), d.id());
        assertEquals(4, after.tests().size());
        assertEquals(2, after.tests().stream()
                .filter(t -> !t.voided() && t.consistVersion() == 2).count());
    }

    @Test
    void 发车后未登记异常不得摘挂变更() {
        JobResponse job = departJob("c2", "ER13");
        assertThrows(BusinessException.class, () -> jobService.changeConsist(job.id(),
                new ConsistChangeRequest("c2-g", List.of(), List.of("C1"))));
    }

    // ---------- 试验失效与自检有效期边界 ----------

    @Test
    void 途中试验恰到失效点拒绝续行提前片刻允许() {
        JobResponse job = departJob("e1", "ER14");
        EnRouteDispositionResponse d = report("e1-r", job.id());
        int ev = fullHandling("e1-h", job.id(), d.id(), 1);
        // 恰到原试验失效点：拒绝
        Mockito.when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(2)));
        BusinessException atExpiry = assertThrows(BusinessException.class,
                () -> resume("e1-re1", job.id(), d.id(), ev));
        assertTrue(atExpiry.getMessage().contains("有效截止"));
        // 过期后：拒绝
        Mockito.when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(2)).plusSeconds(1));
        assertThrows(BusinessException.class, () -> resume("e1-re2", job.id(), d.id(), ev));
        // 失败仍停车、版本不前进：失效前一刻同一期望版本可续行成功
        Mockito.when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(2)).minusSeconds(1));
        assertTrue(resume("e1-re3", job.id(), d.id(), ev).resumed());
    }

    @Test
    void 续行同事务重查列尾自检有效期恰达拒绝且不释放装置() {
        JobResponse job = departJobWithEot("e2", "ER15", "EOT-ER2");
        EnRouteDispositionResponse d = report("e2-r", job.id());
        // 90 分钟后做途中试验：有效至 NOW+3.5h，晚于装置自检截止 NOW+3h
        Mockito.when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(90)));
        int ev = fullHandling("e2-h", job.id(), d.id(), 1);
        // 恰达装置自检截止：拒绝续行，仍停车且不释放装置
        Mockito.when(clock.instant()).thenReturn(DEVICE_VALID_UNTIL);
        BusinessException atDeadline = assertThrows(BusinessException.class,
                () -> resume("e2-re1", job.id(), d.id(), ev));
        assertTrue(atDeadline.getMessage().contains("自检"));
        assertEquals(job.id(), deviceRepository.findByDeviceNo("EOT-ER2").orElseThrow().boundJobId());
        assertEquals("ACTIVE", enRouteService.getDispositionEvidence(job.id(), d.id()).status());
        // 截止前一刻：允许续行
        Mockito.when(clock.instant()).thenReturn(DEVICE_VALID_UNTIL.minusSeconds(1));
        assertTrue(resume("e2-re2", job.id(), d.id(), ev).resumed());
    }

    // ---------- 处置版本与幂等 ----------

    @Test
    void 处置版本自1起修改用期望版本成功加1旧版409() {
        JobResponse job = departJob("v1", "ER16");
        EnRouteDispositionResponse d = report("v1-r", job.id());
        assertEquals(1, d.version());
        // 错版 409
        assertThrows(ConflictException.class, () -> enRouteSens("v1-s0", job.id(), d.id(), 9));
        // 成功加 1
        assertEquals(2, enRouteSens("v1-s1", job.id(), d.id(), 1).dispositionVersion());
        // 旧版 409
        assertThrows(ConflictException.class, () -> enRouteStab("v1-t0", job.id(), d.id(), 1));
        assertEquals(3, enRouteStab("v1-t1", job.id(), d.id(), 2).dispositionVersion());
        assertEquals(4, enRouteConfirm("v1-f", job.id(), d.id(), 3).dispositionVersion());
        assertThrows(ConflictException.class, () -> resume("v1-re0", job.id(), d.id(), 3));
        assertEquals(5, resume("v1-re1", job.id(), d.id(), 4).version());
        // 已结束的处置单不得继续操作
        assertThrows(BusinessException.class, () -> enRouteSens("v1-s2", job.id(), d.id(), 5));
    }

    @Test
    void 幂等同键同参重放异参409失败不占键() {
        JobResponse job = departJob("i1", "ER17");
        EnRouteAbnormalityRequest request = new EnRouteAbnormalityRequest("i1-r", "途中制动异常");
        EnRouteDispositionResponse first = enRouteService.reportAbnormality(job.id(), request);
        EnRouteDispositionResponse replay = enRouteService.reportAbnormality(job.id(), request);
        assertEquals(first.id(), replay.id());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM en_route_disposition", Integer.class));
        // 同键异参：409 且不改变状态
        assertThrows(ConflictException.class, () -> enRouteService.reportAbnormality(job.id(),
                new EnRouteAbnormalityRequest("i1-r", "其他异常")));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM en_route_disposition", Integer.class));
        // 失败不占键：错误减压量失败后同键修正参数可成功
        assertThrows(BusinessException.class, () -> enRouteService.recordTest(job.id(), first.id(),
                new EnRouteTestRequest("i1-s", 1, TestType.SENSITIVITY, 70, 500, true, null)));
        EnRouteTestResponse fixed = enRouteService.recordTest(job.id(), first.id(),
                new EnRouteTestRequest("i1-s", 1, TestType.SENSITIVITY, 50, 500, true, null));
        assertEquals("PASS", fixed.result());
    }

    // ---------- 并发 ----------

    @Test
    void 并发登记途中异常同作业唯一成功() throws Exception {
        JobResponse job = departJob("cc1", "ER18");
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
                    report("cc1-r" + idx, job.id());
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
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM en_route_disposition WHERE job_id = " + job.id(), Integer.class));
    }

    @Test
    void 续行与销记竞争不得尚阻断便释放列尾或已销记又续行() throws Exception {
        JobResponse job = departJob("r1", "ER19");
        EnRouteDispositionResponse d = report("r1-r", job.id());
        int ev = fullHandling("r1-h", job.id(), d.id(), 1);
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger resumeOk = new AtomicInteger();
        AtomicInteger resumeRejected = new AtomicInteger();
        AtomicReference<Object> closeOutcome = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    resume("r1-re" + idx, job.id(), d.id(), ev);
                    resumeOk.incrementAndGet();
                } catch (BusinessException | ConflictException e) {
                    resumeRejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        futures.add(pool.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                closeOutcome.set(jobService.close(job.id(), new RequestIdOnly("r1-x")));
            } catch (BusinessException e) {
                closeOutcome.set(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // 并发续行唯一成功
        assertEquals(1, resumeOk.get());
        assertEquals(1, resumeRejected.get());
        // 终态一致：处置单已结束；不得出现已销记但处置仍阻断
        DispositionRow disposition = dispositionRepository.findById(d.id()).orElseThrow();
        assertEquals(DispositionStatus.RESUMED, disposition.status());
        String jobStatus = jobService.getJob(job.id()).status();
        if (closeOutcome.get() instanceof BusinessException closeRejected) {
            // 销记先于续行提交时被阻断拒绝
            assertTrue(closeRejected.getMessage().contains("阻断"));
            assertEquals("DEPARTURE_CONFIRMED", jobStatus);
            // 阻断结束后可销记
            assertTrue(jobService.close(job.id(), new RequestIdOnly("r1-x2")).closed());
        } else {
            // 销记在续行结束之后提交：合法终态
            assertEquals("CLOSED", jobStatus);
        }
        // 已销记不得再续行
        assertThrows(BusinessException.class,
                () -> resume("r1-re9", job.id(), d.id(), ev + 1));
    }
}
