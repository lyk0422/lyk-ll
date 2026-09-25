package com.example.starter.gas.service;

import com.example.starter.gas.model.EventKind;
import com.example.starter.gas.model.LinkageTaskStatus;
import com.example.starter.gas.model.LinkageTaskType;
import com.example.starter.gas.model.PressureAction;
import com.example.starter.gas.model.SettlementStatus;
import com.example.starter.gas.model.ValveState;
import com.example.starter.gas.repo.GasRepository;
import com.example.starter.gas.repo.GasRepository.DayTotals;
import com.example.starter.gas.repo.GasRepository.SettlementRow;
import com.example.starter.gas.repo.GasRepository.StationRow;
import com.example.starter.gas.service.IdempotencyGate.Attempt;
import com.example.starter.gas.web.IdempotencyConflictException;
import com.example.starter.gas.web.NotFoundException;
import com.example.starter.gas.web.TaskSlotConflictException;
import com.example.starter.gas.web.dto.DetectionRequest;
import com.example.starter.gas.web.dto.DetectionResponse;
import com.example.starter.gas.web.dto.InjectionView;
import com.example.starter.gas.web.dto.LinkageRequest;
import com.example.starter.gas.web.dto.LinkageResponse;
import com.example.starter.gas.web.dto.SettlementRequest;
import com.example.starter.gas.web.dto.SettlementResponse;
import com.example.starter.gas.web.dto.StationResponse;
import com.example.starter.gas.web.dto.ValveCommandRequest;
import com.example.starter.gas.web.dto.ValveCommandResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 门站加臭与压力联动主流程。
 *
 * <p>核心规则：
 * <ul>
 *   <li>加臭按流量比例投加（0.02 g/m³，等价目标浓度 20 mg/m³）；浓度合格区间 [16,24] mg/m³，含边界；</li>
 *   <li>恰连续 2 次越限触发告警并联锁锁阀；锁阀期间实际投加为 0；</li>
 *   <li>压力 [200,400] kPa 含边界合格，越限联动 RAISE/LOWER，调压与加注同事务下发；</li>
 *   <li>requestId 指纹幂等：同键同参返回首次结果，同键异参 409 且状态不变；</li>
 *   <li>锁阀/复开须两名不同确认人；复开与告警解除同事务完成；</li>
 *   <li>日结偏差恰 3% 合格封存（终态），超出转人工；封存后迟到数据只记迟到事件。</li>
 * </ul>
 */
@Service
public class GasStationService {

    /** 投加比例：20 mg/m³ = 0.02 g/m³。 */
    static final BigDecimal DOSE_G_PER_M3 = new BigDecimal("0.02");
    /** 勾稽允许偏差：恰 0.03 合格。 */
    static final BigDecimal SETTLE_TOLERANCE = new BigDecimal("0.03");
    /** 恰连续 2 次越限触发告警。 */
    static final int ALARM_AFTER_VIOLATIONS = 2;

    private final GasRepository repo;
    private final IdempotencyGate gate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GasStationService(GasRepository repo, IdempotencyGate gate, ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.gate = gate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ============================ 运行监测 ============================

    @Transactional
    public DetectionResponse detect(DetectionRequest req) {
        String fingerprint = fingerprint("DETECTION", req.requestId(), req.stationCode(),
                req.detectedAt(), req.concentrationMg(), req.flowM3(), req.actualConsumeG());
        return runIdempotent(req.requestId(), "DETECTION", fingerprint, DetectionResponse.class, () -> {
            StationRow station = lockStation(req.stationCode());

            LocalDate settleDate = req.detectedAt().toLocalDate();
            if (isSealed(req.stationCode(), settleDate)) {
                String payload = "concentrationMg=" + req.concentrationMg().toPlainString()
                        + ",flowM3=" + req.flowM3().toPlainString();
                repo.insertLateEvent(req.stationCode(), settleDate, req.requestId(), req.detectedAt(), payload);
                repo.insertStationEvent(req.stationCode(), EventKind.LATE_DETECTION, req.detectedAt(),
                        "结算日 " + settleDate + " 已封存，检测仅登记为迟到事件");
                DetectionResponse resp = new DetectionResponse(req.requestId(), req.stationCode(),
                        req.detectedAt(), req.concentrationMg(), station.odorTarget(),
                        lowerBound(station), upperBound(station), false, station.violationCount(),
                        false, station.valveState().name(), station.alarmActive(), true, null, false);
                return saveFirstResponse(req.requestId(), resp);
            }

            BigDecimal lower = lowerBound(station);
            BigDecimal upper = upperBound(station);
            boolean inRange = req.concentrationMg().compareTo(lower) >= 0
                    && req.concentrationMg().compareTo(upper) <= 0;

            int newCount;
            boolean alarmNow = false;
            ValveState valveAfter = station.valveState();
            boolean alarmAfter = station.alarmActive();
            if (inRange) {
                newCount = 0;
            } else {
                newCount = station.violationCount() + 1;
                if (newCount == ALARM_AFTER_VIOLATIONS) {
                    // 恰连续 2 次：触发告警并锁定加注阀
                    alarmNow = true;
                    alarmAfter = true;
                    valveAfter = ValveState.LOCKED;
                }
            }

            boolean valveLocked = valveAfter == ValveState.LOCKED;
            InjectionView injection = computeInjection(req.flowM3(), req.actualConsumeG(), valveLocked);

            repo.insertDetection(req.stationCode(), req.requestId(), req.detectedAt(),
                    req.concentrationMg(), inRange, newCount);
            repo.insertFlowReading(req.stationCode(), req.requestId(), req.detectedAt(), req.flowM3());
            repo.insertInjection(req.stationCode(), req.requestId(), req.detectedAt(), req.flowM3(),
                    injection.doseGPerM3(), injection.theoreticalConsumeG(), injection.actualConsumeG(), valveLocked);
            if (alarmNow) {
                repo.insertStationEvent(req.stationCode(), EventKind.ODOR_ALARM, req.detectedAt(),
                        "连续 " + ALARM_AFTER_VIOLATIONS + " 次浓度越限，浓度=" + req.concentrationMg().toPlainString());
                repo.insertStationEvent(req.stationCode(), EventKind.VALVE_LOCKED, req.detectedAt(),
                        "告警联锁自动锁阀，停止加注");
            }
            BigDecimal newConsumed = station.odorConsumed().add(injection.actualConsumeG());
            repo.updateStationRuntime(req.stationCode(), valveAfter, alarmAfter, newCount, newConsumed);

            DetectionResponse resp = new DetectionResponse(req.requestId(), req.stationCode(),
                    req.detectedAt(), req.concentrationMg(), station.odorTarget(), lower, upper,
                    inRange, newCount, alarmNow, valveAfter.name(), alarmAfter, false, injection, false);
            return saveFirstResponse(req.requestId(), resp);
        });
    }

    // ============================ 压力调节联动 ============================

    @Transactional
    public LinkageResponse regulate(LinkageRequest req) {
        String fingerprint = fingerprint("LINKAGE", req.requestId(), req.stationCode(),
                req.detectedAt(), req.pressureKpa(), req.flowM3(), req.actualConsumeG());
        return runIdempotent(req.requestId(), "LINKAGE", fingerprint, LinkageResponse.class, () -> {
            StationRow station = lockStation(req.stationCode());

            LocalDate settleDate = req.detectedAt().toLocalDate();
            if (isSealed(req.stationCode(), settleDate)) {
                String payload = "pressureKpa=" + req.pressureKpa().toPlainString()
                        + ",flowM3=" + req.flowM3().toPlainString();
                repo.insertLateEvent(req.stationCode(), settleDate, req.requestId(), req.detectedAt(), payload);
                repo.insertStationEvent(req.stationCode(), EventKind.LATE_DETECTION, req.detectedAt(),
                        "结算日 " + settleDate + " 已封存，联动数据仅登记为迟到事件");
                LinkageResponse resp = new LinkageResponse(req.requestId(), req.stationCode(), req.detectedAt(),
                        req.pressureKpa(), station.pressureLow(), station.pressureHigh(),
                        PressureAction.HOLD.name(), null, false, true, null, false);
                return saveFirstResponse(req.requestId(), resp);
            }

            // 同站同时刻只允许一个联动任务：数据库唯一槽位，并发下达唯一受理
            long taskId;
            try {
                taskId = repo.insertLinkageTask(req.stationCode(), LinkageTaskType.PRESSURE_REGULATION,
                        LinkageTaskStatus.COMPLETED, req.detectedAt(), req.detectedAt(), req.requestId());
                repo.occupyLinkageSlot(req.stationCode(), req.detectedAt(), req.requestId(), taskId);
            } catch (DuplicateKeyException ex) {
                throw new TaskSlotConflictException("门站 " + req.stationCode() + " 在 " + req.detectedAt()
                        + " 的联动任务已被唯一受理");
            }

            // 恰等限值合格：< 下限升压，> 上限泄压，其余（含相等）保持
            PressureAction action;
            if (req.pressureKpa().compareTo(station.pressureLow()) < 0) {
                action = PressureAction.RAISE;
            } else if (req.pressureKpa().compareTo(station.pressureHigh()) > 0) {
                action = PressureAction.LOWER;
            } else {
                action = PressureAction.HOLD;
            }
            boolean regulated = action != PressureAction.HOLD;
            boolean valveLocked = station.valveState() == ValveState.LOCKED;
            InjectionView injection = computeInjection(req.flowM3(), req.actualConsumeG(), valveLocked);

            // 调压判定与加注调整在同一事务内下发
            repo.insertPressureDetection(req.stationCode(), req.requestId(), req.detectedAt(),
                    req.pressureKpa(), action);
            repo.insertFlowReading(req.stationCode(), req.requestId(), req.detectedAt(), req.flowM3());
            repo.insertInjection(req.stationCode(), req.requestId(), req.detectedAt(), req.flowM3(),
                    injection.doseGPerM3(), injection.theoreticalConsumeG(), injection.actualConsumeG(), valveLocked);
            BigDecimal newConsumed = station.odorConsumed().add(injection.actualConsumeG());
            repo.updateStationRuntime(req.stationCode(), station.valveState(), station.alarmActive(),
                    station.violationCount(), newConsumed);

            LinkageResponse resp = new LinkageResponse(req.requestId(), req.stationCode(), req.detectedAt(),
                    req.pressureKpa(), station.pressureLow(), station.pressureHigh(), action.name(),
                    taskId, regulated, false, injection, false);
            return saveFirstResponse(req.requestId(), resp);
        });
    }

    // ============================ 双人阀门指令 ============================

    @Transactional
    public ValveCommandResponse valveCommand(ValveCommandRequest req) {
        String fingerprint = fingerprint("VALVE", req.requestId(), req.stationCode(), req.occurredAt(),
                req.action(), req.operator1(), req.operator2());
        return runIdempotent(req.requestId(), "VALVE", fingerprint, ValveCommandResponse.class, () -> {
            StationRow station = lockStation(req.stationCode());

            boolean twoPersons = req.operator2() != null && !req.operator2().isBlank()
                    && !req.operator2().equals(req.operator1());
            boolean effective = twoPersons;
            ValveState valveAfter = station.valveState();
            boolean alarmAfter = station.alarmActive();
            int countAfter = station.violationCount();
            String reason;

            if (effective && "LOCK".equals(req.action())) {
                valveAfter = ValveState.LOCKED;
                reason = "双人确认，锁阀生效";
                repo.insertStationEvent(req.stationCode(), EventKind.VALVE_LOCKED, req.occurredAt(),
                        "双人确认锁阀：" + req.operator1() + "/" + req.operator2());
            } else if (effective && "REOPEN".equals(req.action())) {
                // 复开与告警解除在同一事务内完成
                valveAfter = ValveState.OPEN;
                alarmAfter = false;
                countAfter = 0;
                reason = "双人确认，复开并解除告警生效";
                repo.insertStationEvent(req.stationCode(), EventKind.VALVE_REOPENED, req.occurredAt(),
                        "双人确认复开并解除告警：" + req.operator1() + "/" + req.operator2());
            } else {
                effective = false;
                reason = "需要两名不同确认人，单人操作不生效，阀门状态保持";
            }

            repo.insertValveCommand(req.stationCode(), req.requestId(), req.action(),
                    req.operator1(), req.operator2(), effective);
            repo.updateStationRuntime(req.stationCode(), valveAfter, alarmAfter, countAfter, station.odorConsumed());

            ValveCommandResponse resp = new ValveCommandResponse(req.requestId(), req.stationCode(), req.action(),
                    effective, valveAfter.name(), alarmAfter, reason, false);
            return saveFirstResponse(req.requestId(), resp);
        });
    }

    // ============================ 日结勾稽 ============================

    @Transactional
    public SettlementResponse settle(SettlementRequest req) {
        String fingerprint = fingerprint("SETTLEMENT", req.requestId(), req.stationCode(),
                req.settleDate(), null, null, null);
        return runIdempotent(req.requestId(), "SETTLEMENT", fingerprint, SettlementResponse.class, () -> {
            lockStation(req.stationCode());
            SettlementRow existing = repo.findSettlement(req.stationCode(), req.settleDate());
            if (existing != null && SettlementStatus.SEALED.name().equals(existing.status())) {
                // 封存为终态：新 requestId 重复封存返回首次结果（幂等成功），并回填本键快照
                return saveFirstResponse(req.requestId(), toSettlementResponse(existing, true));
            }

            LocalDateTime dayStart = req.settleDate().atStartOfDay();
            LocalDateTime nextDayStart = req.settleDate().plusDays(1).atStartOfDay();
            DayTotals totals = repo.sumDay(req.stationCode(), dayStart, nextDayStart);
            BigDecimal theoretical = totals.totalFlow().multiply(DOSE_G_PER_M3);
            BigDecimal actual = totals.actualConsume();
            BigDecimal deviation;
            if (theoretical.compareTo(BigDecimal.ZERO) == 0) {
                deviation = actual.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
                        : BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP);
            } else {
                deviation = actual.subtract(theoretical).abs()
                        .divide(theoretical, 6, RoundingMode.HALF_UP);
            }
            boolean within = deviation.compareTo(SETTLE_TOLERANCE) <= 0;
            SettlementStatus status = within ? SettlementStatus.SEALED : SettlementStatus.MANUAL;
            LocalDateTime sealedAt = within ? LocalDateTime.now(clock) : null;

            SettlementRow row = new SettlementRow(req.stationCode(), req.settleDate(), req.requestId(),
                    totals.totalFlow(), theoretical, actual, deviation, status.name(), sealedAt);
            if (existing == null) {
                repo.insertSettlement(row);
            } else {
                // 此前为 MANUAL，允许用新请求按当前数据复算并更新同一结算单
                repo.updateSettlement(row);
            }
            if (!within) {
                repo.insertStationEvent(req.stationCode(), EventKind.MANUAL_REVIEW, LocalDateTime.now(clock),
                        "结算日 " + req.settleDate() + " 勾稽偏差 " + deviation.toPlainString()
                                + " 超过 3%，转人工");
            }

            SettlementResponse resp = new SettlementResponse(req.requestId(), req.stationCode(),
                    req.settleDate().toString(), totals.totalFlow(), theoretical, actual, deviation,
                    SETTLE_TOLERANCE, within, status.name(), sealedAt, false);
            return saveFirstResponse(req.requestId(), resp);
        });
    }

    @Transactional(readOnly = true)
    public StationResponse getStation(String stationCode) {
        StationRow station = repo.findStation(stationCode, false);
        if (station == null) {
            throw new NotFoundException("门站不存在: " + stationCode);
        }
        return new StationResponse(station.stationCode(), station.stationName(), station.odorTarget(),
                station.odorTolerance(), station.pressureLow(), station.pressureHigh(), station.odorConsumed(),
                station.valveState().name(), station.alarmActive(), station.violationCount());
    }

    /**
     * 浓度曲线复算：从落库检测序列按规则重放区间判定与连续越限计数，
     * 复开生效点会清零计数；可用于核对锁阀点与现场是否一致。
     */
    @Transactional(readOnly = true)
    public com.example.starter.gas.web.dto.ConcentrationReplayResponse replayConcentration(
            String stationCode, LocalDate settleDate) {
        StationRow station = lockStation(stationCode);
        LocalDateTime dayStart = settleDate.atStartOfDay();
        LocalDateTime nextDayStart = settleDate.plusDays(1).atStartOfDay();
        var detections = repo.listDetections(stationCode, dayStart, nextDayStart);
        var reopenTimes = new java.util.ArrayDeque<>(
                repo.listReopenTimes(stationCode, dayStart, nextDayStart));

        BigDecimal lower = lowerBound(station);
        BigDecimal upper = upperBound(station);
        var points = new java.util.ArrayList<com.example.starter.gas.web.dto.ConCurvePoint>();
        int running = 0;
        int lockPoints = 0;
        for (var d : detections) {
            while (!reopenTimes.isEmpty() && d.detectedAt().isAfter(reopenTimes.peekFirst())) {
                reopenTimes.pollFirst();
                running = 0;
            }
            boolean inRange = d.concentrationMg().compareTo(lower) >= 0
                    && d.concentrationMg().compareTo(upper) <= 0;
            boolean lockPoint = false;
            if (inRange) {
                running = 0;
            } else {
                running++;
                if (running == ALARM_AFTER_VIOLATIONS) {
                    lockPoint = true;
                    lockPoints++;
                }
            }
            points.add(new com.example.starter.gas.web.dto.ConCurvePoint(
                    d.detectedAt(), d.concentrationMg(), inRange, running, lockPoint));
        }
        return new com.example.starter.gas.web.dto.ConcentrationReplayResponse(
                stationCode, settleDate.toString(), station.odorTarget(), lower, upper, lockPoints, points);
    }

    /**
     * 压力链复算：从落库压力序列按 [下限,上限]（含边界）重放 HOLD/RAISE/LOWER 判定。
     */
    @Transactional(readOnly = true)
    public com.example.starter.gas.web.dto.PressureChainReplayResponse replayPressure(
            String stationCode, LocalDate settleDate) {
        StationRow station = lockStation(stationCode);
        LocalDateTime dayStart = settleDate.atStartOfDay();
        LocalDateTime nextDayStart = settleDate.plusDays(1).atStartOfDay();
        var rows = repo.listPressureDetections(stationCode, dayStart, nextDayStart);

        var points = new java.util.ArrayList<com.example.starter.gas.web.dto.PressureChainPoint>();
        long regulatedCount = 0;
        for (var r : rows) {
            PressureAction action;
            if (r.pressureKpa().compareTo(station.pressureLow()) < 0) {
                action = PressureAction.RAISE;
            } else if (r.pressureKpa().compareTo(station.pressureHigh()) > 0) {
                action = PressureAction.LOWER;
            } else {
                action = PressureAction.HOLD;
            }
            if (action != PressureAction.HOLD) {
                regulatedCount++;
            }
            points.add(new com.example.starter.gas.web.dto.PressureChainPoint(
                    r.detectedAt(), r.pressureKpa(), action.name(), action != PressureAction.HOLD));
        }
        return new com.example.starter.gas.web.dto.PressureChainReplayResponse(
                stationCode, settleDate.toString(), station.pressureLow(), station.pressureHigh(),
                regulatedCount, points);
    }

    // ============================ 内部方法（置于作用域末尾） ============================

    /**
     * 统一幂等执行模板：先在独立事务占位，再执行业务；业务回滚则清理占位。
     * 若并发首发在等待期间回滚，等待方会重新门控并尝试成为新的首发。
     */
    private <T> T runIdempotent(String requestId, String requestType, String fingerprint,
                                Class<T> responseType, Supplier<T> business) {
        while (true) {
            Attempt attempt = gate.begin(requestId, requestType, fingerprint);
            if (attempt instanceof Attempt.DiffAttempt) {
                throw new IdempotencyConflictException(
                        "requestId=" + requestId + " 已用于不同参数请求，拒绝处理且不改变现场状态");
            }
            if (attempt instanceof Attempt.SameAttempt same) {
                String body = same.responseBody();
                if (body == null) {
                    body = gate.awaitFirstResponse(requestId);
                    if (body == null) {
                        // 首发事务已回滚并清理占位：重新门控
                        continue;
                    }
                }
                return deserializeReplay(body, responseType);
            }
            try {
                return business.get();
            } catch (RuntimeException ex) {
                gate.abort(requestId);
                throw ex;
            }
        }
    }

    /** 业务事务内写回首次响应快照，随业务一起提交。 */
    private <T> T saveFirstResponse(String requestId, T response) {
        try {
            repo.completeIdempotent(requestId, objectMapper.writeValueAsString(response));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("首次响应无法序列化", ex);
        }
        return response;
    }

    private <T> T deserializeReplay(String body, Class<T> type) {
        try {
            JsonNode node = objectMapper.readTree(body);
            ((ObjectNode) node).put("idempotentReplayed", true);
            return objectMapper.treeToValue(node, type);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应快照无法还原", ex);
        }
    }

    private StationRow lockStation(String stationCode) {
        StationRow station = repo.findStation(stationCode, true);
        if (station == null) {
            throw new NotFoundException("门站不存在: " + stationCode);
        }
        return station;
    }

    private boolean isSealed(String stationCode, LocalDate date) {
        SettlementRow row = repo.findSettlement(stationCode, date);
        return row != null && SettlementStatus.SEALED.name().equals(row.status());
    }

    private BigDecimal lowerBound(StationRow station) {
        return station.odorTarget().multiply(BigDecimal.ONE.subtract(station.odorTolerance()));
    }

    private BigDecimal upperBound(StationRow station) {
        return station.odorTarget().multiply(BigDecimal.ONE.add(station.odorTolerance()));
    }

    private InjectionView computeInjection(BigDecimal flowM3, BigDecimal reportedActualG, boolean valveLocked) {
        BigDecimal theoretical = flowM3.multiply(DOSE_G_PER_M3);
        BigDecimal actual;
        if (valveLocked) {
            actual = BigDecimal.ZERO;
        } else if (reportedActualG != null) {
            actual = reportedActualG;
        } else {
            actual = theoretical;
        }
        return new InjectionView(DOSE_G_PER_M3, theoretical, actual, valveLocked);
    }

    /**
     * 指纹覆盖 requestId 与全部业务字段；BigDecimal 按去尾零的规范字符串归一，
     * 使 16.00 与 16.0 视为同参。
     */
    private String fingerprint(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(normalize(part)).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String normalize(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private SettlementResponse toSettlementResponse(SettlementRow row, boolean replayed) {
        boolean within = SettlementStatus.SEALED.name().equals(row.status());
        return new SettlementResponse(row.requestId(), row.stationCode(), row.settleDate().toString(),
                row.totalFlow(), row.theoreticalConsume(), row.actualConsume(), row.deviationRatio(),
                SETTLE_TOLERANCE, within, row.status(), row.sealedAt(), replayed);
    }
}
