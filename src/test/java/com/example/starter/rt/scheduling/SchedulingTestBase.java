package com.example.starter.rt.scheduling;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.example.starter.rt.AbstractIntegrationTest;
import com.example.starter.rt.dto.DeviceRegisterRequest;
import com.example.starter.rt.dto.RecordedResponse;
import com.example.starter.rt.service.SchedulingService;
import com.example.starter.rt.service.WorkflowService;
import com.example.starter.rt.web.ClockConfig;

/**
 * 排程门禁 H2 测试基类：注入可控时钟（Asia/Shanghai），提供合成患者/模板/设备与
 * “已审核→已批准→首次验证合格”当前计划的标准准备流程。
 */
@Import(SchedulingTestBase.MutableClockConfig.class)
abstract class SchedulingTestBase extends AbstractIntegrationTest {

    static final String PATIENT = "PS";
    static final String PLAN = "PLAN_S";
    static final String TPL = "TPLS";
    static final String DEVICE = "DEV-1";

    @Autowired
    protected WorkflowService workflow;

    @Autowired
    protected SchedulingService scheduling;

    @Autowired
    protected MutableClock mutableClock;

    /** 患者 + 盆腔模板 + 200cGy×25 勾稽一致且恰等限值的计划。 */
    protected void baseData() {
        patient(PATIENT, "排程患者");
        pelvisTemplate(TPL, 4500L, new BigDecimal("40.00"));
        createPlanVersion(PATIENT, PLAN, 1, TPL, 5000L, 200L, 25,
                4500L, new BigDecimal("40.00"));
    }

    /** 审核→批准→首次验证合格（偏差0%），使计划成为可排程的当前版本。 */
    protected void approvedVerifiedPlan() {
        baseData();
        assert200(workflow.audit(PATIENT, PLAN, 1, "S-AUD"));
        assert200(workflow.approve(PATIENT, PLAN, 1, "S-APV"));
        assert200(workflow.verify(PATIENT, PLAN, 1, 200L, 200L, "S-VER"));
    }

    protected void registerDevice(int level) {
        scheduling.registerDevice(new DeviceRegisterRequest(DEVICE, "合成设备", level));
    }

    /** 固定业务时钟到给定 UTC 时刻（治疗窗口判定使用）。 */
    protected void setClockUtc(LocalDateTime utc) {
        mutableClock.setInstant(utc.toInstant(ZoneOffset.UTC));
    }

    protected static void assert200(RecordedResponse response) {
        Assertions.assertEquals(200, response.httpStatus(),
                "期望200，实际 " + response.httpStatus() + "：" + response.body());
    }

    /** 可变固定时钟：默认 2026-10-01T00:00:00Z，测试中可随时设置瞬间。 */
    static class MutableClock extends Clock {

        private volatile Instant instant = Instant.parse("2026-10-01T00:00:00Z");
        private final ZoneId zone = ClockConfig.BUSINESS_ZONE;

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {

        /** 方法名刻意与主配置 businessClock 不同，避免 Bean 定义覆盖；@Primary 保证按类型注入胜出。 */
        @Bean
        @Primary
        MutableClock mutableBusinessClock() {
            return new MutableClock();
        }
    }
}
