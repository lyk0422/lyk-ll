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

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.api.dto.AcquisitionBatchRequest;
import com.example.starter.api.dto.AcquisitionBatchResult;
import com.example.starter.api.dto.AcquisitionBatchView;
import com.example.starter.api.dto.AcquisitionPacketView;
import com.example.starter.api.dto.AcquisitionSessionCloseRequest;
import com.example.starter.api.dto.AcquisitionSessionCreateRequest;
import com.example.starter.api.dto.AcquisitionSessionView;
import com.example.starter.api.dto.AcquisitionWatermarkView;
import com.example.starter.domain.CardStatus;
import com.example.starter.domain.TorqueMath;
import com.example.starter.persistence.AcquisitionRepository;
import com.example.starter.persistence.CardRepository;
import com.example.starter.persistence.CardRow;
import com.example.starter.persistence.FastenerRow;
import com.example.starter.persistence.ToolRepository;
import com.example.starter.persistence.TorqueRepository;
import com.example.starter.service.AcquisitionService;
import com.example.starter.service.IdempotencyExecutor;
import com.example.starter.service.Outcome;
import com.example.starter.support.ApiException;
import com.example.starter.support.Fingerprints;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 本地力矩采集核心服务。
 *
 * <p>并发控制：同会话上传以 {@code SELECT ... FOR UPDATE} 锁定会话行串行执行，
 * 相邻包不会重复消耗前缀；{@code (session_id, seq)} 唯一约束兜底。
 * 乱序窗口：仅缓冲水位线之后第 1~20 号包；水位线只推进到连续完整前缀。
 * 前缀入账、原始包、力矩记录、返工台账与水位线在同一事务写入。
 */
@Service
public class AcquisitionServiceImpl implements AcquisitionService {

    /** 乱序缓冲窗口：水位线之后最多缓冲 20 号。 */
    static final int BUFFER_WINDOW = 20;
    /** 单次上传最大包数。 */
    static final int MAX_BATCH_PACKETS = 20;

    private final Clock clock;
    private final IdempotencyExecutor idempotencyExecutor;
    private final AcquisitionRepository acquisitionRepository;
    private final CardRepository cardRepository;
    private final TorqueRepository torqueRepository;
    private final ToolRepository toolRepository;
    private final ObjectMapper objectMapper;

    public AcquisitionServiceImpl(Clock clock,
                                  IdempotencyExecutor idempotencyExecutor,
                                  AcquisitionRepository acquisitionRepository,
                                  CardRepository cardRepository,
                                  TorqueRepository torqueRepository,
                                  ToolRepository toolRepository,
                                  ObjectMapper objectMapper) {
        this.clock = clock;
        this.idempotencyExecutor = idempotencyExecutor;
        this.acquisitionRepository = acquisitionRepository;
        this.cardRepository = cardRepository;
        this.torqueRepository = torqueRepository;
        this.toolRepository = toolRepository;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 创建会话
    // ------------------------------------------------------------------

    @Override
    public Outcome createSession(AcquisitionSessionCreateRequest request) {
        return idempotencyExecutor.executeTransient(request.requestId(), "ACQ_SESSION_CREATE", request, () -> {
            validatePointCodes(request.pointCodes());
            CardRow card = requireCard(request.cardNo());
            if (card.status() != CardStatus.DISPATCHED) {
                throw ApiException.unprocessable("CARD_NOT_IN_TIGHTENING",
                        "工序卡当前状态 " + card.status() + "，仅派工中工序可绑定采集会话");
            }
            ToolRepository.ToolRow tool = toolRepository.findByCode(request.toolCode());
            if (tool == null) {
                throw ApiException.unprocessable("TOOL_NOT_REGISTERED", "力矩工具未登记: " + request.toolCode());
            }
            for (String pointCode : request.pointCodes()) {
                if (cardRepository.findFastener(card.id(), pointCode) == null) {
                    throw ApiException.unprocessable("UNKNOWN_POINT",
                            "紧固点不属于工序卡工艺: " + pointCode);
                }
            }
            Instant now = clock.instant();
            long sessionId;
            try {
                // 绑定工具日校验版本快照；入账时按测量时刻校验该版本当日有效。
                sessionId = acquisitionRepository.insertSession(request.sessionNo(), request.cardNo(),
                        request.toolCode(), tool.calibrationDate(), now);
            } catch (DuplicateKeyException sessionNoExists) {
                throw ApiException.conflict("SESSION_NO_EXISTS", "采集会话号已存在: " + request.sessionNo());
            }
            for (String pointCode : request.pointCodes()) {
                acquisitionRepository.insertPoint(sessionId, pointCode);
            }
            return Outcome.of(201, toView(acquisitionRepository.findSession(request.sessionNo())));
        });
    }

    private void validatePointCodes(List<String> pointCodes) {
        if (pointCodes == null || pointCodes.isEmpty()) {
            throw ApiException.badRequest("POINTS_REQUIRED", "采集会话至少绑定一个紧固点");
        }
        Set<String> seen = new HashSet<>();
        for (String pointCode : pointCodes) {
            if (pointCode == null || pointCode.isBlank()) {
                throw ApiException.badRequest("POINT_BLANK", "紧固点编号不能为空");
            }
            if (!seen.add(pointCode)) {
                throw ApiException.badRequest("DUPLICATE_POINT", "绑定紧固点重复: " + pointCode);
            }
        }
    }

    // ------------------------------------------------------------------
    // 批量上传
    // ------------------------------------------------------------------

    /** 幂等指纹字段：路径中的会话号必须参与指纹，避免跨会话复用键。 */
    private record BatchFields(String sessionNo, AcquisitionBatchRequest request) {
    }

    @Override
    public Outcome uploadBatch(String sessionNo, AcquisitionBatchRequest request) {
        return idempotencyExecutor.executeTransient(request.requestId(), "ACQ_BATCH_UPLOAD",
                new BatchFields(sessionNo, request), () -> {
                    validateBatchShape(request);
                    return doUploadBatch(sessionNo, request);
                });
    }

    private void validateBatchShape(AcquisitionBatchRequest request) {
        if (request.expectedVersion() == null || request.expectedVersion() < 1) {
            throw ApiException.badRequest("VERSION_INVALID", "expectedVersion 须为从 1 开始的正整数");
        }
        List<AcquisitionBatchRequest.Packet> packets = request.packets();
        if (packets == null || packets.isEmpty() || packets.size() > MAX_BATCH_PACKETS) {
            throw ApiException.badRequest("BATCH_SIZE_INVALID",
                    "一次上传至多 " + MAX_BATCH_PACKETS + " 包且至少 1 包");
        }
        Set<Integer> seqs = new HashSet<>();
        for (AcquisitionBatchRequest.Packet packet : packets) {
            if (packet == null || packet.seq() < 1) {
                throw ApiException.badRequest("SEQ_INVALID", "序号须为从 1 开始的正整数");
            }
            if (packet.pointCode() == null || packet.pointCode().isBlank()
                    || packet.measuredAt() == null || packet.torque() == null
                    || packet.torque().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("PACKET_INVALID", "包须含点号、UTC 测量时刻与有限正力矩");
            }
            if (!seqs.add(packet.seq())) {
                throw ApiException.unprocessable("BATCH_DUPLICATE_SEQ",
                        "批内序号重复: " + packet.seq() + "，整批拒绝");
            }
        }
    }

    private Outcome doUploadBatch(String sessionNo, AcquisitionBatchRequest request) {
        // 锁定会话行：并发上传串行化，相邻包不会重复消耗前缀或多算返工。
        AcquisitionRepository.SessionRow session = acquisitionRepository.findSessionForUpdate(sessionNo);
        if (session == null) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "采集会话不存在: " + sessionNo);
        }
        if (request.expectedVersion() != session.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "会话版本冲突：期望 " + request.expectedVersion() + "，当前 " + session.version());
        }
        CardRow card = requireCard(session.cardNo());
        if (!"OPEN".equals(session.status())) {
            return uploadToClosedSession(session, card, request);
        }

        List<String> points = acquisitionRepository.listPoints(session.id());
        Set<String> pointSet = new HashSet<>(points);
        Instant now = clock.instant();

        // 第一遍：整批校验（未知点/超窗/异内容任一错误整批拒绝，不写任何记录）。
        List<AcquisitionRepository.PacketRow> toInsert = new ArrayList<>();
        int replayed = 0;
        for (AcquisitionBatchRequest.Packet packet : request.packets()) {
            AcquisitionRepository.PacketRow existing =
                    acquisitionRepository.findPacket(session.id(), packet.seq());
            if (existing != null) {
                // 同会话同序号：同内容重放不追加，异内容 409。
                if (!existing.contentHash().equals(contentHash(session.sessionNo(), packet))) {
                    throw ApiException.conflict("PACKET_CONTENT_CONFLICT",
                            "序号 " + packet.seq() + " 已存在不同内容，同序号异内容拒绝");
                }
                replayed++;
                continue;
            }
            if (!pointSet.contains(packet.pointCode())) {
                throw ApiException.unprocessable("UNKNOWN_POINT",
                        "点号不在会话绑定清单: " + packet.pointCode() + "，整批拒绝");
            }
            if (packet.seq() <= session.watermark()
                    || packet.seq() > session.watermark() + BUFFER_WINDOW) {
                throw ApiException.unprocessable("OUT_OF_WINDOW",
                        "序号 " + packet.seq() + " 超出缓冲窗口 (水位线 " + session.watermark()
                                + " 之后第 1~" + BUFFER_WINDOW + " 号)，整批拒绝");
            }
            toInsert.add(new AcquisitionRepository.PacketRow(0, session.id(), packet.seq(),
                    packet.pointCode(), packet.torque(), packet.measuredAt(), "BUFFERED",
                    contentHash(session.sessionNo(), packet), request.requestId(), now));
        }

        // 第二遍：落原始包（同事务，任一失败整批回滚）。
        for (AcquisitionRepository.PacketRow row : toInsert) {
            acquisitionRepository.insertPacket(row);
        }

        // 第三遍：前缀入账——水位线只推进到连续完整前缀；缺号包保持缓冲，
        // 不产生力矩记录，因而不参与自检、互检或见证放行。
        int watermark = session.watermark();
        int committed = 0;
        boolean quarantined = false;
        CardRow current = card;
        while (true) {
            AcquisitionRepository.PacketRow next =
                    acquisitionRepository.findPacket(session.id(), watermark + 1);
            if (next == null || !"BUFFERED".equals(next.status())) {
                break;
            }
            current = requireCard(session.cardNo());
            if (current.status() == CardStatus.QUARANTINED) {
                throw ApiException.unprocessable("CARD_QUARANTINED",
                        "工序卡已整批隔离评审，冻结采集入账");
            }
            if (current.status() != CardStatus.DISPATCHED) {
                throw ApiException.unprocessable("CARD_NOT_IN_TIGHTENING",
                        "工序卡当前状态 " + current.status() + "，不受理采集入账");
            }
            // 按测量时刻验证所绑定工具校验当天（UTC）有效。
            LocalDate measuredDay = LocalDate.ofInstant(next.measuredAt(), ZoneOffset.UTC);
            if (!session.calibrationDate().isEqual(measuredDay)) {
                throw ApiException.unprocessable("TOOL_CALIBRATION_EXPIRED",
                        "绑定工具 " + session.toolCode() + " 日校验版本 " + session.calibrationDate()
                                + " 在测量日(UTC " + measuredDay + ")无效，整批拒绝");
            }
            quarantined = commitPacket(session, current, next, request.requestId(), now);
            acquisitionRepository.markPacketCommitted(next.id());
            watermark++;
            committed++;
            if (quarantined) {
                // 超差沿用原返工与隔离规则：第 3 次测量仍超差整批隔离，冻结后续入账。
                break;
            }
        }

        if (!acquisitionRepository.advanceWatermark(session.id(), session.version(), watermark)) {
            throw ApiException.conflict("VERSION_CONFLICT", "会话版本并发变更，请重试");
        }
        acquisitionRepository.insertBatch(new AcquisitionRepository.BatchRow(0, request.requestId(),
                session.id(), request.packets().size(), toInsert.size(), replayed, committed, 0,
                watermark, null), now);
        AcquisitionRepository.SessionRow after = acquisitionRepository.findSession(sessionNo);
        String cardStatus = requireCard(session.cardNo()).status().name();
        AcquisitionBatchResult result = new AcquisitionBatchResult(sessionNo, request.requestId(),
                toInsert.size(), replayed, committed, 0, watermark, after.version(), cardStatus, quarantined);
        // 触发整批隔离：入账部分保留（沿用原返工隔离语义），以 422 告知。
        return Outcome.of(quarantined ? 422 : 200, result);
    }

    /**
     * 已关闭会话的上传：仅当工序卡已归档时受理，包只记迟到（LATE + 迟到事件），
     * 不复活卡片、不动力矩链与水位线；否则拒绝。
     */
    private Outcome uploadToClosedSession(AcquisitionRepository.SessionRow session, CardRow card,
                                          AcquisitionBatchRequest request) {
        if (card.status() != CardStatus.ARCHIVED) {
            throw ApiException.unprocessable("SESSION_CLOSED",
                    "采集会话已关闭，且工序卡未归档，不再受理上传");
        }
        List<String> points = acquisitionRepository.listPoints(session.id());
        Set<String> pointSet = new HashSet<>(points);
        Instant now = clock.instant();
        int replayed = 0;
        List<AcquisitionRepository.PacketRow> toInsert = new ArrayList<>();
        for (AcquisitionBatchRequest.Packet packet : request.packets()) {
            AcquisitionRepository.PacketRow existing =
                    acquisitionRepository.findPacket(session.id(), packet.seq());
            if (existing != null) {
                if (!existing.contentHash().equals(contentHash(session.sessionNo(), packet))) {
                    throw ApiException.conflict("PACKET_CONTENT_CONFLICT",
                            "序号 " + packet.seq() + " 已存在不同内容，同序号异内容拒绝");
                }
                replayed++;
                continue;
            }
            if (!pointSet.contains(packet.pointCode())) {
                throw ApiException.unprocessable("UNKNOWN_POINT",
                        "点号不在会话绑定清单: " + packet.pointCode() + "，整批拒绝");
            }
            toInsert.add(new AcquisitionRepository.PacketRow(0, session.id(), packet.seq(),
                    packet.pointCode(), packet.torque(), packet.measuredAt(), "LATE",
                    contentHash(session.sessionNo(), packet), request.requestId(), now));
        }
        for (AcquisitionRepository.PacketRow row : toInsert) {
            acquisitionRepository.insertPacket(row);
            // 归档后包只记迟到：登记迟到事件，不改变工序状态、不进入力矩 attempt 链。
            torqueRepository.insertLateEvent(card.id(), row.pointCode(), row.measuredTorque(),
                    "acq-" + session.sessionNo(), session.toolCode(), row.measuredAt(), request.requestId());
        }
        if (!acquisitionRepository.advanceWatermark(session.id(), session.version(), session.watermark())) {
            throw ApiException.conflict("VERSION_CONFLICT", "会话版本并发变更，请重试");
        }
        acquisitionRepository.insertBatch(new AcquisitionRepository.BatchRow(0, request.requestId(),
                session.id(), request.packets().size(), toInsert.size(), replayed, 0, toInsert.size(),
                session.watermark(), null), now);
        AcquisitionRepository.SessionRow after = acquisitionRepository.findSession(session.sessionNo());
        return Outcome.of(200, new AcquisitionBatchResult(session.sessionNo(), request.requestId(),
                toInsert.size(), replayed, 0, toInsert.size(), session.watermark(), after.version(),
                card.status().name(), false));
    }

    /**
     * 入账一个前缀包：追加力矩记录（attempt 链），超差沿用原返工与隔离规则。
     *
     * @return true 表示本次入账触发整批隔离评审
     */
    private boolean commitPacket(AcquisitionRepository.SessionRow session, CardRow card,
                                 AcquisitionRepository.PacketRow packet, String requestId, Instant now) {
        FastenerRow fastener = cardRepository.findFastener(card.id(), packet.pointCode());
        if (fastener == null) {
            throw ApiException.unprocessable("UNKNOWN_POINT",
                    "紧固点不属于工序卡工艺: " + packet.pointCode());
        }
        int attempts = torqueRepository.countAttempts(card.id(), fastener.id());
        int reworks = torqueRepository.countReworks(card.id(), fastener.id());
        int attemptNo = attempts + 1;
        BigDecimal deviation = TorqueMath.deviationPct(fastener.nominalTorque(), packet.measuredTorque());
        boolean pass = TorqueMath.inTolerance(fastener.nominalTorque(), packet.measuredTorque(),
                fastener.tolerancePct());
        String operator = "acq-" + session.sessionNo();
        torqueRepository.insertTorque(new TorqueRepository.TorqueRow(
                0, card.id(), fastener.id(), attemptNo, packet.measuredTorque(), deviation, pass,
                operator, session.toolCode(), packet.measuredAt(), requestId));
        if (!pass) {
            if (reworks < OperationServiceImpl.MAX_REWORKS) {
                String reason = "实测 " + packet.measuredTorque() + " N·m，偏差 " + deviation
                        + "%，超出 ±" + fastener.tolerancePct() + "%";
                torqueRepository.insertRework(new TorqueRepository.ReworkRow(
                        0, card.id(), fastener.id(), reworks + 1, requestId, reason, now), now);
            } else {
                cardRepository.markQuarantined(card.id(), now);
                return true;
            }
        }
        int fastenerCount = cardRepository.listFasteners(card.id()).size();
        if (torqueRepository.allFastenersLastAttemptPassing(card.id(), fastenerCount)) {
            cardRepository.updateStatusIfCurrent(card.id(), CardStatus.DISPATCHED, CardStatus.TIGHTENED);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 关闭会话
    // ------------------------------------------------------------------

    /** 幂等指纹字段：路径中的会话号参与指纹。 */
    private record CloseFields(String sessionNo, AcquisitionSessionCloseRequest request) {
    }

    @Override
    public Outcome closeSession(String sessionNo, AcquisitionSessionCloseRequest request) {
        return idempotencyExecutor.executeTransient(request.requestId(), "ACQ_SESSION_CLOSE",
                new CloseFields(sessionNo, request), () -> {
                    if (request.expectedVersion() == null || request.expectedVersion() < 1) {
                        throw ApiException.badRequest("VERSION_INVALID", "expectedVersion 须为从 1 开始的正整数");
                    }
                    AcquisitionRepository.SessionRow session =
                            acquisitionRepository.findSessionForUpdate(sessionNo);
                    if (session == null) {
                        throw ApiException.notFound("SESSION_NOT_FOUND", "采集会话不存在: " + sessionNo);
                    }
                    if (request.expectedVersion() != session.version()) {
                        throw ApiException.conflict("VERSION_CONFLICT",
                                "会话版本冲突：期望 " + request.expectedVersion()
                                        + "，当前 " + session.version());
                    }
                    if (!"OPEN".equals(session.status())) {
                        throw ApiException.unprocessable("SESSION_CLOSED", "采集会话已关闭: " + sessionNo);
                    }
                    // 关会话必须无缺号且全部缓冲已消费：存在 BUFFERED 包即存在缺口。
                    long buffered = acquisitionRepository.listPackets(session.id()).stream()
                            .filter(p -> "BUFFERED".equals(p.status())).count();
                    if (buffered > 0) {
                        throw ApiException.unprocessable("SESSION_HAS_GAP",
                                "存在缺号，" + buffered + " 个缓冲包未消费，拒绝关会话");
                    }
                    if (!acquisitionRepository.closeSession(session.id(), session.version(),
                            clock.instant())) {
                        throw ApiException.conflict("VERSION_CONFLICT", "会话版本并发变更，请重试");
                    }
                    return Outcome.of(200, toView(acquisitionRepository.findSession(sessionNo)));
                });
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public AcquisitionSessionView getSession(String sessionNo) {
        return toView(requireSession(sessionNo));
    }

    @Override
    public AcquisitionWatermarkView getWatermark(String sessionNo) {
        AcquisitionRepository.SessionRow session = requireSession(sessionNo);
        List<AcquisitionRepository.PacketRow> packets = acquisitionRepository.listPackets(session.id());
        List<Integer> buffered = new ArrayList<>();
        int committed = 0;
        int late = 0;
        for (AcquisitionRepository.PacketRow packet : packets) {
            switch (packet.status()) {
                case "BUFFERED" -> buffered.add(packet.seq());
                case "COMMITTED" -> committed++;
                default -> late++;
            }
        }
        return new AcquisitionWatermarkView(session.sessionNo(), session.watermark(), session.version(),
                buffered, committed, late);
    }

    @Override
    public List<AcquisitionPacketView> listPackets(String sessionNo) {
        AcquisitionRepository.SessionRow session = requireSession(sessionNo);
        List<AcquisitionPacketView> views = new ArrayList<>();
        for (AcquisitionRepository.PacketRow packet : acquisitionRepository.listPackets(session.id())) {
            views.add(new AcquisitionPacketView(packet.seq(), packet.pointCode(), packet.measuredTorque(),
                    packet.measuredAt(), packet.status(), packet.contentHash(), packet.batchNo()));
        }
        return views;
    }

    @Override
    public List<AcquisitionBatchView> listBatches(String sessionNo) {
        AcquisitionRepository.SessionRow session = requireSession(sessionNo);
        List<AcquisitionBatchView> views = new ArrayList<>();
        for (AcquisitionRepository.BatchRow batch : acquisitionRepository.listBatches(session.id())) {
            views.add(new AcquisitionBatchView(batch.batchNo(), batch.packetCount(), batch.acceptedCount(),
                    batch.replayedCount(), batch.committedCount(), batch.lateCount(),
                    batch.watermarkAfter(), batch.createdAt()));
        }
        return views;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private AcquisitionRepository.SessionRow requireSession(String sessionNo) {
        AcquisitionRepository.SessionRow session = acquisitionRepository.findSession(sessionNo);
        if (session == null) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "采集会话不存在: " + sessionNo);
        }
        return session;
    }

    private CardRow requireCard(String cardNo) {
        CardRow card = cardRepository.findByCardNo(cardNo);
        if (card == null) {
            throw ApiException.notFound("CARD_NOT_FOUND", "工序卡不存在: " + cardNo);
        }
        return card;
    }

    private AcquisitionSessionView toView(AcquisitionRepository.SessionRow session) {
        return new AcquisitionSessionView(session.sessionNo(), session.cardNo(), session.toolCode(),
                session.calibrationDate(), session.watermark(), session.status(), session.version(),
                acquisitionRepository.listPoints(session.id()), session.createdAt(), session.closedAt());
    }

    /**
     * 包内容指纹：结构化 JSON（字段名+类型确定）后 SHA-256，
     * 合法文本含分隔符也不会与字段边界混淆。
     */
    private String contentHash(String sessionNo, AcquisitionBatchRequest.Packet packet) {
        Map<String, Object> structured = new HashMap<>();
        structured.put("seq", packet.seq());
        structured.put("pointCode", packet.pointCode());
        structured.put("measuredAt", packet.measuredAt().toString());
        structured.put("torque", packet.torque().stripTrailingZeros().toPlainString());
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(structured);
        } catch (Exception e) {
            throw new IllegalStateException("采集包指纹序列化失败", e);
        }
        return Fingerprints.sha256(sessionNo, "ACQ_PACKET", json);
    }
}
