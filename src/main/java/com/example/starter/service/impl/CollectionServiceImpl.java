package com.example.starter.service.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.api.dto.CollectionBatchRequest;
import com.example.starter.api.dto.CollectionBatchResult;
import com.example.starter.api.dto.CollectionCloseRequest;
import com.example.starter.api.dto.CollectionPacketRequest;
import com.example.starter.api.dto.CollectionPacketView;
import com.example.starter.api.dto.CollectionSessionRequest;
import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionWatermarkView;
import com.example.starter.domain.CardStatus;
import com.example.starter.domain.CollectionStatus;
import com.example.starter.domain.PacketState;
import com.example.starter.domain.TorqueMath;
import com.example.starter.persistence.CardRepository;
import com.example.starter.persistence.CardRow;
import com.example.starter.persistence.CollectionRepository;
import com.example.starter.persistence.FastenerRow;
import com.example.starter.persistence.ToolRepository;
import com.example.starter.persistence.TorqueRepository;
import com.example.starter.service.CollectionService;
import com.example.starter.service.IdempotencyExecutor;
import com.example.starter.service.Outcome;
import com.example.starter.support.ApiException;

/**
 * 本地力矩采集会话核心服务：连续水位线、乱序缓冲窗口与归档前置校验。
 *
 * <p>序号 2 先到、序号 1 未到时仅缓冲 2，不产生力矩记录、不算进见证放行；水位线只推进到
 * 连续完整前缀。原始包、力矩记录、返工台账、水位线与幂等台账在同一事务提交，任一校验失败整批回滚。
 */
@Service
public class CollectionServiceImpl implements CollectionService {

    /** 仅缓冲水位线之后第 1～20 号的乱序包。 */
    static final int BUFFER_WINDOW = 20;
    /** 一次上传至多 20 包。 */
    static final int MAX_BATCH = 20;

    private final Clock clock;
    private final IdempotencyExecutor idempotencyExecutor;
    private final CollectionRepository collectionRepository;
    private final CardRepository cardRepository;
    private final ToolRepository toolRepository;
    private final TorqueRepository torqueRepository;

    public CollectionServiceImpl(Clock clock,
                                 IdempotencyExecutor idempotencyExecutor,
                                 CollectionRepository collectionRepository,
                                 CardRepository cardRepository,
                                 ToolRepository toolRepository,
                                 TorqueRepository torqueRepository) {
        this.clock = clock;
        this.idempotencyExecutor = idempotencyExecutor;
        this.collectionRepository = collectionRepository;
        this.cardRepository = cardRepository;
        this.toolRepository = toolRepository;
        this.torqueRepository = torqueRepository;
    }

    // ------------------------------------------------------------------
    // 创建采集会话
    // ------------------------------------------------------------------

    @Override
    public Outcome openSession(CollectionSessionRequest request) {
        if (request.expectedVersion() == null || request.expectedVersion() != 1) {
            throw ApiException.badRequest("EXPECTED_VERSION_INVALID", "新建采集会话的 expectedVersion 必须为 1");
        }
        return idempotencyExecutor.executeSuccessOnly(
                request.requestId(), "COLLECTION_OPEN", request, () -> {
                    CardRow card = requireCard(request.cardNo());
                    assertToolValidToday(request.toolCode());
                    List<String> distinctPoints = validateBoundPoints(request.pointCodes());
                    for (String pointCode : distinctPoints) {
                        if (cardRepository.findFastener(card.id(), pointCode) == null) {
                            throw ApiException.unprocessable("POINT_NOT_ON_CARD",
                                    "紧固点不存在于工序卡 " + card.cardNo() + ": " + pointCode);
                        }
                    }
                    Instant now = clock.instant();
                    ToolRepository.ToolRow tool = toolRepository.findByCode(request.toolCode());
                    long sessionId;
                    try {
                        sessionId = collectionRepository.insertSession(request.sessionNo(), card.id(),
                                request.toolCode(), tool.calibrationDate(), request.expectedCount(), now);
                    } catch (DuplicateKeyException sessionNoExists) {
                        throw ApiException.conflict("SESSION_NO_EXISTS", "采集会话号已存在: " + request.sessionNo());
                    }
                    for (String pointCode : distinctPoints) {
                        collectionRepository.insertSessionPoint(sessionId, pointCode);
                    }
                    return Outcome.of(201, toView(collectionRepository.findBySessionNo(request.sessionNo())));
                });
    }

    private List<String> validateBoundPoints(List<String> pointCodes) {
        if (pointCodes == null || pointCodes.isEmpty()) {
            throw ApiException.badRequest("POINTS_REQUIRED", "采集会话至少绑定一个紧固点");
        }
        List<String> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String pointCode : pointCodes) {
            if (pointCode == null || pointCode.isBlank()) {
                throw ApiException.badRequest("POINT_CODE_REQUIRED", "紧固点编号不能为空");
            }
            if (!seen.add(pointCode)) {
                throw ApiException.badRequest("DUPLICATE_BOUND_POINT", "绑定紧固点重复: " + pointCode);
            }
            distinct.add(pointCode);
        }
        return distinct;
    }

    // ------------------------------------------------------------------
    // 批量采集
    // ------------------------------------------------------------------

    @Override
    public Outcome collect(CollectionBatchRequest request) {
        return idempotencyExecutor.executeSuccessOnly(
                request.requestId(), "COLLECTION_BATCH", request, () -> doCollect(request));
    }

    private Outcome doCollect(CollectionBatchRequest request) {
        // 参数级校验（不占键，失败以 400 Outcome 返回）。
        if (request.packets() == null || request.packets().isEmpty()) {
            throw ApiException.badRequest("PACKETS_REQUIRED", "批量采集至少包含一个包");
        }
        if (request.packets().size() > MAX_BATCH) {
            throw ApiException.badRequest("BATCH_TOO_LARGE", "一次上传至多 " + MAX_BATCH + " 包");
        }

        // 会话行加写锁：同会话并发上传在此串行，相邻包不会重复消耗前缀或多算返工。
        CollectionRepository.SessionRow session = requireSessionForUpdate(request.sessionNo());
        CardRow card = requireCardById(session.cardId());

        // 批内静态校验：序号重复、非法字段任一错误整批拒绝。
        Set<Integer> batchSeqs = new HashSet<>();
        for (CollectionPacketRequest packet : request.packets()) {
            if (packet.seq() == null || packet.seq() < 1) {
                throw ApiException.badRequest("SEQ_INVALID", "采集序号必须为从 1 开始的正整数");
            }
            if (!batchSeqs.add(packet.seq())) {
                throw ApiException.badRequest("DUPLICATE_SEQ_IN_BATCH", "本批内序号重复: " + packet.seq());
            }
            if (packet.pointCode() == null || packet.pointCode().isBlank()) {
                throw ApiException.badRequest("POINT_CODE_REQUIRED", "第 " + packet.seq() + " 包缺少点号");
            }
            if (packet.measuredTorque() == null || packet.measuredTorque().signum() <= 0) {
                // JSON 数字无法反序列化为 NaN/Infinity（Jackson 直接拒绝），故正数即有限正数。
                throw ApiException.badRequest("TORQUE_INVALID",
                        "第 " + packet.seq() + " 包力矩必须为有限正数");
            }
            if (packet.measuredAt() == null) {
                throw ApiException.badRequest("MEASURED_AT_REQUIRED",
                        "第 " + packet.seq() + " 包缺少 UTC 测量时刻");
            }
        }
        Set<String> boundPoints = new HashSet<>(collectionRepository.listPointCodes(session.id()));

        // 已存在的非迟到序号判定（LATE 行是归档后独立证据汇，不参与）。
        TreeMap<Integer, CollectionRepository.PacketRow> existing = new TreeMap<>();
        for (CollectionRepository.PacketRow row : collectionRepository.listPackets(session.id())) {
            if (row.state() != PacketState.LATE) {
                existing.put(row.seq(), row);
            }
        }

        // 归档后包只记迟到，不复活卡片（优先于版本与会话开关状态）。
        if (card.status() == CardStatus.ARCHIVED) {
            return handleLateBatch(request, session, card, boundPoints);
        }

        // 同 requestId 的重放在幂等台账处直接冻结返回，不会执行到此处；
        // 以下处理的是新 requestId 的提交。终态/版本门禁优先：关闭后新提交一律拒绝。
        if (session.status() == CollectionStatus.CLOSED) {
            throw ApiException.unprocessable("SESSION_CLOSED",
                    "采集会话 " + session.sessionNo() + " 已关闭，不能再上传采集包");
        }
        if (request.expectedVersion() == null || request.expectedVersion() != session.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "expectedVersion=" + request.expectedVersion() + " 与当前版本 " + session.version() + " 不一致");
        }
        if (card.status() == CardStatus.QUARANTINED) {
            throw ApiException.unprocessable("CARD_QUARANTINED",
                    "工序卡已整批隔离评审，冻结受理力矩采集");
        }
        // 乱序缓冲的后续前缀包可能在卡片已达 TIGHTENED 后才按序到齐，仍须按序入账，
        // 故 DISPATCHED 与 TIGHTENED 均受理由采集会话驱动的测量。
        if (card.status() != CardStatus.DISPATCHED && card.status() != CardStatus.TIGHTENED) {
            throw ApiException.unprocessable("CARD_NOT_IN_TIGHTENING",
                    "当前状态 " + card.status() + " 不受理力矩采集");
        }

        // 同会话同序号同内容重放（新 requestId 的设备重发）：不追加记录、不推进版本。
        boolean allReplay = request.packets().stream().allMatch(p -> {
            CollectionRepository.PacketRow prior = existing.get(p.seq());
            return prior != null && sameContent(prior, p);
        });
        if (allReplay) {
            return Outcome.of(200, buildResult(request, session, session.watermark(),
                    List.of(), List.of(), List.of(), card, false, null));
        }

        // 未知点整批拒绝。
        for (CollectionPacketRequest packet : request.packets()) {
            if (!boundPoints.contains(packet.pointCode())) {
                throw ApiException.unprocessable("UNKNOWN_POINT",
                        "点号不在会话绑定清单内: " + packet.pointCode());
            }
        }

        // 已存在序号判定：异内容 409；混有新包或已存在同内容包（非整批重放）→ 整批拒绝。
        List<CollectionPacketRequest> newPackets = new ArrayList<>();
        for (CollectionPacketRequest packet : request.packets()) {
            CollectionRepository.PacketRow prior = existing.get(packet.seq());
            if (prior == null) {
                newPackets.add(packet);
            } else if (!sameContent(prior, packet)) {
                throw ApiException.conflict("PACKET_SEQ_CONFLICT",
                        "序号 " + packet.seq() + " 已有不同内容，拒绝追加");
            }
        }
        Instant now = clock.instant();
        if (newPackets.size() < request.packets().size()) {
            // 部分序号已存在（即使内容相同）：序号重复，整批拒绝，不先写新包。
            throw ApiException.conflict("PACKET_SEQ_EXISTS",
                    "本批包含已上传序号，序号重复，整批拒绝");
        }

        // 超窗校验：仅缓冲水位线之后第 1～20 号，且不得超出计划总数。
        int watermarkBefore = session.watermark();
        int windowLow = watermarkBefore + 1;
        int windowHigh = watermarkBefore + BUFFER_WINDOW;
        for (CollectionPacketRequest packet : newPackets) {
            if (packet.seq() > session.expectedCount()) {
                throw ApiException.unprocessable("PACKET_SEQ_BEYOND_PLAN",
                        "序号 " + packet.seq() + " 超出计划包总数 " + session.expectedCount() + "，整批拒绝");
            }
            if (packet.seq() < windowLow || packet.seq() > windowHigh) {
                throw ApiException.unprocessable("PACKET_OUT_OF_WINDOW",
                        "序号 " + packet.seq() + " 超出缓冲窗口 [" + windowLow + ", " + windowHigh
                                + "]（水位线 " + watermarkBefore + "），整批拒绝");
            }
        }

        // 原始包先全部落为缓冲（同事务），再按连续前缀统一入账，保证“先到 2 不放入账”。
        TreeMap<Integer, CollectionRepository.PacketRow> all = new TreeMap<>(existing);
        for (CollectionPacketRequest packet : newPackets) {
            long packetId;
            try {
                packetId = collectionRepository.insertPacket(new CollectionRepository.PacketRow(
                        0, session.id(), packet.seq(), packet.pointCode(), packet.measuredTorque(),
                        packet.measuredAt(), PacketState.BUFFERED, null,
                        request.requestId(), now, null));
            } catch (DuplicateKeyException seqRace) {
                throw ApiException.conflict("PACKET_SEQ_EXISTS",
                        "序号 " + packet.seq() + " 已存在（并发上传），整批拒绝");
            }
            CollectionRepository.PacketRow inserted = collectionRepository.findPacketById(packetId);
            all.put(packet.seq(), inserted == null
                    ? new CollectionRepository.PacketRow(packetId, session.id(), packet.seq(),
                    packet.pointCode(), packet.measuredTorque(), packet.measuredAt(),
                    PacketState.BUFFERED, null, request.requestId(), now, null)
                    : inserted);
        }

        // 从水位线下一序号起连续消费：旧缓冲与本批新包一视同仁。
        List<Integer> admittedSeqs = new ArrayList<>();
        Integer quarantineSeq = null;
        int newWatermark = watermarkBefore;
        int next = watermarkBefore + 1;
        while (all.containsKey(next)) {
            CollectionRepository.PacketRow packet = all.get(next);
            boolean becameQuarantined = admitPacket(session, card, packet, request.requestId(), now);
            admittedSeqs.add(next);
            newWatermark = next;
            if (becameQuarantined) {
                quarantineSeq = next;
                break;
            }
            next++;
        }

        boolean advanced = collectionRepository.advanceVersion(
                session.id(), session.version(), session.version() + 1, newWatermark);
        if (!advanced) {
            // 行锁内版本不应被他人改动；失败说明并发异常，整批回滚。
            throw ApiException.conflict("VERSION_CONFLICT", "版本推进失败，会话已被并发变更");
        }
        if (quarantineSeq == null) {
            // 全部紧固点最近一次测量均合格，且该卡不存在尚未采全（有缺号/缓冲未消费）的开放会话
            // → 力矩紧固阶段完成（沿用原状态机）；缺号包不参与自检/互检/见证放行。
            int fastenerCount = cardRepository.listFasteners(card.id()).size();
            if (!collectionRepository.existsIncompleteOpenByCard(card.id())
                    && torqueRepository.allFastenersLastAttemptPassing(card.id(), fastenerCount)) {
                cardRepository.updateStatusIfCurrent(card.id(), CardStatus.DISPATCHED, CardStatus.TIGHTENED);
            }
        }
        card = requireCardById(card.id());
        CollectionRepository.SessionRow refreshed = collectionRepository.findBySessionNo(session.sessionNo());

        List<Integer> newSeqList = newPackets.stream().map(CollectionPacketRequest::seq).sorted().toList();
        Set<Integer> admittedSet = new HashSet<>(admittedSeqs);
        List<Integer> bufferedNow = newSeqList.stream().filter(s -> !admittedSet.contains(s)).toList();
        return Outcome.of(200, buildResult(request, refreshed, watermarkBefore, admittedSeqs, bufferedNow,
                List.of(), card, quarantineSeq != null, quarantineSeq));
    }

    /**
     * 单个前缀包入账：按测量时刻(UTC)复核绑定工具当日校验有效，写力矩记录与返工台账。
     *
     * @return 该包（第 3 次测量仍超差）是否触发整批隔离；触发时后续前缀包停止消费、继续缓冲
     */
    private boolean admitPacket(CollectionRepository.SessionRow session, CardRow card,
                                CollectionRepository.PacketRow packet, String requestId, Instant admittedAt) {
        LocalDate measureDateUtc = packet.measuredAt().atZone(ZoneOffset.UTC).toLocalDate();
        ToolRepository.ToolRow tool = toolRepository.findByCode(session.toolCode());
        if (tool == null) {
            throw ApiException.unprocessable("TOOL_NOT_REGISTERED", "力矩工具未登记: " + session.toolCode());
        }
        if (!tool.calibrationDate().isEqual(measureDateUtc)) {
            throw ApiException.unprocessable("TOOL_CALIBRATION_EXPIRED",
                    "力矩工具 " + session.toolCode() + " 日校验日期 " + tool.calibrationDate()
                            + " 与测量当日(UTC " + measureDateUtc + ")不符，序号 " + packet.seq() + " 整批拒绝");
        }
        FastenerRow fastener = cardRepository.findFastener(card.id(), packet.pointCode());
        if (fastener == null) {
            throw ApiException.unprocessable("UNKNOWN_POINT", "紧固点不存在: " + packet.pointCode());
        }
        int attemptNo;
        int reworks;
        try {
            attemptNo = torqueRepository.countAttempts(card.id(), fastener.id()) + 1;
            reworks = torqueRepository.countReworks(card.id(), fastener.id());
        } catch (Exception e) {
            throw ApiException.conflict("TORQUE_ATTEMPT_RACE", "紧固点测量序号并发冲突，整批拒绝");
        }
        BigDecimal deviation = TorqueMath.deviationPct(fastener.nominalTorque(), packet.measuredTorque());
        boolean pass = TorqueMath.inTolerance(fastener.nominalTorque(), packet.measuredTorque(),
                fastener.tolerancePct());
        String syntheticOperator = "LOCAL-DEVICE:" + session.sessionNo();
        try {
            torqueRepository.insertTorque(new TorqueRepository.TorqueRow(
                    0, card.id(), fastener.id(), attemptNo, packet.measuredTorque(), deviation, pass,
                    syntheticOperator, session.toolCode(), packet.measuredAt(), requestId));
        } catch (DuplicateKeyException attemptRace) {
            // 其他会话/入口并发占用同一 attempt：整批回滚，不重复计返工。
            throw ApiException.conflict("TORQUE_ATTEMPT_RACE",
                    "紧固点 " + packet.pointCode() + " 测量序号 " + attemptNo + " 并发冲突，整批拒绝");
        }

        boolean quarantined = false;
        if (!pass) {
            if (reworks < OperationServiceImpl.MAX_REWORKS) {
                int reworkNo = reworks + 1;
                String reason = "实测 " + packet.measuredTorque() + " N·m，偏差 " + deviation
                        + "%，超出 ±" + fastener.tolerancePct() + "%（采集序号 " + packet.seq() + "）";
                torqueRepository.insertRework(new TorqueRepository.ReworkRow(
                        0, card.id(), fastener.id(), reworkNo, requestId, reason, admittedAt), admittedAt);
            } else {
                // 已返工恰 2 次后第 3 次测量仍超差：整批隔离（沿用原规则），后续前缀包不再消费。
                cardRepository.markQuarantined(card.id(), admittedAt);
                quarantined = true;
            }
        }
        collectionRepository.markPacketAdmitted(packet.id(), attemptNo, admittedAt);
        return quarantined;
    }

    private Outcome handleLateBatch(CollectionBatchRequest request,
                                    CollectionRepository.SessionRow session, CardRow card,
                                    Set<String> boundPoints) {
        // 归档后迟到是独立证据汇：仅与已有 LATE 行做重放/冲突判定；与已入账（ADMITTED）同序号可并存。
        TreeMap<Integer, CollectionRepository.PacketRow> lateBySeq = new TreeMap<>();
        Set<Integer> lateSeqsInBatch = new HashSet<>();
        for (CollectionRepository.PacketRow row : collectionRepository.listPacketsByState(
                session.id(), PacketState.LATE)) {
            lateBySeq.put(row.seq(), row);
        }
        boolean allReplay = true;
        for (CollectionPacketRequest packet : request.packets()) {
            if (!lateSeqsInBatch.add(packet.seq())) {
                throw ApiException.badRequest("DUPLICATE_SEQ_IN_BATCH",
                        "本批内序号重复: " + packet.seq());
            }
            if (packet.seq() > session.expectedCount()) {
                throw ApiException.unprocessable("PACKET_SEQ_BEYOND_PLAN",
                        "序号 " + packet.seq() + " 超出计划包总数 " + session.expectedCount() + "，整批拒绝");
            }
            if (!boundPoints.contains(packet.pointCode())) {
                throw ApiException.unprocessable("UNKNOWN_POINT",
                        "点号不在会话绑定清单内: " + packet.pointCode());
            }
            CollectionRepository.PacketRow priorLate = lateBySeq.get(packet.seq());
            if (priorLate == null) {
                allReplay = false;
            } else if (!sameContent(priorLate, packet)) {
                throw ApiException.conflict("PACKET_SEQ_CONFLICT",
                        "迟到序号 " + packet.seq() + " 已有不同内容，拒绝追加");
            }
        }
        Instant now = clock.instant();
        List<Integer> lateSeqs = new ArrayList<>();
        if (!allReplay) {
            for (CollectionPacketRequest packet : request.packets()) {
                if (lateBySeq.containsKey(packet.seq())) {
                    // 批内混合新迟到包与已存在迟到包：序号重复，整批拒绝。
                    throw ApiException.conflict("PACKET_SEQ_EXISTS",
                            "迟到序号 " + packet.seq() + " 已登记，整批拒绝");
                }
                try {
                    collectionRepository.insertPacket(new CollectionRepository.PacketRow(
                            0, session.id(), packet.seq(), packet.pointCode(), packet.measuredTorque(),
                            packet.measuredAt(), PacketState.LATE, null, request.requestId(), now, null));
                } catch (DuplicateKeyException seqRace) {
                    throw ApiException.conflict("PACKET_SEQ_EXISTS",
                            "迟到序号 " + packet.seq() + " 并发冲突，整批拒绝");
                }
                // 只记迟到事件，不进入力矩 attempt 链，不复活已归档卡片。
                torqueRepository.insertLateEvent(card.id(), packet.pointCode(), packet.measuredTorque(),
                        "LOCAL-DEVICE:" + session.sessionNo(), session.toolCode(),
                        packet.measuredAt(), request.requestId());
                lateSeqs.add(packet.seq());
            }
            lateSeqs.sort(Integer::compareTo);
        }
        // 迟到包不变更（可能已关闭的）会话版本与水位线。
        return Outcome.of(200, buildResult(request, session, session.watermark(),
                List.of(), List.of(), lateSeqs, card, false, null));
    }

    private boolean sameContent(CollectionRepository.PacketRow prior, CollectionPacketRequest packet) {
        return prior.pointCode().equals(packet.pointCode())
                && prior.measuredTorque().compareTo(packet.measuredTorque()) == 0
                && prior.measuredAt().equals(packet.measuredAt());
    }

    // ------------------------------------------------------------------
    // 关闭会话
    // ------------------------------------------------------------------

    @Override
    public Outcome close(CollectionCloseRequest request) {
        return idempotencyExecutor.executeSuccessOnly(
                request.requestId(), "COLLECTION_CLOSE", request, () -> {
                    CollectionRepository.SessionRow session = requireSessionForUpdate(request.sessionNo());
                    if (request.expectedVersion() == null || request.expectedVersion() != session.version()) {
                        throw ApiException.conflict("VERSION_CONFLICT",
                                "expectedVersion=" + request.expectedVersion() + " 与当前版本 "
                                        + session.version() + " 不一致");
                    }
                    if (session.status() == CollectionStatus.CLOSED) {
                        return Outcome.of(200, toView(session));
                    }
                    int buffered = collectionRepository.listPacketsByState(session.id(), PacketState.BUFFERED).size();
                    if (session.watermark() != session.expectedCount()) {
                        throw ApiException.unprocessable("SESSION_HAS_GAPS",
                                "存在缺号：水位线 " + session.watermark() + "，计划总数 "
                                        + session.expectedCount() + "，不能关闭");
                    }
                    if (buffered > 0) {
                        throw ApiException.unprocessable("BUFFER_NOT_DRAINED",
                                buffered + " 个缓冲包尚未随前缀消费，不能关闭");
                    }
                    Instant now = clock.instant();
                    boolean closed = collectionRepository.closeSession(
                            session.id(), session.version(), session.version() + 1, now);
                    if (!closed) {
                        throw ApiException.conflict("VERSION_CONFLICT", "会话关闭失败，状态或版本已并发变更");
                    }
                    return Outcome.of(200, toView(collectionRepository.findBySessionNo(request.sessionNo())));
                });
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public CollectionSessionView getSession(String sessionNo) {
        return toView(requireSession(sessionNo));
    }

    @Override
    public CollectionWatermarkView getWatermark(String sessionNo) {
        CollectionRepository.SessionRow session = requireSession(sessionNo);
        List<Integer> bufferedSeqs = collectionRepository.listPacketsByState(session.id(), PacketState.BUFFERED)
                .stream().map(CollectionRepository.PacketRow::seq).sorted().toList();
        return new CollectionWatermarkView(session.sessionNo(), session.status().name(), session.version(),
                session.watermark(), session.watermark() + 1, session.expectedCount(),
                session.watermark() >= session.expectedCount(), bufferedSeqs);
    }

    @Override
    public List<CollectionPacketView> listPackets(String sessionNo) {
        CollectionRepository.SessionRow session = requireSession(sessionNo);
        return packetViews(session, collectionRepository.listPackets(session.id()));
    }

    private List<CollectionPacketView> packetViews(CollectionRepository.SessionRow session,
                                                   List<CollectionRepository.PacketRow> rows) {
        // 入账证据补充：通过同卡力矩记录（点号 + attempt）取合格标记，通过返工台账取返工序号。
        List<FastenerRow> fasteners = cardRepository.listFasteners(session.cardId());
        Map<Long, String> pointByFastenerId = new HashMap<>();
        for (FastenerRow f : fasteners) {
            pointByFastenerId.put(f.id(), f.pointCode());
        }
        Map<String, TorqueRepository.TorqueRow> torqueByPointAttempt = new HashMap<>();
        for (TorqueRepository.TorqueRow t : torqueRepository.listTorqueByCard(session.cardId())) {
            String pointCode = pointByFastenerId.get(t.fastenerId());
            if (pointCode != null) {
                torqueByPointAttempt.put(pointCode + "#" + t.attemptNo(), t);
            }
        }
        Map<String, Integer> reworkNoByPointRequest = new HashMap<>();
        for (TorqueRepository.ReworkRow r : torqueRepository.listReworksByCard(session.cardId())) {
            String pointCode = pointByFastenerId.get(r.fastenerId());
            if (pointCode != null) {
                reworkNoByPointRequest.put(pointCode + "#" + r.requestId(), r.reworkNo());
            }
        }
        List<CollectionPacketView> views = new ArrayList<>();
        for (CollectionRepository.PacketRow p : rows) {
            Boolean inTolerance = null;
            Integer reworkNo = null;
            if (p.state() == PacketState.ADMITTED && p.attemptNo() != null) {
                TorqueRepository.TorqueRow torque =
                        torqueByPointAttempt.get(p.pointCode() + "#" + p.attemptNo());
                if (torque != null) {
                    inTolerance = torque.inTolerance();
                }
                reworkNo = reworkNoByPointRequest.get(p.pointCode() + "#" + p.requestId());
            }
            views.add(new CollectionPacketView(p.seq(), p.pointCode(), p.measuredTorque(), p.measuredAt(),
                    p.state().name(), p.attemptNo(), inTolerance, reworkNo,
                    p.requestId(), p.receivedAt(), p.admittedAt()));
        }
        return views;
    }

    private CollectionBatchResult buildResult(CollectionBatchRequest request,
                                              CollectionRepository.SessionRow session,
                                              int watermarkBefore,
                                              List<Integer> admittedSeqs,
                                              List<Integer> bufferedSeqs,
                                              List<Integer> lateSeqs,
                                              CardRow card,
                                              boolean quarantined,
                                              Integer quarantineSeq) {
        List<CollectionRepository.PacketRow> rows = new ArrayList<>();
        boolean lateBatch = !lateSeqs.isEmpty() && admittedSeqs.isEmpty() && bufferedSeqs.isEmpty();
        Map<Integer, CollectionRepository.PacketRow> bySeq = new HashMap<>();
        for (CollectionRepository.PacketRow row : collectionRepository.listPackets(session.id())) {
            CollectionRepository.PacketRow current = bySeq.get(row.seq());
            if (current == null
                    || (lateBatch ? row.state() == PacketState.LATE : row.state() != PacketState.LATE)) {
                bySeq.put(row.seq(), row);
            }
        }
        for (CollectionPacketRequest p : request.packets()) {
            CollectionRepository.PacketRow row = bySeq.get(p.seq());
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort((a, b) -> Integer.compare(a.seq(), b.seq()));
        List<CollectionPacketView> packetViews = packetViews(session, rows);
        return new CollectionBatchResult(session.sessionNo(), session.version(),
                watermarkBefore, session.watermark(),
                List.copyOf(admittedSeqs), List.copyOf(bufferedSeqs), List.copyOf(lateSeqs),
                card.status().name(), quarantined, quarantineSeq, packetViews);
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

    private CardRow requireCardById(long cardId) {
        CardRow card = cardRepository.findById(cardId);
        if (card == null) {
            throw ApiException.notFound("CARD_NOT_FOUND", "工序卡不存在: id=" + cardId);
        }
        return card;
    }

    private CollectionRepository.SessionRow requireSession(String sessionNo) {
        CollectionRepository.SessionRow session = collectionRepository.findBySessionNo(sessionNo);
        if (session == null) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "采集会话不存在: " + sessionNo);
        }
        return session;
    }

    private CollectionRepository.SessionRow requireSessionForUpdate(String sessionNo) {
        CollectionRepository.SessionRow session = collectionRepository.findBySessionNoForUpdate(sessionNo);
        if (session == null) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "采集会话不存在: " + sessionNo);
        }
        return session;
    }

    private void assertToolValidToday(String toolCode) {
        ToolRepository.ToolRow tool = toolRepository.findByCode(toolCode);
        if (tool == null) {
            throw ApiException.unprocessable("TOOL_NOT_REGISTERED", "力矩工具未登记: " + toolCode);
        }
        LocalDate todayUtc = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!tool.calibrationDate().isEqual(todayUtc)) {
            throw ApiException.unprocessable("TOOL_CALIBRATION_EXPIRED",
                    "力矩工具 " + toolCode + " 日校验日期 " + tool.calibrationDate()
                            + " 非当日(UTC " + todayUtc + ")，禁止绑定");
        }
    }

    private CollectionSessionView toView(CollectionRepository.SessionRow session) {
        List<CollectionRepository.PacketRow> packets = collectionRepository.listPackets(session.id());
        int admitted = 0;
        int buffered = 0;
        int late = 0;
        List<Integer> bufferedSeqs = new ArrayList<>();
        for (CollectionRepository.PacketRow p : packets) {
            switch (p.state()) {
                case ADMITTED -> admitted++;
                case BUFFERED -> {
                    buffered++;
                    bufferedSeqs.add(p.seq());
                }
                case LATE -> late++;
            }
        }
        bufferedSeqs.sort(Integer::compareTo);
        CardRow card = cardRepository.findById(session.cardId());
        String cardNo = card == null ? String.valueOf(session.cardId()) : card.cardNo();
        return new CollectionSessionView(session.sessionNo(), cardNo, session.toolCode(),
                session.calibrationDate(), session.status().name(), session.version(), session.watermark(),
                session.expectedCount(), admitted, buffered, late, bufferedSeqs,
                session.createdAt(), session.closedAt());
    }
}
