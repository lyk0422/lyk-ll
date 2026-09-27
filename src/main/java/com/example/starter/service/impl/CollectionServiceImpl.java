package com.example.starter.service.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.api.dto.CollectionBatchView;
import com.example.starter.api.dto.CollectionCloseRequest;
import com.example.starter.api.dto.CollectionPacketView;
import com.example.starter.api.dto.CollectionSessionCreateRequest;
import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionUploadRequest;
import com.example.starter.api.dto.CollectionUploadResult;
import com.example.starter.domain.CardStatus;
import com.example.starter.domain.CollectionPacketStatus;
import com.example.starter.domain.CollectionSessionStatus;
import com.example.starter.domain.TorqueMath;
import com.example.starter.persistence.CardRepository;
import com.example.starter.persistence.CardRow;
import com.example.starter.persistence.CollectionRepository;
import com.example.starter.persistence.FastenerRow;
import com.example.starter.persistence.ToolRepository;
import com.example.starter.persistence.TorqueRepository;
import com.example.starter.service.CollectionService;
import com.example.starter.service.Outcome;
import com.example.starter.support.ApiException;
import com.example.starter.support.Fingerprints;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 本地工具采集协议核心服务：有序批次入账与乱序水位线。
 *
 * <p>写操作经 {@link StrictIdempotencyExecutor} 包裹：成功结果（含版本推进、水位线、
 * 原始包与力矩记录）同事务提交，失败不占键。仅缓冲水位线之后第 1～20 号的乱序包，
 * 水位线只推进到连续完整前缀；超差沿用返工与整批隔离规则（与人工测量同一套
 * {@link TorqueRepository} 追加链）。归档后包只记迟到（LATE），不复活卡片。
 */
@Service
public class CollectionServiceImpl implements CollectionService {

    /** 乱序缓冲窗口：仅接受水位线之后第 1～20 号包。 */
    static final int BUFFER_WINDOW = 20;

    private final Clock clock;
    private final StrictIdempotencyExecutor idempotencyExecutor;
    private final CollectionRepository collectionRepository;
    private final CardRepository cardRepository;
    private final TorqueRepository torqueRepository;
    private final ToolRepository toolRepository;
    private final ObjectMapper objectMapper;

    public CollectionServiceImpl(Clock clock,
                                 StrictIdempotencyExecutor idempotencyExecutor,
                                 CollectionRepository collectionRepository,
                                 CardRepository cardRepository,
                                 TorqueRepository torqueRepository,
                                 ToolRepository toolRepository,
                                 ObjectMapper objectMapper) {
        this.clock = clock;
        this.idempotencyExecutor = idempotencyExecutor;
        this.collectionRepository = collectionRepository;
        this.cardRepository = cardRepository;
        this.torqueRepository = torqueRepository;
        this.toolRepository = toolRepository;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 创建会话
    // ------------------------------------------------------------------

    @Override
    public Outcome createSession(CollectionSessionCreateRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "COLLECTION_SESSION_CREATE", request, () -> {
            CardRow card = requireCard(request.cardNo());
            if (card.status() != CardStatus.DISPATCHED) {
                throw ApiException.unprocessable("CARD_NOT_IN_TIGHTENING",
                        "当前状态 " + card.status() + " 不能绑定采集会话");
            }
            ToolRepository.ToolRow tool = toolRepository.findByCode(request.toolCode());
            if (tool == null) {
                throw ApiException.unprocessable("TOOL_NOT_REGISTERED",
                        "力矩工具未登记: " + request.toolCode());
            }
            Instant now = clock.instant();
            long sessionId;
            try {
                // 绑定工具日校验版本：创建时快照 calibration_date，后续入账按测量时刻校验该版本。
                sessionId = collectionRepository.insertSession(request.sessionNo(), request.cardNo(),
                        request.toolCode(), tool.calibrationDate(), now);
            } catch (DuplicateKeyException duplicate) {
                throw ApiException.conflict("SESSION_NO_EXISTS", "采集会话号已存在: " + request.sessionNo());
            }
            // 快照工序卡紧固点清单，上传按此判定未知点。
            for (FastenerRow fastener : cardRepository.listFasteners(card.id())) {
                collectionRepository.insertSessionPoint(sessionId, fastener.pointCode());
            }
            return Outcome.of(201, toView(collectionRepository.findSessionByNo(request.sessionNo())));
        });
    }

    // ------------------------------------------------------------------
    // 批量上传
    // ------------------------------------------------------------------

    @Override
    public Outcome upload(CollectionUploadRequest request) {
        validateBatchShape(request);
        return idempotencyExecutor.execute(request.requestId(), "COLLECTION_UPLOAD", request, () -> {
            CollectionRepository.SessionRow session = requireSession(request.sessionNo());
            assertVersion(session, request.expectedVersion());
            CardRow card = requireCard(session.cardNo());
            Instant now = clock.instant();

            // 终态：归档后包只记迟到，不复活卡片。
            if (card.status() == CardStatus.ARCHIVED) {
                return uploadLate(session, card, request, now);
            }
            if (session.status() == CollectionSessionStatus.CLOSED) {
                throw ApiException.unprocessable("SESSION_CLOSED",
                        "采集会话已关闭，不再受理上传: " + request.sessionNo());
            }
            if (card.status() == CardStatus.QUARANTINED) {
                throw ApiException.unprocessable("CARD_QUARANTINED",
                        "工序卡已整批隔离评审，冻结受理采集上传");
            }
            // 紧固阶段（派工/已紧固）受理上传：允许补齐缺号；自检互检签署后冻结采集。
            if (card.status() != CardStatus.DISPATCHED && card.status() != CardStatus.TIGHTENED) {
                throw ApiException.unprocessable("CARD_NOT_IN_TIGHTENING",
                        "当前状态 " + card.status() + " 不受理采集上传");
            }
            return uploadActive(session, card, request, now);
        });
    }

    /** 批内形状校验：批量须为 1～20 包且序号不重复，否则整批拒绝（参数非法 400，不占幂等键）。 */
    private void validateBatchShape(CollectionUploadRequest request) {
        if (request.packets() == null || request.packets().isEmpty()
                || request.packets().size() > BUFFER_WINDOW) {
            throw ApiException.badRequest("BATCH_SIZE_INVALID",
                    "一次上传须为 1～" + BUFFER_WINDOW + " 包");
        }
        Set<Integer> seen = new HashSet<>();
        for (CollectionUploadRequest.PacketSpec spec : request.packets()) {
            if (spec.seq() == null || spec.seq() < 1) {
                throw ApiException.badRequest("SEQ_INVALID", "包序号须为从 1 开始的正整数");
            }
            if (spec.measuredTorque() == null
                    || spec.measuredTorque().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("TORQUE_NOT_POSITIVE", "力矩须为有限正数");
            }
            if (spec.measuredAt() == null) {
                throw ApiException.badRequest("MEASURED_AT_REQUIRED", "缺少 UTC 测量时刻");
            }
            if (!seen.add(spec.seq())) {
                throw ApiException.badRequest("DUPLICATE_SEQ_IN_BATCH", "批内序号重复: " + spec.seq());
            }
        }
    }

    /**
     * 采集中上传：先整批校验（未知点/超窗/校验版本，任一错误整批拒绝），再追加原始包、
     * 按连续完整前缀消费入账，最后同事务推进水位线与版本。
     */
    private Outcome uploadActive(CollectionRepository.SessionRow session, CardRow card,
                                 CollectionUploadRequest request, Instant now) {
        Set<String> boundPoints = new HashSet<>(collectionRepository.listSessionPoints(session.id()));
        List<CollectionUploadRequest.PacketSpec> toInsert = new ArrayList<>();
        int skipped = 0;
        for (CollectionUploadRequest.PacketSpec spec : request.packets()) {
            CollectionRepository.PacketRow existing = collectionRepository.findPacket(session.id(), spec.seq());
            if (existing != null) {
                // 同会话同序号：同内容重放不追加，异内容 409。
                if (existing.contentHash().equals(contentHash(spec))) {
                    skipped++;
                    continue;
                }
                throw ApiException.conflict("PACKET_CONTENT_CONFLICT",
                        "会话 " + session.sessionNo() + " 序号 " + spec.seq() + " 已存在不同内容的包");
            }
            if (!boundPoints.contains(spec.pointCode())) {
                throw ApiException.notFound("COLLECTION_POINT_UNKNOWN",
                        "紧固点不在会话绑定清单: " + spec.pointCode());
            }
            if (spec.seq() > session.watermark() + BUFFER_WINDOW) {
                throw ApiException.unprocessable("COLLECTION_WINDOW_EXCEEDED",
                        "序号 " + spec.seq() + " 超出乱序缓冲窗口（水位线 " + session.watermark()
                                + "，仅接受之后第 1～" + BUFFER_WINDOW + " 号）");
            }
            // 按测量时刻验证所绑定工具校验当天（UTC）有效。
            LocalDate measuredDate = LocalDate.ofInstant(spec.measuredAt(), ZoneOffset.UTC);
            if (!measuredDate.isEqual(session.calibrationDate())) {
                throw ApiException.unprocessable("TOOL_CALIBRATION_EXPIRED",
                        "测量日(UTC " + measuredDate + ")非绑定工具校验版本 "
                                + session.calibrationDate() + " 当天，禁止入账");
            }
            toInsert.add(spec);
        }

        int appended = 0;
        for (CollectionUploadRequest.PacketSpec spec : toInsert) {
            collectionRepository.insertPacket(session.id(), spec.seq(), spec.pointCode(),
                    spec.measuredTorque(), spec.measuredAt(), CollectionPacketStatus.BUFFERED,
                    contentHash(spec), request.requestId(), now);
            appended++;
        }

        // 前缀消费：水位线只推进到连续完整前缀；隔离等冻结态停止（缺号/冻结包留缓冲）。
        // 紧固阶段内（含本批入账促成 TIGHTENED 后）继续消费本批连续前缀，避免缺号无法补齐。
        int watermark = session.watermark();
        int consumed = 0;
        CardRow current = card;
        while (current.status() == CardStatus.DISPATCHED || current.status() == CardStatus.TIGHTENED) {
            CollectionRepository.PacketRow next = collectionRepository.findPacket(session.id(), watermark + 1);
            if (next == null || next.status() != CollectionPacketStatus.BUFFERED) {
                break;
            }
            current = postPacket(session, current, next, request.requestId(), now);
            collectionRepository.markPacketConsumed(next.id());
            watermark++;
            consumed++;
        }

        int newVersion = session.version();
        if (appended > 0) {
            // 成功变更：版本加 1（乐观守卫，并发漂移则整事务回滚）。
            boolean won = watermark != session.watermark()
                    ? collectionRepository.advanceWatermark(session.id(), session.version(), watermark)
                    : collectionRepository.bumpVersion(session.id(), session.version());
            if (!won) {
                throw ApiException.conflict("CONCURRENT_MODIFICATION", "会话版本并发漂移，请按最新版本重试");
            }
            newVersion = session.version() + 1;
            collectionRepository.insertBatch(session.id(), request.requestId(), appended, now);
        }
        return Outcome.of(200, new CollectionUploadResult(session.sessionNo(), request.packets().size(),
                appended, skipped, consumed, watermark, newVersion, current.status().name(), false));
    }

    /**
     * 归档后上传：只记迟到（LATE）证据，不消费、不推进水位线、不复活卡片；
     * 同序号同内容重放不追加，异内容 409。
     */
    private Outcome uploadLate(CollectionRepository.SessionRow session, CardRow card,
                               CollectionUploadRequest request, Instant now) {
        Set<String> boundPoints = new HashSet<>(collectionRepository.listSessionPoints(session.id()));
        List<CollectionUploadRequest.PacketSpec> toInsert = new ArrayList<>();
        int skipped = 0;
        for (CollectionUploadRequest.PacketSpec spec : request.packets()) {
            CollectionRepository.PacketRow existing = collectionRepository.findPacket(session.id(), spec.seq());
            if (existing != null) {
                if (existing.contentHash().equals(contentHash(spec))) {
                    skipped++;
                    continue;
                }
                throw ApiException.conflict("PACKET_CONTENT_CONFLICT",
                        "会话 " + session.sessionNo() + " 序号 " + spec.seq() + " 已存在不同内容的包");
            }
            if (!boundPoints.contains(spec.pointCode())) {
                throw ApiException.notFound("COLLECTION_POINT_UNKNOWN",
                        "紧固点不在会话绑定清单: " + spec.pointCode());
            }
            toInsert.add(spec);
        }
        int appended = 0;
        for (CollectionUploadRequest.PacketSpec spec : toInsert) {
            collectionRepository.insertPacket(session.id(), spec.seq(), spec.pointCode(),
                    spec.measuredTorque(), spec.measuredAt(), CollectionPacketStatus.LATE,
                    contentHash(spec), request.requestId(), now);
            appended++;
        }
        int newVersion = session.version();
        if (appended > 0) {
            if (!collectionRepository.bumpVersion(session.id(), session.version())) {
                throw ApiException.conflict("CONCURRENT_MODIFICATION", "会话版本并发漂移，请按最新版本重试");
            }
            newVersion = session.version() + 1;
            collectionRepository.insertBatch(session.id(), request.requestId(), appended, now);
        }
        return Outcome.of(200, new CollectionUploadResult(session.sessionNo(), request.packets().size(),
                appended, skipped, 0, session.watermark(), newVersion, card.status().name(), true));
    }

    /**
     * 单包入账：追加力矩记录（测量时刻取包内 UTC 时刻），超差沿用返工与整批隔离规则。
     *
     * @return 入账后的最新工序卡（可能已 TIGHTENED 或 QUARANTINED）
     */
    private CardRow postPacket(CollectionRepository.SessionRow session, CardRow card,
                               CollectionRepository.PacketRow packet, String requestId, Instant now) {
        FastenerRow fastener = cardRepository.findFastener(card.id(), packet.pointCode());
        if (fastener == null) {
            // 绑定清单快照自工序卡工艺，正常不可达；防御性拒绝并回滚整批。
            throw ApiException.notFound("COLLECTION_POINT_UNKNOWN", "紧固点不存在: " + packet.pointCode());
        }
        int attemptNo = torqueRepository.countAttempts(card.id(), fastener.id()) + 1;
        int reworks = torqueRepository.countReworks(card.id(), fastener.id());
        BigDecimal deviation = TorqueMath.deviationPct(fastener.nominalTorque(), packet.measuredTorque());
        boolean pass = TorqueMath.inTolerance(fastener.nominalTorque(), packet.measuredTorque(),
                fastener.tolerancePct());

        // 力矩记录追加（永不覆盖）；采集来源操作者记为设备会话。
        torqueRepository.insertTorque(new TorqueRepository.TorqueRow(
                0, card.id(), fastener.id(), attemptNo, packet.measuredTorque(), deviation, pass,
                "DEVICE:" + session.sessionNo(), session.toolCode(), packet.measuredAt(), requestId));

        if (!pass) {
            if (reworks < OperationServiceImpl.MAX_REWORKS) {
                // 超差即返工并记录；返工台账与力矩链同一事务追加。
                String reason = "采集实测 " + packet.measuredTorque() + " N·m，偏差 " + deviation
                        + "%，超出 ±" + fastener.tolerancePct() + "%";
                torqueRepository.insertRework(new TorqueRepository.ReworkRow(
                        0, card.id(), fastener.id(), reworks + 1, requestId, reason, now), now);
            } else {
                // 已返工恰 2 次后第 3 次测量仍超差：整批隔离评审，停止继续消费前缀。
                cardRepository.markQuarantined(card.id(), now);
                return requireCard(card.cardNo());
            }
        }

        // 全部紧固点最近一次测量均合格 → 力矩紧固阶段完成。
        int fastenerCount = cardRepository.listFasteners(card.id()).size();
        if (torqueRepository.allFastenersLastAttemptPassing(card.id(), fastenerCount)) {
            cardRepository.updateStatusIfCurrent(card.id(), CardStatus.DISPATCHED, CardStatus.TIGHTENED);
        }
        return requireCard(card.cardNo());
    }

    // ------------------------------------------------------------------
    // 关闭会话
    // ------------------------------------------------------------------

    @Override
    public Outcome closeSession(CollectionCloseRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "COLLECTION_SESSION_CLOSE", request, () -> {
            CollectionRepository.SessionRow session = requireSession(request.sessionNo());
            assertVersion(session, request.expectedVersion());
            if (session.status() == CollectionSessionStatus.CLOSED) {
                throw ApiException.unprocessable("SESSION_CLOSED",
                        "采集会话已关闭: " + request.sessionNo());
            }
            int buffered = collectionRepository.countPacketsByStatus(
                    session.id(), CollectionPacketStatus.BUFFERED);
            if (buffered > 0) {
                // 有缺号（缓冲未被消费）不得关闭。
                throw ApiException.unprocessable("COLLECTION_BUFFER_PENDING",
                        "存在缺号，" + buffered + " 个乱序缓冲包未消费，不得关闭会话");
            }
            Instant now = clock.instant();
            if (!collectionRepository.closeSession(session.id(), session.version(), now)) {
                throw ApiException.conflict("CONCURRENT_MODIFICATION", "会话版本并发漂移，请按最新版本重试");
            }
            return Outcome.of(200, toView(collectionRepository.findSessionByNo(request.sessionNo())));
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
    public List<CollectionPacketView> listPackets(String sessionNo) {
        CollectionRepository.SessionRow session = requireSession(sessionNo);
        return collectionRepository.listPackets(session.id()).stream()
                .map(p -> new CollectionPacketView(p.seq(), p.pointCode(), p.measuredTorque(),
                        p.measuredAt(), p.status().name(), p.contentHash(), p.requestId(), p.createdAt()))
                .toList();
    }

    @Override
    public List<CollectionBatchView> listBatches(String sessionNo) {
        CollectionRepository.SessionRow session = requireSession(sessionNo);
        return collectionRepository.listBatches(session.id()).stream()
                .map(b -> new CollectionBatchView(b.requestId(), b.packetCount(), b.createdAt()))
                .toList();
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

    private CollectionRepository.SessionRow requireSession(String sessionNo) {
        CollectionRepository.SessionRow session = collectionRepository.findSessionByNo(sessionNo);
        if (session == null) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "采集会话不存在: " + sessionNo);
        }
        return session;
    }

    private void assertVersion(CollectionRepository.SessionRow session, int expectedVersion) {
        if (session.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "会话版本冲突：期望 " + expectedVersion + "，当前 " + session.version());
        }
    }

    /** 包内容指纹：结构化 JSON 的 SHA-256，合法文本含分隔符也可区分。 */
    private String contentHash(CollectionUploadRequest.PacketSpec spec) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(spec);
        } catch (Exception e) {
            throw new IllegalStateException("采集包序列化失败", e);
        }
        return Fingerprints.sha256("COLLECTION_PACKET", "PACKET", json);
    }

    private CollectionSessionView toView(CollectionRepository.SessionRow session) {
        return new CollectionSessionView(session.sessionNo(), session.cardNo(), session.toolCode(),
                session.calibrationDate(), session.status().name(), session.watermark(), session.version(),
                collectionRepository.countPacketsByStatus(session.id(), CollectionPacketStatus.BUFFERED),
                collectionRepository.countPacketsByStatus(session.id(), CollectionPacketStatus.CONSUMED),
                collectionRepository.countPacketsByStatus(session.id(), CollectionPacketStatus.LATE),
                collectionRepository.listSessionPoints(session.id()),
                session.createdAt(), session.closedAt());
    }
}
