package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.starter.domain.AlarmRow;
import com.example.starter.domain.AllocationRow;
import com.example.starter.domain.CommandRow;
import com.example.starter.domain.ReportRow;
import com.example.starter.domain.TurbineRow;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.AllocationRepository;
import com.example.starter.repo.CommandRepository;
import com.example.starter.repo.ReportRepository;
import com.example.starter.repo.TurbineRepository;
import com.example.starter.util.Fingerprints;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.AlarmView;
import com.example.starter.web.dto.CurvePoint;
import com.example.starter.web.dto.ReportAccepted;
import com.example.starter.web.dto.ReportRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行回报服务：requestId 幂等、机组＋时刻去重、乱序归位、超时告警与重分摊、封存后迟到事件。
 */
@Service
public class ReportService {

    /**
     * 超时阈值：下发后 10 分钟（恰满 10 分钟判定为超时）。
     */
    static final long TIMEOUT_MINUTES = 10;

    private final CommandRepository commandRepository;
    private final TurbineRepository turbineRepository;
    private final ReportRepository reportRepository;
    private final AllocationRepository allocationRepository;
    private final AlarmRepository alarmRepository;
    private final CurtailmentService curtailmentService;
    private final Clock clock;

    public ReportService(CommandRepository commandRepository, TurbineRepository turbineRepository,
                         ReportRepository reportRepository,
                         AllocationRepository allocationRepository,
                         AlarmRepository alarmRepository, CurtailmentService curtailmentService,
                         Clock clock) {
        this.commandRepository = commandRepository;
        this.turbineRepository = turbineRepository;
        this.reportRepository = reportRepository;
        this.allocationRepository = allocationRepository;
        this.alarmRepository = alarmRepository;
        this.curtailmentService = curtailmentService;
        this.clock = clock;
    }

    /**
     * 接收执行回报。
     */
    @Transactional
    public ReportAccepted submit(ReportRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(),
                String.valueOf(request.commandId()), String.valueOf(request.turbineId()),
                request.reportTime().toString(), String.valueOf(request.actualKw()));

        var existing = reportRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            ReportRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在但业务字段不一致");
            }
            return toAccepted(row, true, false, false);
        }

        CommandRow command = commandRepository.findById(request.commandId())
                .orElseThrow(() -> ApiException.notFound("限电指令不存在: " + request.commandId()));
        TurbineRow turbine = turbineRepository.findById(request.turbineId())
                .orElseThrow(() -> ApiException.notFound("机组不存在: " + request.turbineId()));
        if (turbine.farmId() != command.farmId()) {
            throw ApiException.unprocessable("机组不属于该指令的风场");
        }

        // 机组＋时刻去重：同值去重返回，异值冲突 409
        var duplicate = reportRepository.findByPoint(request.commandId(), request.turbineId(),
                request.reportTime());
        if (duplicate.isPresent()) {
            ReportRow row = duplicate.get();
            if (row.actualKw() != request.actualKw()) {
                throw ApiException.conflict("同机组同时刻已存在不同取值的回报");
            }
            return toAccepted(row, true, false, false);
        }

        boolean late = !CommandRow.STATUS_EXECUTING.equals(command.status());
        LocalDateTime receivedAt = LocalDateTime.now(clock);
        long reportId = reportRepository.insert(request.commandId(), command.farmId(),
                request.turbineId(), request.requestId(), fingerprint, request.reportTime(),
                request.actualKw(), late, receivedAt);

        if (late) {
            // 封存/作废后的迟到回报只记迟到事件，不参与分摊
            alarmRepository.insert(command.id(), turbine.id(), AlarmRow.TYPE_LATE_REPORT,
                    "指令已" + command.status() + "，迟到回报仅记录事件", receivedAt);
            return new ReportAccepted(reportId, command.id(), turbine.id(), request.reportTime(),
                    request.actualKw(), true, false, false, false);
        }

        // 最新可用出力随回报刷新
        turbineRepository.updateAvailableKw(turbine.id(), request.actualKw());

        // 下发后 10 分钟未达目标（恰满 10 分钟判定）：告警并按最新可用出力重分摊
        boolean alarmRaised = false;
        boolean reallocated = false;
        LocalDateTime deadline = command.issuedAt().plus(TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        if (!request.reportTime().isBefore(deadline)) {
            AllocationRow current = allocationRepository
                    .findLatestForTurbine(command.id(), turbine.id());
            if (current != null && request.actualKw() > current.targetKw()) {
                alarmRepository.insert(command.id(), turbine.id(), AlarmRow.TYPE_CURTAIL_TIMEOUT,
                        "下发满 10 分钟未达目标：目标 " + current.targetKw() + " kW，实际 "
                                + request.actualKw() + " kW", receivedAt);
                alarmRaised = true;
                reallocate(command);
                reallocated = true;
            }
        }
        return new ReportAccepted(reportId, command.id(), turbine.id(), request.reportTime(),
                request.actualKw(), false, false, alarmRaised, reallocated);
    }

    /**
     * 执行曲线：乱序回报按采集时刻归位。
     */
    @Transactional(readOnly = true)
    public List<CurvePoint> curve(long commandId) {
        CommandRow command = commandRepository.findById(commandId)
                .orElseThrow(() -> ApiException.notFound("限电指令不存在: " + commandId));
        Map<Long, TurbineRow> turbines = turbineRepository.findByFarmId(command.farmId()).stream()
                .collect(Collectors.toMap(TurbineRow::id, Function.identity()));
        return reportRepository.findCurve(commandId).stream()
                .map(r -> new CurvePoint(r.turbineId(),
                        turbines.containsKey(r.turbineId()) ? turbines.get(r.turbineId()).code()
                                : null,
                        r.reportTime(), r.actualKw(), r.late()))
                .toList();
    }

    /**
     * 告警链：按事件生成顺序。
     */
    @Transactional(readOnly = true)
    public List<AlarmView> alarms(long commandId) {
        commandRepository.findById(commandId)
                .orElseThrow(() -> ApiException.notFound("限电指令不存在: " + commandId));
        return alarmRepository.findByCommandId(commandId).stream()
                .map(a -> new AlarmView(a.id(), a.turbineId(), a.type(), a.message(),
                        a.createdAt()))
                .toList();
    }

    /**
     * 按最新可用出力重分摊，生成新一轮分摊明细。
     */
    private void reallocate(CommandRow command) {
        List<TurbineRow> turbines = turbineRepository.findByFarmId(command.farmId());
        int totalMin = turbines.stream().mapToInt(TurbineRow::minKw).sum();
        int effectiveTarget = Math.max(command.targetKw(), totalMin);
        AllocationCalculator.Result result = curtailmentService.allocate(turbines, effectiveTarget);
        int nextRound = allocationRepository.maxRound(command.id()) + 1;
        allocationRepository.batchInsert(command.id(), nextRound, result.allocations());
    }

    private ReportAccepted toAccepted(ReportRow row, boolean deduplicated, boolean alarmRaised,
                                      boolean reallocated) {
        return new ReportAccepted(row.id(), row.commandId(), row.turbineId(), row.reportTime(),
                row.actualKw(), row.late(), deduplicated, alarmRaised, reallocated);
    }
}
