package com.example.starter.torque;

import com.example.starter.torque.domain.FastenerSpecRow;
import com.example.starter.torque.domain.InspectionRow;
import com.example.starter.torque.domain.LateMeasurementRow;
import com.example.starter.torque.domain.ReworkRow;
import com.example.starter.torque.domain.TighteningRow;
import com.example.starter.torque.domain.WitnessEventRow;
import com.example.starter.torque.domain.WorkCardRow;
import com.example.starter.torque.dto.CardView;
import com.example.starter.torque.dto.DispatchRequest;
import com.example.starter.torque.dto.InspectionRequest;
import com.example.starter.torque.dto.InspectionResult;
import com.example.starter.torque.dto.ReleaseRequest;
import com.example.starter.torque.dto.ReleaseResult;
import com.example.starter.torque.dto.TighteningRequest;
import com.example.starter.torque.dto.TighteningResult;
import com.example.starter.torque.dto.ToolRegisterRequest;
import com.example.starter.torque.dto.ToolView;
import com.example.starter.torque.dto.WitnessRequest;
import com.example.starter.torque.dto.WitnessResult;
import com.example.starter.torque.idempotency.IdempotencyStore;
import com.example.starter.torque.repo.TorqueRepository;
import com.example.starter.torque.rule.TorqueTolerance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 航空装配力矩紧固与见证点控制服务。
 *
 * <p>流转：派工(DISPATCHED)→力矩紧固→自检(SELF_CHECK)→互检(MUTUAL_CHECK)
 * →H 点见证(WITNESS_PENDING/WAITING/ESCALATED)→可覆盖(COVERING)→放行归档(RELEASED)。
 * 同一紧固点返工 2 次后仍超差则整批隔离评审(QUARANTINED)。</p>
 *
 * <p>写操作均以 requestId 幂等：指纹覆盖 requestId、路径 cardNo 与全部业务字段；
 * 业务写入与幂等记录在同一事务内提交，失败整体回滚。</p>
 */
@Service
public class TorqueService {

    static final String TYPE_DISPATCH = "DISPATCH";
    static final String TYPE_TOOL = "TOOL_REGISTER";
    static final String TYPE_TIGHTEN = "TIGHTEN";
    static final String TYPE_INSPECTION = "INSPECTION";
    static final String TYPE_WITNESS = "WITNESS";
    static final String TYPE_RELEASE = "RELEASE";

    /** 见证缺席等待满 2 小时升级（恰满 2 小时判定）。 */
    static final Duration WITNESS_WAIT = Duration.ofHours(2);

    /** 同一紧固点最多返工次数；恰 2 次合法，第 3 次测量仍超差即隔离。 */
    static final int MAX_REWORK = 2;

    private final TorqueRepository repo;
    private final IdempotencyStore idempotency;
    private final ServiceClock clock;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public TorqueService(TorqueRepository repo,
                         IdempotencyStore idempotency,
                         ServiceClock clock,
                         org.springframework.transaction.PlatformTransactionManager txManager,
                         ObjectMapper objectMapper) {
        this.repo = repo;
        this.idempotency = idempotency;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 派工
    // ------------------------------------------------------------------

    public CardView dispatch(DispatchRequest request) {
        return executeIdempotent(request.requestId(), TYPE_DISPATCH, null, request,
                HttpStatus.CREATED.value(), CardView.class, () -> doDispatch(request));
    }

    private CardView doDispatch(DispatchRequest request) {
        Instant now = clock.now();
        WorkCardRow card = new WorkCardRow(0L, request.cardNo(), request.station(),
                request.productNo(), CardStatus.DISPATCHED.name(), now, null, null, 0);
        try {
            repo.insertWorkCard(card);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ILLEGAL_STATE,
                    "工序卡编号已存在: " + request.cardNo());
        }
        WorkCardRow saved = repo.getWorkCard(request.cardNo());

        for (DispatchRequest.FastenerSpecDto spec : request.fasteners()) {
            repo.insertFastener(saved.id(), spec.pointCode(), spec.nominalTorque(), spec.hPoint());
        }

        // 同一工位同时刻只允许一张执行中工序卡；唯一约束兜底并发派工。
        boolean occupied = repo.occupyStation(request.station(), saved.id(), now);
        if (!occupied) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.STATION_OCCUPIED,
                    "工位 " + request.station() + " 已有执行中工序卡");
        }
        return toView(saved);
    }

    // ------------------------------------------------------------------
    // 力矩工具登记 / 日校验
    // ------------------------------------------------------------------

    public ToolView registerTool(ToolRegisterRequest request) {
        repo.upsertTool(request.toolNo(), request.lastCheckUtcDate());
        boolean valid = !request.lastCheckUtcDate().isBefore(clock.todayUtc());
        return new ToolView(request.toolNo(), request.lastCheckUtcDate(), valid, clock.now());
    }

    // ------------------------------------------------------------------
    // 力矩紧固
    // ------------------------------------------------------------------

    public TighteningResult tighten(String cardNo, TighteningRequest request) {
        return executeIdempotent(request.requestId(), TYPE_TIGHTEN, cardNo, request,
                HttpStatus.OK.value(), TighteningResult.class, () -> doTighten(cardNo, request));
    }

    private TighteningResult doTighten(String cardNo, TighteningRequest request) {
        WorkCardRow card = requireCard(cardNo);
        Instant now = clock.now();

        // 归档为终态：迟到测量只记迟到事件，不追加紧固链、不改变工序状态。
        if (CardStatus.RELEASED.name().equals(card.status())) {
            FastenerSpecRow spec = repo.findFastener(card.id(), request.pointCode())
                    .orElseThrow(() -> notFound("紧固点不存在: " + request.pointCode()));
            repo.insertLateMeasurement(new LateMeasurementRow(0L, card.id(), request.pointCode(),
                    request.measuredTorque(), request.toolNo(), request.operator(), now, now));
            boolean within = TorqueTolerance.isWithin(request.measuredTorque(), spec.nominalTorque());
            return new TighteningResult(cardNo, request.pointCode(),
                    repo.listTightening(card.id(), request.pointCode()).size() + 1,
                    request.measuredTorque(),
                    TorqueTolerance.lowerBound(spec.nominalTorque()),
                    TorqueTolerance.upperBound(spec.nominalTorque()),
                    within, false, false, true, card.status(), now);
        }
        if (CardStatus.QUARANTINED.name().equals(card.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "工序卡已整批隔离评审，禁止继续紧固");
        }
        if (!CardStatus.DISPATCHED.name().equals(card.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "当前状态 " + card.status() + " 不允许力矩紧固");
        }

        FastenerSpecRow spec = repo.findFastener(card.id(), request.pointCode())
                .orElseThrow(() -> notFound("紧固点不存在: " + request.pointCode()));

        // 工具日校验当日（UTC 日界）有效，逾期或未登记直接拒绝。
        var tool = repo.findTool(request.toolNo())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ErrorCodes.TOOL_EXPIRED, "力矩工具未登记或当日校验失效: " + request.toolNo()));
        if (tool.lastCheckUtcDate().isBefore(clock.todayUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.TOOL_EXPIRED,
                    "力矩工具 " + request.toolNo() + " 日校验已逾期，禁止使用");
        }

        List<TighteningRow> chain = repo.listTightening(card.id(), request.pointCode());
        if (!chain.isEmpty() && chain.get(chain.size() - 1).withinTolerance()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "紧固点 " + request.pointCode() + " 已合格，禁止重复紧固");
        }
        int attemptNo = chain.size() + 1;
        BigDecimal nominal = spec.nominalTorque();
        boolean within = TorqueTolerance.isWithin(request.measuredTorque(), nominal);

        // 力矩记录链只追加，返工不覆盖原始记录。
        repo.insertTightening(new TighteningRow(0L, card.id(), request.pointCode(), attemptNo,
                request.measuredTorque(), nominal, within,
                request.toolNo(), request.operator(), now, false));

        boolean reworkRequired = false;
        boolean quarantined = false;

        if (!within) {
            int priorReworks = repo.countReworks(card.id(), request.pointCode());
            int newReworkNo = priorReworks + 1;
            if (newReworkNo > MAX_REWORK) {
                // 第 2 次返工之后的测量仍超差：整批隔离评审（终态）。
                repo.markQuarantined(card.id(), now);
                repo.releaseStation(card.id());
                quarantined = true;
            } else {
                repo.insertRework(new ReworkRow(0L, card.id(), request.pointCode(), newReworkNo,
                        "力矩超差：实测 " + request.measuredTorque() + "N·m 超出标称 "
                                + nominal + "N·m 的 ±5%", now));
                reworkRequired = true;
            }
        }

        String finalStatus = card.status();
        if (quarantined) {
            finalStatus = CardStatus.QUARANTINED.name();
        } else {
            int totalRework = repo.listReworks(card.id()).size();
            if (totalRework != card.currentRework()) {
                repo.updateReworkCount(card.id(), totalRework);
            }
            List<FastenerSpecRow> specs = repo.listFasteners(card.id());
            if (repo.countPassedPoints(card.id()) == specs.size()) {
                // 全部紧固点最新一次测量合格，转入自检。
                repo.updateStatus(card.id(), CardStatus.SELF_CHECK.name());
                finalStatus = CardStatus.SELF_CHECK.name();
            }
        }

        return new TighteningResult(cardNo, request.pointCode(), attemptNo, request.measuredTorque(),
                TorqueTolerance.lowerBound(nominal), TorqueTolerance.upperBound(nominal),
                within, reworkRequired, quarantined, false, finalStatus, now);
    }

    // ------------------------------------------------------------------
    // 自检 / 互检
    // ------------------------------------------------------------------

    public InspectionResult inspect(String cardNo, InspectionRequest request) {
        return executeIdempotent(request.requestId(), TYPE_INSPECTION, cardNo, request,
                HttpStatus.OK.value(), InspectionResult.class, () -> doInspect(cardNo, request));
    }

    private InspectionResult doInspect(String cardNo, InspectionRequest request) {
        WorkCardRow card = requireCard(cardNo);
        String signType = request.signType();
        if (!"SELF".equals(signType) && !"MUTUAL".equals(signType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                    "signType 仅支持 SELF 或 MUTUAL");
        }

        if ("SELF".equals(signType)) {
            if (!CardStatus.SELF_CHECK.name().equals(card.status())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                        "当前状态 " + card.status() + " 不允许自检签署");
            }
            if (repo.findInspection(card.id(), "SELF").isPresent()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                        "自检已签署，不得重复签署");
            }
            repo.insertInspection(new InspectionRow(0L, card.id(), "SELF", request.inspector(), clock.now()));
            repo.updateStatus(card.id(), CardStatus.MUTUAL_CHECK.name());
            return new InspectionResult(cardNo, "SELF", request.inspector(), CardStatus.MUTUAL_CHECK.name());
        }

        // MUTUAL
        if (!CardStatus.MUTUAL_CHECK.name().equals(card.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "当前状态 " + card.status() + " 不允许互检签署");
        }
        InspectionRow selfSign = repo.findInspection(card.id(), "SELF")
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ErrorCodes.ILLEGAL_STATE, "自检未完成，不得互检"));
        if (selfSign.inspector().equals(request.inspector())) {
            // 自检与互检须不同人员，同一人双签拒绝。
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.SAME_INSPECTOR,
                    "自检与互检不得为同一人员: " + request.inspector());
        }
        if (repo.findInspection(card.id(), "MUTUAL").isPresent()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "互检已签署，不得重复签署");
        }
        repo.insertInspection(new InspectionRow(0L, card.id(), "MUTUAL", request.inspector(), clock.now()));

        List<FastenerSpecRow> specs = repo.listFasteners(card.id());
        boolean hasHPoint = specs.stream().anyMatch(FastenerSpecRow::hPoint);
        String nextStatus = hasHPoint ? CardStatus.WITNESS_PENDING.name() : CardStatus.COVERING.name();
        if (hasHPoint) {
            Instant now = clock.now();
            specs.stream().filter(FastenerSpecRow::hPoint).forEach(hp ->
                    repo.insertWitnessEvent(new WitnessEventRow(0L, card.id(), hp.pointCode(),
                            "REQUESTED", null, now, "互检完成，提请 H 点见证")));
        }
        repo.updateStatus(card.id(), nextStatus);
        return new InspectionResult(cardNo, "MUTUAL", request.inspector(), nextStatus);
    }

    // ------------------------------------------------------------------
    // H 点见证
    // ------------------------------------------------------------------

    public WitnessResult witness(String cardNo, WitnessRequest request) {
        return executeIdempotent(request.requestId(), TYPE_WITNESS, cardNo, request,
                HttpStatus.OK.value(), WitnessResult.class, () -> doWitness(cardNo, request));
    }

    private WitnessResult doWitness(String cardNo, WitnessRequest request) {
        WorkCardRow card = requireCard(cardNo);
        FastenerSpecRow point = repo.findFastener(card.id(), request.pointCode())
                .orElseThrow(() -> notFound("紧固点不存在: " + request.pointCode()));
        if (!point.hPoint()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.BAD_REQUEST,
                    "紧固点 " + request.pointCode() + " 不是 H 点，无需见证");
        }

        String status = card.status();
        if (CardStatus.RELEASED.name().equals(status) || CardStatus.QUARANTINED.name().equals(status)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "工序卡已终态 " + status + "，不接受见证");
        }
        if (List.of(CardStatus.DISPATCHED.name(), CardStatus.SELF_CHECK.name(),
                CardStatus.MUTUAL_CHECK.name(), CardStatus.COVERING.name()).contains(status)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "当前状态 " + status + " 不处理见证");
        }

        Instant now = clock.now();
        List<WitnessEventRow> pointEvents = repo.listWitnessEvents(card.id(), request.pointCode());
        boolean alreadyWitnessed = pointEvents.stream().anyMatch(e -> "WITNESSED".equals(e.eventType()));

        if (request.present()) {
            if (request.witness() == null || request.witness().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                        "见证到场时 witness 不能为空");
            }
            if (alreadyWitnessed) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                        "H 点 " + request.pointCode() + " 已见证完成");
            }
            // 见证到场（含升级后迟到见证）：H 点未见证不得进入覆盖工序。
            repo.insertWitnessEvent(new WitnessEventRow(0L, card.id(), request.pointCode(),
                    "WITNESSED", request.witness(), now, null));
            String newStatus = recomputeWitnessStatus(card.id());
            return new WitnessResult(cardNo, request.pointCode(), "WITNESSED", request.witness(),
                    CardStatus.ESCALATED.name().equals(status), null, now, newStatus);
        }

        // present=false：见证缺席。已见证点不得再缺席。
        if (alreadyWitnessed) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "H 点 " + request.pointCode() + " 已见证完成，不得再报缺席");
        }

        WitnessEventRow absentEvent = pointEvents.stream()
                .filter(e -> "ABSENT".equals(e.eventType()))
                .reduce((first, second) -> second).orElse(null);
        boolean escalatedBefore = pointEvents.stream().anyMatch(e -> "ESCALATED".equals(e.eventType()));

        if (absentEvent == null) {
            // 首次缺席：转等待。
            repo.insertWitnessEvent(new WitnessEventRow(0L, card.id(), request.pointCode(),
                    "ABSENT", null, now, "见证人缺席，转等待"));
            if (CardStatus.WITNESS_PENDING.name().equals(status)) {
                repo.updateStatus(card.id(), CardStatus.WAITING.name());
            }
            return new WitnessResult(cardNo, request.pointCode(), "ABSENT", null, false,
                    now.plus(WITNESS_WAIT), now, CardStatus.WAITING.name());
        }

        // 已在等待：恰满 2 小时判定升级；升级事件只插入一次。
        Instant waitUntil = absentEvent.eventAt().plus(WITNESS_WAIT);
        boolean justEscalated = false;
        if (!escalatedBefore && !now.isBefore(waitUntil)) {
            repo.insertWitnessEvent(new WitnessEventRow(0L, card.id(), request.pointCode(),
                    "ESCALATED", null, now, "见证缺席等待满 2 小时，升级处理"));
            escalatedBefore = true;
            justEscalated = true;
        }
        if (escalatedBefore && !CardStatus.ESCALATED.name().equals(status)) {
            repo.updateStatus(card.id(), CardStatus.ESCALATED.name());
            status = CardStatus.ESCALATED.name();
        }
        String eventType = escalatedBefore ? "ESCALATED" : "ABSENT";
        return new WitnessResult(cardNo, request.pointCode(), eventType, null,
                justEscalated, waitUntil, absentEvent.eventAt(), status);
    }

    /**
     * 按各 H 点事件链重算工序卡见证阶段状态：
     * 全部 WITNESSED → COVERING；任一 ESCALATED → ESCALATED；任一 ABSENT 等待 → WAITING；否则 WITNESS_PENDING。
     */
    private String recomputeWitnessStatus(long cardId) {
        List<FastenerSpecRow> hPoints = repo.listFasteners(cardId).stream()
                .filter(FastenerSpecRow::hPoint).toList();
        boolean anyEscalated = false;
        boolean anyWaiting = false;
        for (FastenerSpecRow hp : hPoints) {
            List<WitnessEventRow> events = repo.listWitnessEvents(cardId, hp.pointCode());
            boolean witnessed = events.stream().anyMatch(e -> "WITNESSED".equals(e.eventType()));
            if (witnessed) {
                continue;
            }
            if (events.stream().anyMatch(e -> "ESCALATED".equals(e.eventType()))) {
                anyEscalated = true;
            } else if (events.stream().anyMatch(e -> "ABSENT".equals(e.eventType()))) {
                anyWaiting = true;
            }
        }
        String resolved;
        if (hPoints.stream().allMatch(hp -> repo.latestWitnessEvent(cardId, hp.pointCode())
                .map(e -> "WITNESSED".equals(e.eventType())).orElse(false))) {
            resolved = CardStatus.COVERING.name();
        } else if (anyEscalated) {
            resolved = CardStatus.ESCALATED.name();
        } else if (anyWaiting) {
            resolved = CardStatus.WAITING.name();
        } else {
            resolved = CardStatus.WITNESS_PENDING.name();
        }
        repo.updateStatus(cardId, resolved);
        return resolved;
    }

    // ------------------------------------------------------------------
    // 放行归档（终态）
    // ------------------------------------------------------------------

    public ReleaseResult release(String cardNo, ReleaseRequest request) {
        // 终态短路：重复归档（不论是否相同 requestId）均幂等成功，沿用首次归档时间。
        WorkCardRow existing = requireCard(cardNo);
        if (CardStatus.RELEASED.name().equals(existing.status())) {
            return new ReleaseResult(cardNo, CardStatus.RELEASED.name(), true, existing.releasedAt());
        }
        return executeIdempotent(request.requestId(), TYPE_RELEASE, cardNo, request,
                HttpStatus.OK.value(), ReleaseResult.class, () -> doRelease(cardNo, request));
    }

    private ReleaseResult doRelease(String cardNo, ReleaseRequest request) {
        WorkCardRow card = requireCard(cardNo);

        // 终态重复归档：幂等成功，不改变归档时间。
        if (CardStatus.RELEASED.name().equals(card.status())) {
            return new ReleaseResult(cardNo, CardStatus.RELEASED.name(), true, card.releasedAt());
        }
        if (CardStatus.QUARANTINED.name().equals(card.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "工序卡已整批隔离评审，不得放行归档");
        }
        if (!CardStatus.COVERING.name().equals(card.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE,
                    "当前状态 " + card.status() + "：检验或 H 点见证未完成，不得进入覆盖工序与放行");
        }

        Instant now = clock.now();
        repo.markReleased(card.id(), CardStatus.RELEASED.name(), now);
        repo.releaseStation(card.id());
        return new ReleaseResult(cardNo, CardStatus.RELEASED.name(), false, now);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public CardView getCard(String cardNo) {
        return toView(requireCard(cardNo));
    }

    /**
     * 力矩曲线：按紧固点、尝试序号复算的只追加测量链；归档后迟到测量追加在链尾并标注 late=true，
     * 不覆盖原始记录、不改变工序状态。
     */
    public List<com.example.starter.torque.dto.TighteningRecordView> getTighteningChain(String cardNo) {
        WorkCardRow card = requireCard(cardNo);
        List<com.example.starter.torque.dto.TighteningRecordView> views = new java.util.ArrayList<>(
                repo.listAllTightening(card.id()).stream()
                        .map(r -> new com.example.starter.torque.dto.TighteningRecordView(
                                r.pointCode(), r.attemptNo(), r.measuredTorque(), r.nominalTorque(),
                                r.withinTolerance(), r.toolNo(), r.operator(), r.recordedAt(), r.late()))
                        .toList());
        java.util.Map<String, Long> countByPoint = views.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        com.example.starter.torque.dto.TighteningRecordView::pointCode,
                        java.util.stream.Collectors.counting()));
        repo.listLateMeasurements(card.id()).forEach(lm -> {
            long next = countByPoint.merge(lm.pointCode(), 1L, Long::sum);
            BigDecimal nominal = repo.findFastener(card.id(), lm.pointCode())
                    .map(com.example.starter.torque.domain.FastenerSpecRow::nominalTorque)
                    .orElse(lm.measuredTorque());
            views.add(new com.example.starter.torque.dto.TighteningRecordView(
                    lm.pointCode(), (int) next, lm.measuredTorque(), nominal,
                    TorqueTolerance.isWithin(lm.measuredTorque(), nominal),
                    lm.toolNo(), lm.operator(), lm.recordedAt(), true));
        });
        return views;
    }

    /** 见证链复算：REQUESTED→ABSENT→ESCALATED→WITNESSED。 */
    public List<com.example.starter.torque.dto.WitnessEventView> getWitnessChain(String cardNo) {
        WorkCardRow card = requireCard(cardNo);
        return repo.listAllWitnessEvents(card.id()).stream()
                .map(e -> new com.example.starter.torque.dto.WitnessEventView(
                        e.pointCode(), e.eventType(), e.witness(), e.eventAt(), e.detail()))
                .toList();
    }

    /** 返工台账复算。 */
    public List<com.example.starter.torque.dto.ReworkView> getReworkLedger(String cardNo) {
        WorkCardRow card = requireCard(cardNo);
        return repo.listReworks(card.id()).stream()
                .map(r -> new com.example.starter.torque.dto.ReworkView(
                        r.pointCode(), r.reworkNo(), r.reason(), r.createdAt()))
                .toList();
    }

    /** 归档后迟到测量事件复算。 */
    public List<com.example.starter.torque.dto.LateMeasurementView> getLateMeasurements(String cardNo) {
        WorkCardRow card = requireCard(cardNo);
        return repo.listLateMeasurements(card.id()).stream()
                .map(r -> new com.example.starter.torque.dto.LateMeasurementView(
                        r.pointCode(), r.measuredTorque(), r.toolNo(), r.operator(),
                        r.recordedAt(), r.receivedAt()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 幂等模板：业务写入与幂等落库同一事务
    // ------------------------------------------------------------------

    private <T> T executeIdempotent(String requestId, String type, String cardNo, Object payload,
                                    int httpStatus, Class<T> responseType, Supplier<T> action) {
        String fingerprint = idempotency.fingerprint(requestId, type, cardNo, payload);
        Optional<IdempotencyStore.StoredResponse> stored = idempotency.find(requestId, type);
        if (stored.isPresent()) {
            return replayOrConflict(stored.get(), fingerprint, responseType);
        }
        java.util.concurrent.atomic.AtomicBoolean loser = new java.util.concurrent.atomic.AtomicBoolean();
        T result;
        try {
            result = tx.execute(status -> {
                // 事务内二次检查：胜者可能已在本事务开启前提交（状态已推进），
                // 此时直接按并发败者回滚，由外层重读首次结果。
                if (idempotency.find(requestId, type).isPresent()) {
                    loser.set(true);
                    status.setRollbackOnly();
                    return null;
                }
                try {
                    T value = action.get();
                    boolean saved = idempotency.save(requestId, type, fingerprint,
                            toJson(value), httpStatus, clock.now());
                    if (!saved) {
                        // 同键并发受理：标记败者回滚，由外层重读首次结果。
                        loser.set(true);
                        status.setRollbackOnly();
                        return null;
                    }
                    return value;
                } catch (org.springframework.dao.DuplicateKeyException dup) {
                    // 可能撞业务唯一约束（如紧固尝试序号）或幂等键：均按并发受理回滚。
                    loser.set(true);
                    status.setRollbackOnly();
                    return null;
                } catch (RuntimeException ex) {
                    // 业务执行期间胜者可能已提交并推进状态：再查一次幂等记录，
                    // 若首次结果已存在则按并发败者处理；否则这是真实业务错误，原样抛出。
                    if (idempotency.find(requestId, type).isPresent()) {
                        loser.set(true);
                        status.setRollbackOnly();
                        return null;
                    }
                    throw ex;
                }
            });
        } catch (RuntimeException e) {
            if (loser.get() || idempotency.find(requestId, type).isPresent()) {
                result = null;
            } else {
                throw e;
            }
        }
        if (result != null) {
            return result;
        }
        // 败者事务已回滚：有界等待胜者提交后重读首次结果。
        IdempotencyStore.StoredResponse winner = waitForWinner(requestId, type);
        return replayOrConflict(winner, fingerprint, responseType);
    }

    private IdempotencyStore.StoredResponse waitForWinner(String requestId, String type) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Optional<IdempotencyStore.StoredResponse> winner = idempotency.find(requestId, type);
                if (winner.isPresent()) {
                    return winner.get();
                }
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                "相同 requestId 并发冲突且未观察到首次结果");
    }

    private <T> T replayOrConflict(IdempotencyStore.StoredResponse stored, String fingerprint,
                                   Class<T> responseType) {
        // 同键异参：409 且不改变工序状态。
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                    "相同 requestId 提交了不一致的业务参数");
        }
        try {
            return objectMapper.readValue(stored.responseJson(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("解析幂等首次响应失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化响应失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private WorkCardRow requireCard(String cardNo) {
        try {
            return repo.getWorkCard(cardNo);
        } catch (EmptyResultDataAccessException e) {
            throw notFound("工序卡不存在: " + cardNo);
        }
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }

    private CardView toView(WorkCardRow card) {
        return new CardView(card.cardNo(), card.station(), card.productNo(), card.status(),
                card.currentRework(), card.dispatchedAt(), card.releasedAt(), card.quarantinedAt());
    }

    /** 兼容 java.util.function.Supplier 的本地别名，避免 import 冲突。 */
    @FunctionalInterface
    private interface Supplier<T> {
        T get();
    }
}
