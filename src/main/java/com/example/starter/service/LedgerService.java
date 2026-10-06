package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.api.Responses.LedgerEntry;
import com.example.starter.domain.PlanCount;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.RiskCount;
import com.example.starter.domain.SegmentRow;
import com.example.starter.repo.PlanRepository;
import com.example.starter.repo.SegmentRepository;
import com.example.starter.repo.TreeRepository;

/**
 * 台账：各路段风险分布与修剪闭环率。全部由当前库表实时复算，
 * 按路段编码排序，重复查询结果稳定。闭环率分母为待修剪+已闭环+已督办，
 * 已重排计划不计入；无计划时闭环率为 null。
 */
@Service
public class LedgerService {

    private final SegmentRepository segmentRepository;
    private final TreeRepository treeRepository;
    private final PlanRepository planRepository;
    private final Clock clock;

    public LedgerService(SegmentRepository segmentRepository, TreeRepository treeRepository,
                         PlanRepository planRepository, Clock clock) {
        this.segmentRepository = segmentRepository;
        this.treeRepository = treeRepository;
        this.planRepository = planRepository;
        this.clock = clock;
    }

    @Transactional
    public List<LedgerEntry> ledger() {
        // 逾期升级督办与台账统计同事务，保证可复算
        planRepository.escalateOverdue(LocalDateTime.now(clock));

        Map<Long, Map<Integer, Long>> riskBySegment = new HashMap<>();
        for (RiskCount rc : treeRepository.countBySegmentAndLevel()) {
            riskBySegment.computeIfAbsent(rc.segmentId(), k -> new HashMap<>())
                    .put(rc.riskLevel(), rc.count());
        }
        Map<Long, Map<String, Long>> planBySegment = new HashMap<>();
        for (PlanCount pc : planRepository.countBySegmentAndStatus()) {
            planBySegment.computeIfAbsent(pc.segmentId(), k -> new HashMap<>())
                    .put(pc.status(), pc.count());
        }

        return segmentRepository.findAll().stream().map(segment -> {
            Map<Integer, Long> risk = riskBySegment.getOrDefault(segment.id(), Map.of());
            Map<String, Long> plans = planBySegment.getOrDefault(segment.id(), Map.of());
            long pending = plans.getOrDefault(PlanStatus.PENDING.name(), 0L);
            long closed = plans.getOrDefault(PlanStatus.CLOSED.name(), 0L);
            long escalated = plans.getOrDefault(PlanStatus.ESCALATED.name(), 0L);
            long total = pending + closed + escalated;
            Double closureRate = total == 0 ? null
                    : Math.round(closed * 10000.0 / total) / 10000.0;
            return new LedgerEntry(segment.code(),
                    risk.getOrDefault(1, 0L), risk.getOrDefault(2, 0L),
                    risk.getOrDefault(3, 0L), risk.getOrDefault(4, 0L),
                    total, closed, escalated, closureRate);
        }).toList();
    }
}
