package com.example.starter.service;

import com.example.starter.dto.AlertView;
import com.example.starter.dto.AssessmentView;
import com.example.starter.dto.PowerLimitView;
import com.example.starter.dto.RecurrenceStatView;
import com.example.starter.dto.RepairOrderView;
import com.example.starter.model.AlertRow;
import com.example.starter.model.AssessmentRow;
import com.example.starter.model.PowerLimitRow;
import com.example.starter.repository.AlertRepository;
import com.example.starter.repository.AssessmentRepository;
import com.example.starter.repository.DefectRepository;
import com.example.starter.repository.PowerLimitRepository;
import com.example.starter.repository.RepairOrderRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询服务：限功率链、维修台账、复发统计、评估与告警，均由明细表实时复算，重复查询稳定。
 */
@Service
public class QueryService {

    private final PowerLimitRepository powerLimitRepository;
    private final RepairOrderRepository repairOrderRepository;
    private final DefectRepository defectRepository;
    private final AssessmentRepository assessmentRepository;
    private final AlertRepository alertRepository;
    private final Clock clock;

    public QueryService(PowerLimitRepository powerLimitRepository,
                        RepairOrderRepository repairOrderRepository,
                        DefectRepository defectRepository,
                        AssessmentRepository assessmentRepository,
                        AlertRepository alertRepository,
                        Clock clock) {
        this.powerLimitRepository = powerLimitRepository;
        this.repairOrderRepository = repairOrderRepository;
        this.defectRepository = defectRepository;
        this.assessmentRepository = assessmentRepository;
        this.alertRepository = alertRepository;
        this.clock = clock;
    }

    /**
     * 限功率链：按开始时刻排序的全部限功率区间。
     */
    @Transactional(readOnly = true)
    public List<PowerLimitView> powerLimits(long bladeId) {
        return powerLimitRepository.listByBlade(bladeId).stream()
                .map(QueryService::toView)
                .toList();
    }

    /**
     * 维修台账：按叶片列出全部维修单。
     */
    @Transactional(readOnly = true)
    public List<RepairOrderView> repairOrders(long bladeId) {
        return repairOrderRepository.listByBlade(bladeId).stream()
                .map(RepairService::toView)
                .toList();
    }

    /**
     * 复发统计：以当前时刻为窗口终点，按缺陷类别聚合，可复算。
     */
    @Transactional(readOnly = true)
    public List<RecurrenceStatView> recurrence(long bladeId, int days) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowStart = now.minusDays(days);
        return defectRepository.aggregateByTypeWithin(bladeId, windowStart, now).stream()
                .map(agg -> new RecurrenceStatView(agg.defectType(), agg.count(),
                        windowStart, now, agg.firstDetectedAt(), agg.lastDetectedAt()))
                .toList();
    }

    /**
     * 根因评估列表。
     */
    @Transactional(readOnly = true)
    public List<AssessmentView> assessments(long bladeId) {
        boolean limitActive = powerLimitRepository.findActiveByBlade(bladeId).isPresent();
        return assessmentRepository.listByBlade(bladeId).stream()
                .map(row -> toView(row, limitActive))
                .toList();
    }

    /**
     * 逾期升级告警列表。
     */
    @Transactional(readOnly = true)
    public List<AlertView> alerts(long bladeId) {
        return alertRepository.listByBlade(bladeId).stream()
                .map(QueryService::toView)
                .toList();
    }

    private static PowerLimitView toView(PowerLimitRow row) {
        return new PowerLimitView(row.id(), row.bladeId(), row.reason(),
                row.startedAt(), row.revokedAt(), row.active());
    }

    private static AssessmentView toView(AssessmentRow row, boolean limitActive) {
        return new AssessmentView(row.id(), row.bladeId(), row.defectType(), row.triggerCount(),
                row.status(), row.startedAt(), row.completedAt(), limitActive);
    }

    private static AlertView toView(AlertRow row) {
        return new AlertView(row.id(), row.repairOrderId(), row.bladeId(), row.defectId(),
                row.firstDetectedAt(), row.alertAt(), row.message());
    }
}
