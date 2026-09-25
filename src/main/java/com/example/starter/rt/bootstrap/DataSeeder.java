package com.example.starter.rt.bootstrap;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.rt.domain.MetricType;
import com.example.starter.rt.repository.DirectoryRepository;
import com.example.starter.rt.repository.PlanRepository;

/**
 * 本地合成数据初始化：在种子患者 P1001 与模板 TPL_PELVIS 就绪后，
 * 创建一个 DESIGN 状态示范计划 PLAN_PELVIS_01 v1（200cGy×25 次=5000cGy，申报剂量恰等限值）。
 * 仅在该患者尚无任何计划版本时执行，保证重复启动幂等；数据仅存于内存库。
 */
@Component
@Order(0)
public class DataSeeder implements ApplicationRunner {

    static final String PATIENT_ID = "P1001";
    static final String PLAN_CODE = "PLAN_PELVIS_01";
    static final String TEMPLATE_CODE = "TPL_PELVIS";

    private final DirectoryRepository directoryRepository;
    private final PlanRepository planRepository;
    private final Clock clock;

    public DataSeeder(DirectoryRepository directoryRepository, PlanRepository planRepository, Clock clock) {
        this.directoryRepository = directoryRepository;
        this.planRepository = planRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!directoryRepository.patientExists(PATIENT_ID)
                || !directoryRepository.templateExists(TEMPLATE_CODE)) {
            return;
        }
        if (!planRepository.findVersions(PATIENT_ID, PLAN_CODE).isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int versionNo = planRepository.nextVersionNo(PATIENT_ID, PLAN_CODE);
        long versionId = planRepository.insertVersion(PATIENT_ID, PLAN_CODE, versionNo, TEMPLATE_CODE,
                5000L, 200L, 25, now);
        planRepository.insertOarDose(versionId, "SPINAL_CORD", MetricType.DMAX_CGY.name(),
                null, new BigDecimal("4500.00"));
        planRepository.insertOarDose(versionId, "BLADDER", MetricType.V_DOSE_PCT.name(),
                4000L, new BigDecimal("40.00"));
    }
}
