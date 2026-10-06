package com.example.starter.service;

import com.example.starter.model.PowerLimitRow;
import com.example.starter.repository.AssessmentRepository;
import com.example.starter.repository.DefectRepository;
import com.example.starter.repository.PowerLimitRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 限功率同步：维护不变式——叶片存在未闭环结构级缺陷或进行中根因评估时限功率生效，
 * 否则撤销。限功率链由缺陷/维修/评估状态确定性推导，可复算。
 */
@Service
public class PowerLimitService {

    /** 限功率原因：结构级缺陷。 */
    public static final String REASON_STRUCTURAL_DEFECT = "STRUCTURAL_DEFECT";
    /** 限功率原因：根因评估。 */
    public static final String REASON_ROOT_CAUSE_ASSESSMENT = "ROOT_CAUSE_ASSESSMENT";

    private final PowerLimitRepository powerLimitRepository;
    private final DefectRepository defectRepository;
    private final AssessmentRepository assessmentRepository;

    public PowerLimitService(PowerLimitRepository powerLimitRepository,
                             DefectRepository defectRepository,
                             AssessmentRepository assessmentRepository) {
        this.powerLimitRepository = powerLimitRepository;
        this.defectRepository = defectRepository;
        this.assessmentRepository = assessmentRepository;
    }

    /**
     * 按当前缺陷与评估状态同步叶片限功率（须在业务事务内调用）。
     * 限功率事由变化时结束当前区间并按新事由开启新区间，形成可复算的限功率链。
     */
    public void sync(long bladeId, LocalDateTime now) {
        boolean assessmentActive = assessmentRepository.existsActiveByBlade(bladeId);
        boolean required = assessmentActive || defectRepository.existsUnresolvedStructural(bladeId);
        Optional<PowerLimitRow> active = powerLimitRepository.findActiveByBlade(bladeId);
        if (required) {
            String reason = assessmentActive ? REASON_ROOT_CAUSE_ASSESSMENT : REASON_STRUCTURAL_DEFECT;
            if (active.isEmpty()) {
                powerLimitRepository.insert(bladeId, reason, now, now);
            } else if (!active.get().reason().equals(reason)) {
                powerLimitRepository.revoke(active.get().id(), now);
                powerLimitRepository.insert(bladeId, reason, now, now);
            }
        } else if (active.isPresent()) {
            powerLimitRepository.revoke(active.get().id(), now);
        }
    }

    /**
     * 叶片当前是否处于限功率状态。
     */
    public boolean isActive(long bladeId) {
        return powerLimitRepository.findActiveByBlade(bladeId).isPresent();
    }
}
