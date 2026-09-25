package com.example.starter.rt;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.rt.domain.MetricType;
import com.example.starter.rt.repository.DirectoryRepository;
import com.example.starter.rt.repository.PlanRepository;

/**
 * H2 集成测试基类：真实 Spring 容器、真实事务与 H2（MODE=MySQL）。
 * 不使用测试托管事务（保证并发用例中服务事务边界真实有效），
 * 每个用例前以自动提交方式清空全部业务表，避免顺序依赖。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    protected PlanRepository planRepository;

    @Autowired
    protected DirectoryRepository directoryRepository;

    @Autowired
    protected Clock clock;

    /** 清理由 {@link #cleanDatabase()} 在非事务上下文中完成，以真正提交清空。 */
    @BeforeEach
    void clean() {
        planRepository.deleteAllForTest();
    }

    /** 创建合成患者。 */
    protected void patient(String patientId, String name) {
        directoryRepository.insertPatient(patientId, name, LocalDateTime.now(clock));
    }

    /** 创建含脊髓 DMAX 与膀胱 V40 两项约束的模板。 */
    protected void pelvisTemplate(String templateCode, long spinalLimit, BigDecimal bladderLimit) {
        directoryRepository.insertTemplate(templateCode, "盆腔模板", LocalDateTime.now(clock));
        directoryRepository.insertConstraintItem(templateCode, "SPINAL_CORD", "脊髓",
                MetricType.DMAX_CGY.name(), null, BigDecimal.valueOf(spinalLimit));
        directoryRepository.insertConstraintItem(templateCode, "BLADDER", "膀胱",
                MetricType.V_DOSE_PCT.name(), 4000L, bladderLimit);
    }

    /** 创建计划版本并写入两项申报剂量，返回版本ID。 */
    protected long createPlanVersion(String patientId, String planCode, int versionNo,
                                     String templateCode, long totalDose, long fractionDose,
                                     int fractionCount, long spinalPlanned,
                                     BigDecimal bladderPlanned) {
        long id = planRepository.insertVersion(patientId, planCode, versionNo, templateCode,
                totalDose, fractionDose, fractionCount, LocalDateTime.now(clock));
        planRepository.insertOarDose(id, "SPINAL_CORD", MetricType.DMAX_CGY.name(),
                null, BigDecimal.valueOf(spinalPlanned));
        planRepository.insertOarDose(id, "BLADDER", MetricType.V_DOSE_PCT.name(),
                4000L, bladderPlanned);
        return id;
    }
}
