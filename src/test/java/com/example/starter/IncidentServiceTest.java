package com.example.starter.service;

import com.example.starter.domain.TestType;
import com.example.starter.repo.EotDeviceRepository;
import com.example.starter.repo.IncidentRepository;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.CarDto;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.ConsistCheckResponse;
import com.example.starter.web.dto.EnRouteConfirmRequest;
import com.example.starter.web.dto.EnRouteTestRequest;
import com.example.starter.web.dto.EnRouteTestResponse;
import com.example.starter.web.dto.EotBindRequest;
import com.example.starter.web.dto.EotConfirmRequest;
import com.example.starter.web.dto.EotDeviceRegisterRequest;
import com.example.starter.web.dto.IncidentEvidence;
import com.example.starter.web.dto.IncidentReportRequest;
import com.example.starter.web.dto.IncidentResponse;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.web.dto.ResumeRequest;
import com.example.starter.web.dto.ResumeResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 途中异常停车与续行许可测试：UTC 可控时钟、H2 真实事务/唯一约束，
 * 覆盖主流程、旧曲线拒绝、编组变更、试验失效边界、续行竞争与幂等。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_incident_demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Import(IncidentServiceTest.TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentServiceTest {

    static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    static final Instant DEVICE_VALID_UNTIL = NOW.plusSeconds(24 * 3600);

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Mockito.mock(Clock.class);
        }
    }

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private TrainJobService jobService;

    @Autowired
    private EotService eotService;

    @Autowired
    private IncidentRepository incidentRepository;

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
        jdbc.update("DELETE FROM incident");
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

    private List<CarDto> cars(int total, int closed) {
        List<CarDto> cars = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            cars.add(new CarDto("C" + i, new BigDecimal("80.0"), new BigDecimal("1.3"), i <= closed));
        }
        return cars;
    }

    /** 接入并通过运统1核对。 */
    private JobResponse acceptAndCheck(String key, String track, int total, int closed) {
        JobResponse job = jobService.accept(new AcceptJobRequest(key + "-a", "T" + key, track,
                cars(total, closed)));
        ConsistCheckResponse check = jobService.checkConsist(job.id(), new ConsistCheckRequest(
                key + "-c", total, new BigDecimal("1.3").multiply(BigDecimal.valueOf(total)),
                new BigDecimal("80.0").multiply(BigDecimal.valueOf(total))));
        assertTrue(check.matched());
        return job;
    }

    /** 完成始发感度/安定试验。 */
    private void passOriginTests(long jobId, String key) {
        jobService.brakeTest(jobId, new BrakeTestRequest(key + "-s", TestType.SENSITIVITY,
                50, 500, true, null));
        jobService.brakeTest(jobId, new BrakeTestRequest(key + "-t", TestType.STABILITY,
                140, 500, null, false));
    }

    /** 无列尾作业：接入→核对→试验→发车。 */
    private long departedJob(String key, String track) {
        JobResponse job = acceptAndCheck(key, track, 10, 0);
        passOriginTests(job.id(), key);
        jobService.confirmDeparture(job.id(), new RequestIdOnly(key + "-d"));
        return job.id();
    }

    /** 列尾受控作业：建档→绑定→双人确认→试验→发车；装置自检有效至 NOW+24h。 */
    private long departedJobWithEot(String key, String track, String deviceNo) {
        return departedJobWithEot(key, track, deviceNo, DEVICE_VALID_UNTIL);
    }

    /** 列尾受控作业（可指定装置自检有效截止）。 */
    private long departedJobWithEot(String key, String track, String deviceNo, Instant validUntil) {
        JobResponse job = acceptAndCheck(key, track, 10, 0);
        eotService.register(new EotDeviceRegisterRequest(key + "-dr", deviceNo, validUntil));
        eotService.bind(job.id(), new EotBindRequest(key + "-b", deviceNo, 1));
        eotService.confirm(job.id(), new EotConfirmRequest(key + "-cf", 2, "C10", 1, "张三", "李四"));
        passOriginTests(job.id(), key);
        jobService.confirmDeparture(job.id(), new RequestIdOnly(key + "-d"));
        return job.id();
    }

    private IncidentResponse report(long jobId, String key) {
        return incidentService.report(jobId, new IncidentReportRequest(key + "-i", "制动异常"));
    }

    private EnRouteTestResponse enRouteSensitivity(long jobId, long incidentId, String key, int version) {
        return incidentService.enRouteTest(jobId, incidentId, new EnRouteTestRequest(
                key + "-es", version, TestType.SENSITIVITY, 50, 500, true, null));
    }

    private EnRouteTestResponse enRouteStability(long jobId, long incidentId, String key, int version) {
        return incidentService.enRouteTest(jobId, incidentId, new EnRouteTestRequest(
                key + "-et", version, TestType.STABILITY, 140, 500, null, false));
    }

    @Test
    void 登记异常后阻断期间销记与旧发车入口拒绝且列尾继续占用() {
        long jobId = departedJobWithEot("i1", "G1", "D-I1");
        IncidentResponse incident = report(jobId, "i1");
        assertEquals("OPEN", incident.status());
        assertEquals(1, incident.version());
        assertEquals(1, incident.consistVersion());
        assertNotNull(incident.eotBindingId());

        // 阻断中：销记拒绝（不得释放列尾）
        BusinessException closeErr = assertThrows(BusinessException.class,
                () -> jobService.close(jobId, new RequestIdOnly("i1-x")));
        assertTrue(closeErr.getMessage().contains("阻断"));
        // 阻断中：旧发车入口不得绕过
        assertThrows(BusinessException.class,
                () -> jobService.confirmDeparture(jobId, new RequestIdOnly("i1-d2")));
        // 阻断中：列尾装置继续占用
        assertEquals(jobId, deviceRepository.findByDeviceNo("D-I1").orElseThrow().boundJobId());
        // 证据可查：处置单 OPEN
        List<IncidentEvidence> evidence = incidentService.getEvidence(jobId);
        assertEquals(1, evidence.size());
        assertEquals("OPEN", evidence.get(0).status());
    }

    @Test
    void 未发车已销记或未知作业登记拒绝() {
        JobResponse job = acceptAndCheck("i2", "G2", 10, 0);
        // 未发车：拒绝
        assertThrows(BusinessException.class, () -> report(job.id(), "i2"));
        // 未知作业：404
        assertThrows(NotFoundException.class, () -> report(999999, "i2"));
        // 已销记：拒绝
        long departed = departedJob("i2b", "G3");
        jobService.close(departed, new RequestIdOnly("i2b-x"));
        assertThrows(BusinessException.class, () -> report(departed, "i2b"));
    }

    @Test
    void 同作业仅一条活动处置同键重放异参409() {
        long jobId = departedJob("i3", "G4");
        IncidentResponse first = report(jobId, "i3");
        // 同键同参：重放首次响应
        IncidentResponse replay = incidentService.report(jobId,
                new IncidentReportRequest("i3-i", "制动异常"));
        assertEquals(first.id(), replay.id());
        // 同键异参：409
        assertThrows(ConflictException.class,
                () -> incidentService.report(jobId, new IncidentReportRequest("i3-i", "其他原因")));
        // 第二条活动处置（新键）：409
        assertThrows(ConflictException.class,
                () -> incidentService.report(jobId, new IncidentReportRequest("i3-i2", "再次异常")));
        assertEquals(1, incidentRepository.findAllByJobId(jobId).size());
    }

    @Test
    void 始发曲线不能冒充途中试验直接续行拒绝() {
        long jobId = departedJob("i4", "G5");
        IncidentResponse incident = report(jobId, "i4");
        // 始发感度/安定均合格，但未做途中试验：续行拒绝
        BusinessException e = assertThrows(BusinessException.class,
                () -> incidentService.resume(jobId, incident.id(), new ResumeRequest("i4-r", 1)));
        assertTrue(e.getMessage().contains("途中"));
        // 仍阻断中
        assertEquals("OPEN", incidentRepository.findById(incident.id()).orElseThrow().status().name());
    }

    @Test
    void 途中试验复用始发数值规则() {
        long jobId = departedJob("i5", "G6");
        IncidentResponse incident = report(jobId, "i5");
        // 风压未恢复定压：拒绝
        assertThrows(BusinessException.class, () -> incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i5-e1", 1, TestType.SENSITIVITY, 50, 499, true, null)));
        // 感度减压量非 50：拒绝
        assertThrows(BusinessException.class, () -> incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i5-e2", 1, TestType.SENSITIVITY, 70, 500, true, null)));
        // 安定前须途中感度合格
        assertThrows(BusinessException.class, () -> incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i5-e3", 1, TestType.STABILITY, 140, 500, null, false)));
        // 感度不合格记录后须重做合格
        EnRouteTestResponse fail = incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i5-e4", 1, TestType.SENSITIVITY, 50, 500, false, null));
        assertEquals("FAIL", fail.result());
        assertEquals(2, fail.incidentVersion());
        assertThrows(BusinessException.class, () -> incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i5-e5", 2, TestType.STABILITY, 140, 500, null, false)));
        EnRouteTestResponse pass = enRouteSensitivity(jobId, incident.id(), "i5", 2);
        assertEquals("PASS", pass.result());
        assertEquals(3, pass.incidentVersion());
    }

    @Test
    void 途中试验与双人确认后续行成功证据保留可再报新异常() {
        long jobId = departedJobWithEot("i6", "G7", "D-I6");
        IncidentResponse incident = report(jobId, "i6");
        enRouteSensitivity(jobId, incident.id(), "i6", 1);
        enRouteStability(jobId, incident.id(), "i6", 2);
        incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i6-ec", 3, "C10", 1, "王五", "赵六"));
        ResumeResponse resume = incidentService.resume(jobId, incident.id(),
                new ResumeRequest("i6-r", 4));
        assertTrue(resume.resumed());
        assertEquals(5, resume.incidentVersion());

        // 仅结束本次阻断：作业仍发车确认态，列尾继续占用
        assertEquals("DEPARTURE_CONFIRMED", jobService.getJob(jobId).status());
        assertEquals(jobId, deviceRepository.findByDeviceNo("D-I6").orElseThrow().boundJobId());
        // 始发与途中两条证据均保留
        List<IncidentEvidence> evidence = incidentService.getEvidence(jobId);
        assertEquals(1, evidence.size());
        assertEquals("RESUMED", evidence.get(0).status());
        assertEquals(2, evidence.get(0).tests().size());
        assertEquals(1, evidence.get(0).confirmations().size());
        assertEquals(2, jobService.getTestCurve(jobId).tests().stream()
                .filter(t -> !t.voided()).count());
        // 可另报新异常
        IncidentResponse second = report(jobId, "i6n");
        assertEquals(1, second.version());
        assertEquals(2, incidentRepository.findAllByJobId(jobId).size());
    }

    @Test
    void 双人确认须两名不同人员且匹配最新编组与试验() {
        long jobId = departedJobWithEot("i7", "G8", "D-I7");
        IncidentResponse incident = report(jobId, "i7");
        // 未完成途中试验即确认：拒绝
        assertThrows(BusinessException.class, () -> incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i7-e0", 1, "C10", 1, "王五", "赵六")));
        enRouteSensitivity(jobId, incident.id(), "i7", 1);
        enRouteStability(jobId, incident.id(), "i7", 2);
        // 同一人员：拒绝
        assertThrows(BusinessException.class, () -> incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i7-e1", 3, "C10", 1, "王五", "王五")));
        // 编组版本不符：拒绝
        assertThrows(BusinessException.class, () -> incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i7-e2", 3, "C10", 2, "王五", "赵六")));
        // 尾车不符：拒绝
        assertThrows(BusinessException.class, () -> incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i7-e3", 3, "C9", 1, "王五", "赵六")));
        // 未确认直接续行：拒绝（列尾一致性未共同确认）
        assertThrows(BusinessException.class,
                () -> incidentService.resume(jobId, incident.id(), new ResumeRequest("i7-r", 3)));
        var confirmed = incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i7-e4", 3, "C10", 1, "王五", "赵六"));
        assertTrue(confirmed.valid());
        assertEquals(4, confirmed.incidentVersion());
    }

    @Test
    void 阻断中编组变更使途中试验与确认失效重做后才能续行() {
        long jobId = departedJobWithEot("i8", "G9", "D-I8");
        IncidentResponse incident = report(jobId, "i8");
        enRouteSensitivity(jobId, incident.id(), "i8", 1);
        enRouteStability(jobId, incident.id(), "i8", 2);
        incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i8-ec", 3, "C10", 1, "王五", "赵六"));

        // 阻断中摘挂：允许；挂新车 C11 成为新尾车
        var change = jobService.changeConsist(jobId, new ConsistChangeRequest("i8-g",
                List.of(new CarDto("C11", new BigDecimal("80.0"), new BigDecimal("1.3"), false)),
                List.of()));
        assertEquals(2, change.consistVersion());
        // 原发车试验证据冻结不作废，作业仍发车确认态
        assertEquals(0, change.voidedTests());
        assertEquals("DEPARTURE_CONFIRMED", jobService.getJob(jobId).status());
        assertEquals(2, jobService.getTestCurve(jobId).tests().stream()
                .filter(t -> !t.voided()).count());
        // 待批准途中试验与确认失效，处置版本 +1
        IncidentEvidence evidence = incidentService.getEvidence(jobId).get(0);
        assertEquals(5, evidence.version());
        assertTrue(evidence.tests().stream().allMatch(IncidentEvidence.TestPoint::voided));
        assertFalse(evidence.confirmations().get(0).valid());
        // 旧版本续行：409；当前版本但未重做试验：422
        assertThrows(ConflictException.class,
                () -> incidentService.resume(jobId, incident.id(), new ResumeRequest("i8-r0", 4)));
        assertThrows(BusinessException.class,
                () -> incidentService.resume(jobId, incident.id(), new ResumeRequest("i8-r1", 5)));
        // 重做试验与双人确认（新尾车 C11、新版本 2）后续行成功
        enRouteSensitivity(jobId, incident.id(), "i8n", 5);
        enRouteStability(jobId, incident.id(), "i8n", 6);
        incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i8-ec2", 7, "C11", 2, "王五", "赵六"));
        ResumeResponse resume = incidentService.resume(jobId, incident.id(),
                new ResumeRequest("i8-r2", 8));
        assertTrue(resume.resumed());
        // 续行后（无活动处置）摘挂拒绝
        assertThrows(BusinessException.class,
                () -> jobService.changeConsist(jobId, new ConsistChangeRequest("i8-g2",
                        List.of(), List.of("C1"))));
    }

    @Test
    void 处置版本旧版409成功加1() {
        long jobId = departedJob("i9", "G10");
        IncidentResponse incident = report(jobId, "i9");
        // 旧版/错版：409
        assertThrows(ConflictException.class, () -> incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i9-e1", 2, TestType.SENSITIVITY, 50, 500, true, null)));
        // 正确版本：成功且版本 +1
        EnRouteTestResponse ok = enRouteSensitivity(jobId, incident.id(), "i9", 1);
        assertEquals(2, ok.incidentVersion());
        // 失败不占键：同键修正参数后成功
        assertThrows(BusinessException.class, () -> incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i9-e2", 2, TestType.STABILITY, 141, 500, null, false)));
        EnRouteTestResponse retried = incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i9-e2", 2, TestType.STABILITY, 140, 500, null, false));
        assertEquals("PASS", retried.result());
        // 同键同参重放
        EnRouteTestResponse replay = incidentService.enRouteTest(jobId, incident.id(),
                new EnRouteTestRequest("i9-e2", 2, TestType.STABILITY, 140, 500, null, false));
        assertEquals(retried.id(), replay.id());
    }

    @Test
    void 试验失效边界恰到失效点拒绝之前一刻允许() {
        long jobId = departedJobWithEot("i10", "G11", "D-I10");
        IncidentResponse incident = report(jobId, "i10");
        enRouteSensitivity(jobId, incident.id(), "i10", 1);
        EnRouteTestResponse stability = enRouteStability(jobId, incident.id(), "i10", 2);
        incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i10-ec", 3, "C10", 1, "王五", "赵六"));
        Instant validUntil = stability.validUntil();
        assertEquals(NOW.plus(IncidentService.EN_ROUTE_TEST_VALID_DURATION), validUntil);
        // 恰到失效点：拒绝，仍停车
        Mockito.when(clock.instant()).thenReturn(validUntil);
        assertThrows(BusinessException.class,
                () -> incidentService.resume(jobId, incident.id(), new ResumeRequest("i10-r", 4)));
        assertEquals("OPEN", incidentRepository.findById(incident.id()).orElseThrow().status().name());
        // 失效前一刻：允许
        Mockito.when(clock.instant()).thenReturn(validUntil.minusMillis(1));
        ResumeResponse resume = incidentService.resume(jobId, incident.id(),
                new ResumeRequest("i10-r2", 4));
        assertTrue(resume.resumed());
    }

    @Test
    void 列尾自检过期续行拒绝装置不释放() {
        // 装置自检仅有效至 NOW+1h；途中试验有效期 6h，可隔离自检过期分支
        long jobId = departedJobWithEot("i11", "G12", "D-I11", NOW.plusSeconds(3600));
        IncidentResponse incident = report(jobId, "i11");
        enRouteSensitivity(jobId, incident.id(), "i11", 1);
        enRouteStability(jobId, incident.id(), "i11", 2);
        incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i11-ec", 3, "C10", 1, "王五", "赵六"));
        // 推进时钟超过装置自检有效截止（途中试验仍在 6h 有效期内）
        Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(3601));
        BusinessException e = assertThrows(BusinessException.class,
                () -> incidentService.resume(jobId, incident.id(), new ResumeRequest("i11-r", 4)));
        assertTrue(e.getMessage().contains("自检"));
        // 装置不释放，仍阻断
        assertEquals(jobId, deviceRepository.findByDeviceNo("D-I11").orElseThrow().boundJobId());
        assertEquals("OPEN", incidentRepository.findById(incident.id()).orElseThrow().status().name());
    }

    @Test
    void 关门车比例超限续行拒绝() {
        // 50 辆 3 辆关门车 = 恰 6% 发车
        JobResponse job = acceptAndCheck("i12", "G13", 50, 3);
        passOriginTests(job.id(), "i12");
        jobService.confirmDeparture(job.id(), new RequestIdOnly("i12-d"));
        IncidentResponse incident = report(job.id(), "i12");
        // 阻断中摘非关门车使比例超限：变更本身即被拒（恰 6% 规则阻断中同样生效）
        assertThrows(BusinessException.class, () -> jobService.changeConsist(job.id(),
                new ConsistChangeRequest("i12-g", List.of(), List.of("C50"))));
        enRouteSensitivity(job.id(), incident.id(), "i12", 1);
        enRouteStability(job.id(), incident.id(), "i12", 2);
        // 途中关门车状态变化（合成数据直改）：4/50 = 8% > 6%，续行同事务重查拒绝
        jdbc.update("UPDATE train_car SET valve_closed = TRUE WHERE job_id = ? AND car_no = 'C4'",
                job.id());
        BusinessException e = assertThrows(BusinessException.class,
                () -> incidentService.resume(job.id(), incident.id(), new ResumeRequest("i12-r", 3)));
        assertTrue(e.getMessage().contains("关门车"));
    }

    @Test
    void 跨作业引用处置单拒绝() {
        long jobA = departedJob("i13a", "G14");
        long jobB = departedJob("i13b", "G15");
        IncidentResponse incidentA = report(jobA, "i13a");
        // 跨作业引用：拒绝且不改变原处置状态
        assertThrows(BusinessException.class, () -> enRouteSensitivity(jobB, incidentA.id(), "i13x", 1));
        assertThrows(BusinessException.class,
                () -> incidentService.resume(jobB, incidentA.id(), new ResumeRequest("i13x-r", 1)));
        assertEquals("OPEN", incidentRepository.findById(incidentA.id()).orElseThrow().status().name());
        // 未知处置单：404
        assertThrows(NotFoundException.class,
                () -> incidentService.resume(jobB, 999999, new ResumeRequest("i13x-r2", 1)));
    }

    @Test
    void 续行竞争唯一成功装置不释放() throws Exception {
        long jobId = departedJobWithEot("i14", "G16", "D-I14");
        IncidentResponse incident = report(jobId, "i14");
        enRouteSensitivity(jobId, incident.id(), "i14", 1);
        enRouteStability(jobId, incident.id(), "i14", 2);
        incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i14-ec", 3, "C10", 1, "王五", "赵六"));

        int threads = 2;
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
                    incidentService.resume(jobId, incident.id(),
                            new ResumeRequest("i14-r" + idx, 4));
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
        assertEquals(1, rejected.get());
        // 最终状态：处置已续行一次，作业仍发车确认态，装置继续占用
        var row = incidentRepository.findById(incident.id()).orElseThrow();
        assertEquals("RESUMED", row.status().name());
        assertEquals(5, row.version());
        assertEquals("DEPARTURE_CONFIRMED", jobService.getJob(jobId).status());
        assertEquals(jobId, deviceRepository.findByDeviceNo("D-I14").orElseThrow().boundJobId());
    }

    @Test
    void 异常补录与销记竞争不致已销记又阻断或阻断中释放列尾() throws Exception {
        long jobId = departedJobWithEot("i15", "G17", "D-I15");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reportOk = new AtomicInteger();
        AtomicInteger closeOk = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                incidentService.report(jobId, new IncidentReportRequest("i15-i", "制动异常"));
                reportOk.incrementAndGet();
            } catch (ApiException e) {
                // 销记先提交则补录被拒
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
        futures.add(pool.submit(() -> {
            ready.countDown();
            try {
                start.await(5, TimeUnit.SECONDS);
                jobService.close(jobId, new RequestIdOnly("i15-x"));
                closeOk.incrementAndGet();
            } catch (ApiException e) {
                // 阻断先登记则销记被拒
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
        // 恰一个成功，且最终状态一致：不得既销记又留活动处置，不得阻断中释放列尾
        assertEquals(1, reportOk.get() + closeOk.get());
        boolean hasActiveIncident = incidentService.hasActiveIncident(jobId);
        String status = jobService.getJob(jobId).status();
        Long boundJob = deviceRepository.findByDeviceNo("D-I15").orElseThrow().boundJobId();
        if (reportOk.get() == 1) {
            assertTrue(hasActiveIncident);
            assertEquals("DEPARTURE_CONFIRMED", status);
            assertEquals(jobId, boundJob);
        } else {
            assertFalse(hasActiveIncident);
            assertEquals("CLOSED", status);
            assertNull(boundJob);
        }
    }

    @Test
    void 续行后可正常销记释放列尾() {
        long jobId = departedJobWithEot("i16", "G18", "D-I16");
        IncidentResponse incident = report(jobId, "i16");
        enRouteSensitivity(jobId, incident.id(), "i16", 1);
        enRouteStability(jobId, incident.id(), "i16", 2);
        incidentService.confirmEnRoute(jobId, incident.id(),
                new EnRouteConfirmRequest("i16-ec", 3, "C10", 1, "王五", "赵六"));
        incidentService.resume(jobId, incident.id(), new ResumeRequest("i16-r", 4));
        var closed = jobService.close(jobId, new RequestIdOnly("i16-x"));
        assertTrue(closed.closed());
        assertNull(deviceRepository.findByDeviceNo("D-I16").orElseThrow().boundJobId());
        // 销记后不得再登记异常
        assertThrows(BusinessException.class, () -> report(jobId, "i16n"));
    }
}
