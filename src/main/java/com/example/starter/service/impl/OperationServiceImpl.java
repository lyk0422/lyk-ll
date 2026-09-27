package com.example.starter.service.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.api.dto.ActionResult;
import com.example.starter.api.dto.CardView;
import com.example.starter.api.dto.CoverageRequest;
import com.example.starter.api.dto.DispatchRequest;
import com.example.starter.api.dto.InspectionRequest;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ToolRegisterRequest;
import com.example.starter.api.dto.TorqueRequest;
import com.example.starter.api.dto.TorqueResult;
import com.example.starter.api.dto.TraceView;
import com.example.starter.api.dto.WitnessRequest;
import com.example.starter.api.dto.WitnessResult;
import com.example.starter.domain.CardStatus;
import com.example.starter.domain.TorqueMath;
import com.example.starter.domain.WitnessStatus;
import com.example.starter.persistence.AcquisitionRepository;
import com.example.starter.persistence.CardRepository;
import com.example.starter.persistence.CardRow;
import com.example.starter.persistence.FastenerRow;
import com.example.starter.persistence.InspectionRepository;
import com.example.starter.persistence.ReleaseRepository;
import com.example.starter.persistence.ToolRepository;
import com.example.starter.persistence.TorqueRepository;
import com.example.starter.persistence.WitnessRepository;
import com.example.starter.service.IdempotencyExecutor;
import com.example.starter.service.OperationService;
import com.example.starter.service.Outcome;
import com.example.starter.support.ApiException;

/**
 * 航空装配力矩紧固与见证点控制核心服务。
 *
 * <p>所有携带 requestId 的变更操作经 {@link IdempotencyExecutor} 包裹：业务动作与台账同事务提交，
 * 失败结果另起事务登记。返工与力矩记录在同一事务内追加，原始力矩记录永不覆盖。
 */
@Service
public class OperationServiceImpl implements OperationService {

    /** 见证等待升级时长：2 小时，恰满（含边界）即升级。 */
    static final Duration WITNESS_WAIT_LIMIT = Duration.ofHours(2);
    /** 同一紧固点最大返工次数：恰 2 次合法，第 3 次测量仍超差则整批隔离。 */
    static final int MAX_REWORKS = 2;
    private static final BigDecimal DEFAULT_TOLERANCE_PCT = new BigDecimal("5.00");

    private final Clock clock;
    private final IdempotencyExecutor idempotencyExecutor;
    private final CardRepository cardRepository;
    private final TorqueRepository torqueRepository;
    private final InspectionRepository inspectionRepository;
    private final WitnessRepository witnessRepository;
    private final ReleaseRepository releaseRepository;
    private final ToolRepository toolRepository;
    private final AcquisitionRepository acquisitionRepository;

    public OperationServiceImpl(Clock clock,
                                IdempotencyExecutor idempotencyExecutor,
                                CardRepository cardRepository,
                                TorqueRepository torqueRepository,
                                InspectionRepository inspectionRepository,
                                WitnessRepository witnessRepository,
                                ReleaseRepository releaseRepository,
                                ToolRepository toolRepository,
                                AcquisitionRepository acquisitionRepository) {
        this.clock = clock;
        this.idempotencyExecutor = idempotencyExecutor;
        this.cardRepository = cardRepository;
        this.torqueRepository = torqueRepository;
        this.inspectionRepository = inspectionRepository;
        this.witnessRepository = witnessRepository;
        this.releaseRepository = releaseRepository;
        this.toolRepository = toolRepository;
        this.acquisitionRepository = acquisitionRepository;
    }

    // ------------------------------------------------------------------
    // 派工
    // ------------------------------------------------------------------

    @Override
    public Outcome dispatch(DispatchRequest request) {
        validateDispatch(request);
        return idempotencyExecutor.execute(request.requestId(), "DISPATCH", request, () -> {
            Instant now = clock.instant();
            try {
                cardRepository.insertGuard(request.station(), request.cardNo(), now);
            } catch (DuplicateKeyException stationBusy) {
                throw ApiException.conflict("STATION_BUSY",
                        "工位 " + request.station() + " 已存在执行中工序卡");
            }
            long cardId;
            try {
                cardId = cardRepository.insertCard(request.cardNo(), request.station(), request.productSn(),
                        request.witnessPoint(), request.operator(), now);
            } catch (DuplicateKeyException cardNoExists) {
                throw ApiException.conflict("CARD_NO_EXISTS", "工序卡号已存在: " + request.cardNo());
            }
            for (DispatchRequest.FastenerSpec spec : request.fasteners()) {
                BigDecimal tolerance = spec.tolerancePct() == null
                        ? DEFAULT_TOLERANCE_PCT : spec.tolerancePct();
                cardRepository.insertFastener(cardId, spec.pointCode(), spec.nominalTorque(), tolerance);
            }
            CardRow card = requireCard(request.cardNo());
            return Outcome.of(201, toView(card));
        });
    }

    private void validateDispatch(DispatchRequest request) {
        if (request.fasteners() == null || request.fasteners().isEmpty()) {
            throw ApiException.badRequest("FASTENERS_REQUIRED", "派工至少包含一个紧固点");
        }
        Set<String> points = new HashSet<>();
        for (DispatchRequest.FastenerSpec spec : request.fasteners()) {
            if (!points.add(spec.pointCode())) {
                throw ApiException.badRequest("DUPLICATE_POINT", "紧固点编号重复: " + spec.pointCode());
            }
            if (spec.tolerancePct() != null
                    && spec.tolerancePct().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("TOLERANCE_INVALID", "允差必须为正数");
            }
        }
    }

    // ------------------------------------------------------------------
    // 力矩紧固测量
    // ------------------------------------------------------------------

    @Override
    public Outcome measure(TorqueRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "TORQUE_MEASURE", request, () -> {
            CardRow card = requireCard(request.cardNo());
            Instant now = clock.instant();

            // 终态：归档后迟到测量只记迟到事件，不改变工序状态。
            if (card.status() == CardStatus.ARCHIVED) {
                torqueRepository.insertLateEvent(card.id(), request.pointCode(), request.measuredTorque(),
                        request.operator(), request.toolCode(), now, request.requestId());
                TorqueResult late = new TorqueResult(card.cardNo(), request.pointCode(), 0, null,
                        request.measuredTorque(), null, false, null, card.status().name(), true, now);
                return Outcome.of(200, late);
            }
            // 隔离评审冻结：不再受理新测量。
            if (card.status() == CardStatus.QUARANTINED) {
                throw ApiException.unprocessable("CARD_QUARANTINED",
                        "工序卡已整批隔离评审，冻结受理力矩测量");
            }
            if (card.status() != CardStatus.DISPATCHED) {
                throw ApiException.unprocessable("CARD_NOT_IN_TIGHTENING",
                        "当前状态 " + card.status() + " 不受理力矩测量");
            }
            FastenerRow fastener = cardRepository.findFastener(card.id(), request.pointCode());
            if (fastener == null) {
                throw ApiException.notFound("FASTENER_NOT_FOUND",
                        "紧固点不存在: " + request.pointCode());
            }
            assertToolValidToday(request.toolCode());

            int attempts = torqueRepository.countAttempts(card.id(), fastener.id());
            int reworks = torqueRepository.countReworks(card.id(), fastener.id());
            int attemptNo = attempts + 1;
            BigDecimal deviation = TorqueMath.deviationPct(fastener.nominalTorque(), request.measuredTorque());
            boolean pass = TorqueMath.inTolerance(fastener.nominalTorque(), request.measuredTorque(),
                    fastener.tolerancePct());

            // 力矩记录追加（永不覆盖）。
            torqueRepository.insertTorque(new TorqueRepository.TorqueRow(
                    0, card.id(), fastener.id(), attemptNo, request.measuredTorque(), deviation, pass,
                    request.operator(), request.toolCode(), now, request.requestId()));

            Integer reworkNo = null;
            if (!pass) {
                if (reworks < MAX_REWORKS) {
                    // 超差即返工并记录；返工台账与力矩链同一事务追加。
                    reworkNo = reworks + 1;
                    String reason = "实测 " + request.measuredTorque() + " N·m，偏差 " + deviation
                            + "%，超出 ±" + fastener.tolerancePct() + "%";
                    torqueRepository.insertRework(new TorqueRepository.ReworkRow(
                            0, card.id(), fastener.id(), reworkNo, request.requestId(), reason, now), now);
                } else {
                    // 已返工恰 2 次后第 3 次测量仍超差：整批隔离评审。
                    cardRepository.markQuarantined(card.id(), now);
                    card = requireCard(card.cardNo());
                    TorqueResult quarantined = new TorqueResult(card.cardNo(), request.pointCode(), attemptNo,
                            fastener.nominalTorque(), request.measuredTorque(), deviation, false, null,
                            CardStatus.QUARANTINED.name(), false, now);
                    return Outcome.of(422, quarantined);
                }
            }

            // 全部紧固点最近一次测量均合格 → 力矩紧固阶段完成。
            int fastenerCount = cardRepository.listFasteners(card.id()).size();
            if (torqueRepository.allFastenersLastAttemptPassing(card.id(), fastenerCount)) {
                cardRepository.updateStatusIfCurrent(card.id(), CardStatus.DISPATCHED, CardStatus.TIGHTENED);
            }
            card = requireCard(card.cardNo());
            return Outcome.of(200, new TorqueResult(card.cardNo(), request.pointCode(), attemptNo,
                    fastener.nominalTorque(), request.measuredTorque(), deviation, pass, reworkNo,
                    card.status().name(), false, now));
        });
    }

    private void assertToolValidToday(String toolCode) {
        ToolRepository.ToolRow tool = toolRepository.findByCode(toolCode);
        if (tool == null) {
            throw ApiException.unprocessable("TOOL_NOT_REGISTERED", "力矩工具未登记: " + toolCode);
        }
        LocalDate todayUtc = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!tool.calibrationDate().isEqual(todayUtc)) {
            // 日校验逾期（或提前日期）使用直接拒绝。
            throw ApiException.unprocessable("TOOL_CALIBRATION_EXPIRED",
                    "力矩工具 " + toolCode + " 日校验日期 " + tool.calibrationDate()
                            + " 非当日(UTC " + todayUtc + ")，禁止使用");
        }
    }

    // ------------------------------------------------------------------
    // 自检互检
    // ------------------------------------------------------------------

    @Override
    public Outcome inspect(InspectionRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "INSPECTION", request, () -> {
            CardRow card = requireCard(request.cardNo());
            if (card.status() != CardStatus.TIGHTENED) {
                throw ApiException.unprocessable("CARD_NOT_TIGHTENED",
                        "力矩紧固未完成（当前 " + card.status() + "），不能自检互检");
            }
            if (request.selfInspector().equals(request.mutualInspector())) {
                throw ApiException.unprocessable("SELF_MUTUAL_SAME_PERSON",
                        "自检与互检须不同人员，同一人双签拒绝");
            }
            Instant now = clock.instant();
            try {
                inspectionRepository.insert(card.id(), request.selfInspector(),
                        request.mutualInspector(), request.requestId(), now);
            } catch (DuplicateKeyException duplicate) {
                throw ApiException.conflict("INSPECTION_EXISTS", "该工序卡已完成自检互检");
            }
            cardRepository.updateStatus(card.id(), CardStatus.INSPECTED);
            card = requireCard(card.cardNo());
            return Outcome.of(200, new ActionResult("自检互检完成", card.cardNo(), card.status().name()));
        });
    }

    // ------------------------------------------------------------------
    // H 点见证
    // ------------------------------------------------------------------

    @Override
    public Outcome witness(WitnessRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "WITNESS", request, () -> {
            CardRow card = requireCard(request.cardNo());
            if (!"H".equals(card.witnessPoint())) {
                throw ApiException.unprocessable("NOT_H_POINT", "非 H 点工序，无需见证");
            }
            if (card.status() != CardStatus.INSPECTED
                    && card.status() != CardStatus.WAITING_WITNESS
                    && card.status() != CardStatus.ESCALATED) {
                throw ApiException.unprocessable("INSPECTION_REQUIRED",
                        "自检互检未完成（当前 " + card.status() + "），不能发起见证");
            }
            Instant now = clock.instant();
            int seq = witnessRepository.countByCard(card.id()) + 1;

            if ("PRESENT".equals(request.action())) {
                if (request.witnessName() == null || request.witnessName().isBlank()) {
                    throw ApiException.badRequest("WITNESS_NAME_REQUIRED", "见证到场须提供见证人");
                }
                WitnessRepository.WitnessRow latest = witnessRepository.findLatest(card.id());
                if (latest != null && latest.status() == WitnessStatus.PRESENT) {
                    throw ApiException.conflict("WITNESS_ALREADY_PRESENT", "H 点已见证到场");
                }
                witnessRepository.append(card.id(), seq, WitnessStatus.PRESENT, request.witnessName(),
                        now, request.requestId(), "见证人到场签署");
                cardRepository.updateStatus(card.id(), CardStatus.WITNESSED);
                card = requireCard(card.cardNo());
                return Outcome.of(200, new WitnessResult(card.cardNo(), seq, WitnessStatus.PRESENT.name(),
                        request.witnessName(), card.status().name(), now, null, null, false, "见证人到场签署"));
            }

            // ABSENT：缺席 → 等待。起始时间取首次缺席时间（重复缺席不重置 2 小时计时）。
            WitnessRepository.WitnessRow latest = witnessRepository.findLatest(card.id());
            if (latest != null && (latest.status() == WitnessStatus.PRESENT)) {
                throw ApiException.conflict("WITNESS_ALREADY_PRESENT", "H 点已见证，不能再报缺席");
            }
            Instant waitingSince = findWaitingSince(card.id(), now);
            witnessRepository.append(card.id(), seq, WitnessStatus.ABSENT, null, now,
                    request.requestId(), "见证缺席");
            witnessRepository.append(card.id(), seq + 1, WitnessStatus.WAITING, null, now,
                    request.requestId(), "见证缺席，转等待");
            cardRepository.updateStatusIfCurrent(card.id(), CardStatus.INSPECTED, CardStatus.WAITING_WITNESS);
            card = requireCard(card.cardNo());
            Instant escalateAt = waitingSince.plus(WITNESS_WAIT_LIMIT);
            return Outcome.of(200, new WitnessResult(card.cardNo(), seq + 1, WitnessStatus.WAITING.name(),
                    null, card.status().name(), now, waitingSince, escalateAt, false,
                    "见证缺席，转等待；满 2 小时(UTC)升级"));
        });
    }

    private Instant findWaitingSince(long cardId, Instant fallback) {
        for (WitnessRepository.WitnessRow row : witnessRepository.listByCard(cardId)) {
            if (row.status() == WitnessStatus.ABSENT || row.status() == WitnessStatus.WAITING) {
                return row.eventTime();
            }
        }
        return fallback;
    }

    @org.springframework.transaction.annotation.Transactional
    @Override
    public Outcome checkEscalation(String cardNo) {
        CardRow card = requireCard(cardNo);
        Instant now = clock.instant();
        WitnessRepository.WitnessRow latest = witnessRepository.findLatest(card.id());
        if (latest == null) {
            throw ApiException.unprocessable("NO_WITNESS_EVENT", "尚无见证事件");
        }
        Instant waitingSince = findWaitingSince(card.id(), latest.eventTime());
        Instant escalateAt = waitingSince.plus(WITNESS_WAIT_LIMIT);
        boolean due = !now.isBefore(escalateAt); // 恰满 2 小时判定为升级

        if (card.status() == CardStatus.ESCALATED || latest.status() == WitnessStatus.ESCALATED) {
            return Outcome.of(200, new WitnessResult(card.cardNo(), latest.seq(),
                    WitnessStatus.ESCALATED.name(), null, card.status().name(), latest.eventTime(),
                    waitingSince, escalateAt, true, "等待满 2 小时，已升级"));
        }
        if (card.status() == CardStatus.WITNESSED || latest.status() == WitnessStatus.PRESENT) {
            return Outcome.of(200, new WitnessResult(card.cardNo(), latest.seq(),
                    latest.status().name(), latest.witnessName(), card.status().name(), latest.eventTime(),
                    null, null, false, "见证已到场，无需升级"));
        }
        if (!due) {
            return Outcome.of(200, new WitnessResult(card.cardNo(), latest.seq(),
                    latest.status().name(), null, card.status().name(), now,
                    waitingSince, escalateAt, false, "等待未满 2 小时，暂不升级"));
        }
        int seq = witnessRepository.countByCard(card.id()) + 1;
        // 以状态条件更新作为升级受理判据：并发调用只有一方成功并追加升级链事件。
        boolean won = cardRepository.updateStatusIfCurrent(
                card.id(), CardStatus.WAITING_WITNESS, CardStatus.ESCALATED);
        if (!won) {
            card = requireCard(card.cardNo());
            WitnessRepository.WitnessRow winner = witnessRepository.findLatest(card.id());
            return Outcome.of(200, new WitnessResult(card.cardNo(), winner.seq(),
                    WitnessStatus.ESCALATED.name(), null, card.status().name(), winner.eventTime(),
                    waitingSince, escalateAt, true, "等待满 2 小时，已升级"));
        }
        witnessRepository.append(card.id(), seq, WitnessStatus.ESCALATED, null, now,
                "SYSTEM_ESCALATION", "等待满 2 小时（含恰满边界），升级处理");
        card = requireCard(card.cardNo());
        return Outcome.of(200, new WitnessResult(card.cardNo(), seq, WitnessStatus.ESCALATED.name(),
                null, card.status().name(), now, waitingSince, escalateAt, true,
                "等待满 2 小时（含恰满边界），升级处理"));
    }

    // ------------------------------------------------------------------
    // 覆盖工序与放行归档
    // ------------------------------------------------------------------

    @Override
    public Outcome coverage(CoverageRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "COVERAGE", request, () -> {
            CardRow card = requireCard(request.cardNo());
            if ("H".equals(card.witnessPoint())) {
                if (card.status() != CardStatus.WITNESSED) {
                    // H 点未见证（缺席/等待/升级）不得进入覆盖工序。
                    throw ApiException.unprocessable("WITNESS_REQUIRED",
                            "H 点工序未见证到场（当前 " + card.status() + "），不得进入覆盖工序");
                }
            } else if (card.status() != CardStatus.INSPECTED
                    && card.status() != CardStatus.WITNESSED
                    && card.status() != CardStatus.ARCHIVED) {
                throw ApiException.unprocessable("INSPECTION_REQUIRED",
                        "自检互检未完成（当前 " + card.status() + "），不得进入覆盖工序");
            }
            return Outcome.of(200, new ActionResult(
                    "允许进入覆盖工序: " + request.nextOperation(), card.cardNo(), card.status().name()));
        });
    }

    @Override
    public Outcome release(ReleaseRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "RELEASE", request, () -> {
            CardRow card = requireCard(request.cardNo());
            // 终态重复归档：幂等成功（即使是新 requestId）。
            if (card.status() == CardStatus.ARCHIVED) {
                ReleaseRepository.ReleaseRow existing = releaseRepository.findByCard(card.id());
                return Outcome.of(200, new ActionResult(
                        "工序卡已归档（幂等），首次归档 requestId=" + existing.requestId(),
                        card.cardNo(), CardStatus.ARCHIVED.name()));
            }
            if (card.status() == CardStatus.QUARANTINED) {
                throw ApiException.unprocessable("CARD_QUARANTINED", "整批隔离评审中，不得放行归档");
            }
            // 工序归档前全部采集会话必须已关闭。
            int openSessions = acquisitionRepository.countOpenByCard(card.cardNo());
            if (openSessions > 0) {
                throw ApiException.unprocessable("ACQUISITION_SESSION_OPEN",
                        "存在 " + openSessions + " 个未关闭的采集会话，不得放行归档");
            }
            if ("H".equals(card.witnessPoint())) {
                if (card.status() != CardStatus.WITNESSED) {
                    throw ApiException.unprocessable("WITNESS_REQUIRED",
                            "H 点未见证到场（当前 " + card.status() + "），不得放行");
                }
            } else if (card.status() != CardStatus.INSPECTED) {
                throw ApiException.unprocessable("INSPECTION_REQUIRED",
                        "自检互检未完成（当前 " + card.status() + "），不得放行");
            }
            Instant now = clock.instant();
            releaseRepository.insert(card.id(), request.releasedBy(), request.requestId(), now);
            cardRepository.markArchived(card.id(), now);
            cardRepository.releaseGuard(card.station());
            return Outcome.of(200, new ActionResult("放行归档完成（终态）",
                    request.cardNo(), CardStatus.ARCHIVED.name()));
        });
    }

    // ------------------------------------------------------------------
    // 查询与复算
    // ------------------------------------------------------------------

    @Override
    public CardView getCard(String cardNo) {
        return toView(requireCard(cardNo));
    }

    @Override
    public TraceView trace(String cardNo) {
        CardRow card = requireCard(cardNo);
        List<FastenerRow> fasteners = cardRepository.listFasteners(card.id());
        java.util.Map<Long, String> pointNames = new java.util.HashMap<>();
        for (FastenerRow f : fasteners) {
            pointNames.put(f.id(), f.pointCode());
        }

        List<TraceView.TorquePoint> curve = new ArrayList<>();
        for (TorqueRepository.TorqueRow t : torqueRepository.listTorqueByCard(card.id())) {
            FastenerRow f = fasteners.stream()
                    .filter(x -> x.id() == t.fastenerId()).findFirst().orElse(null);
            curve.add(new TraceView.TorquePoint(
                    pointNames.getOrDefault(t.fastenerId(), "?"),
                    t.attemptNo(),
                    f == null ? null : f.nominalTorque(),
                    t.measuredTorque(), t.deviationPct(), t.inTolerance(),
                    t.operator(), t.toolCode(), t.measuredAt()));
        }

        List<TraceView.WitnessPoint> witnessChain = new ArrayList<>();
        for (WitnessRepository.WitnessRow w : witnessRepository.listByCard(card.id())) {
            witnessChain.add(new TraceView.WitnessPoint(
                    w.seq(), w.status().name(), w.witnessName(), w.eventTime(), w.note()));
        }

        List<TraceView.ReworkPoint> reworkLedger = new ArrayList<>();
        for (TorqueRepository.ReworkRow r : torqueRepository.listReworksByCard(card.id())) {
            reworkLedger.add(new TraceView.ReworkPoint(
                    pointNames.getOrDefault(r.fastenerId(), "?"),
                    r.reworkNo(), r.requestId(), r.reason(), r.createdAt()));
        }
        return new TraceView(card.cardNo(), card.status().name(), curve, witnessChain, reworkLedger);
    }

    @Override
    public ActionResult registerTool(ToolRegisterRequest request) {
        toolRepository.upsert(request.toolCode(), request.calibrationDate());
        return new ActionResult("力矩工具已登记日校验日期 " + request.calibrationDate()
                + "（UTC，仅当日有效）", request.toolCode(), "TOOL_REGISTERED");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private CardRow requireCard(String cardNo) {
        CardRow card = cardRepository.findByCardNo(cardNo);
        if (card == null) {
            throw ApiException.notFound("CARD_NOT_FOUND", "工序卡不存在: " + cardNo);
        }
        return card;
    }

    private CardView toView(CardRow card) {
        return new CardView(card.cardNo(), card.station(), card.productSn(), card.witnessPoint(),
                card.status().name(), card.operator(), card.dispatchedAt(),
                card.quarantinedAt(), card.archivedAt());
    }
}
