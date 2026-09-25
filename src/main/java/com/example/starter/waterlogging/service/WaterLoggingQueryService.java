package com.example.starter.waterlogging.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.waterlogging.api.PlanView;
import com.example.starter.waterlogging.api.PumpCommandResult;
import com.example.starter.waterlogging.api.ReadingResult;
import com.example.starter.waterlogging.exception.NotFoundException;
import com.example.starter.waterlogging.po.LossContactOrder;
import com.example.starter.waterlogging.po.PreDrainPlan;
import com.example.starter.waterlogging.repo.LossContactOrderRepository;
import com.example.starter.waterlogging.repo.PlanRepository;
import com.example.starter.waterlogging.repo.PumpCommandRepository;
import com.example.starter.waterlogging.repo.PumpStationRepository;
import com.example.starter.waterlogging.repo.ReadingRepository;

/**
 * 只读复算服务：预降单状态、水位曲线、启停序列、失联派单台账均可复算。
 */
@Service
public class WaterLoggingQueryService {

    private final PlanRepository planRepository;
    private final PumpStationRepository stationRepository;
    private final PumpCommandRepository commandRepository;
    private final ReadingRepository readingRepository;
    private final LossContactOrderRepository lossOrderRepository;

    public WaterLoggingQueryService(PlanRepository planRepository,
                                    PumpStationRepository stationRepository,
                                    PumpCommandRepository commandRepository,
                                    ReadingRepository readingRepository,
                                    LossContactOrderRepository lossOrderRepository) {
        this.planRepository = planRepository;
        this.stationRepository = stationRepository;
        this.commandRepository = commandRepository;
        this.readingRepository = readingRepository;
        this.lossOrderRepository = lossOrderRepository;
    }

    /** 查询预降单及分区内各泵站当前状态。 */
    public PlanView getPlan(String planRequestId) {
        PreDrainPlan plan = planRepository.findByRequestId(planRequestId)
                .orElseThrow(() -> new NotFoundException("PLAN_NOT_FOUND",
                        "预降单不存在: " + planRequestId));
        List<PlanView.StationStateView> stations = stationRepository.findByZone(plan.basinZone()).stream()
                .map(s -> new PlanView.StationStateView(
                        s.stationCode(), s.stationName(), s.safetyLowLevel(),
                        s.runStatus(), s.gateOpening()))
                .toList();
        return new PlanView(plan.id(), plan.requestId(), plan.basinZone(), plan.warningLevelCode(),
                plan.riverName(), plan.targetLevel(), plan.warningTime(), plan.gateOpening(),
                plan.status(), plan.startedAt(), plan.completedAt(), plan.archivedAt(), stations);
    }

    /** 复算预降单水位曲线。 */
    public List<ReadingResult> getCurve(String planRequestId) {
        PreDrainPlan plan = requirePlan(planRequestId);
        return readingRepository.findByPlan(plan.id()).stream()
                .map(r -> new ReadingResult(r.id(), r.stationCode(), r.readingTime(), r.level(),
                        true, false))
                .toList();
    }

    /** 复算预降单泵站启停序列。 */
    public List<PumpCommandResult> getCommands(String planRequestId) {
        PreDrainPlan plan = requirePlan(planRequestId);
        return commandRepository.findByPlan(plan.id()).stream()
                .map(c -> new PumpCommandResult(
                        c.id(), c.planId(), c.stationCode(), c.action(), c.sequenceNo(),
                        c.commandTime(), c.gateOpening(), Boolean.TRUE.equals(c.gateAdjusted()),
                        "START".equals(c.action()) ? "RUNNING" : "STOPPED"))
                .toList();
    }

    /** 复算某站点失联派单台账。 */
    public List<LossContactOrder> getLossOrders(String stationCode) {
        return lossOrderRepository.findByStation(stationCode);
    }

    private PreDrainPlan requirePlan(String planRequestId) {
        return planRepository.findByRequestId(planRequestId)
                .orElseThrow(() -> new NotFoundException("PLAN_NOT_FOUND",
                        "预降单不存在: " + planRequestId));
    }
}
