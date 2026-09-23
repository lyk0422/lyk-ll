package com.example.starter.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.EntityType;
import com.example.starter.domain.EventKind;
import com.example.starter.repo.AliquotRepository;
import com.example.starter.repo.AliquotRow;
import com.example.starter.repo.CustodyEventRepository;
import com.example.starter.repo.CustodyEventRow;
import com.example.starter.repo.IdempotentRequestRepository;
import com.example.starter.repo.IdempotentRequestRow;
import com.example.starter.repo.ReservationRepository;
import com.example.starter.repo.ReservationRow;
import com.example.starter.repo.SpecimenRepository;
import com.example.starter.repo.SpecimenRow;
import com.example.starter.domain.ReservationStatus;
import com.example.starter.support.BadRequestException;
import com.example.starter.support.ConflictException;
import com.example.starter.support.Fingerprint;
import com.example.starter.support.LabClock;
import com.example.starter.support.NotFoundException;
import com.example.starter.support.Volume;
import com.example.starter.web.dto.AliquotView;
import com.example.starter.web.dto.CancelRequest;
import com.example.starter.web.dto.CustodyEventView;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.QuarantineRequest;
import com.example.starter.web.dto.ReservationView;
import com.example.starter.web.dto.ReserveRequest;
import com.example.starter.web.dto.ResultRequest;
import com.example.starter.web.dto.SpecimenView;
import com.example.starter.web.dto.SplitItemRequest;
import com.example.starter.web.dto.SplitRequest;
import com.example.starter.web.dto.SplitResponse;
import com.example.starter.web.dto.StartRequest;
import com.example.starter.web.dto.TransferRequest;
import com.example.starter.web.dto.VolumeLedgerResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实验室样本保管应用服务：入库、分装、申请/开始/取消/结果、污染隔离、交接与账本查询。
 *
 * <p>全部写操作在单事务内完成；同一标本/分装管的并发写通过行锁（FOR UPDATE）串行化，
 * 体积一律使用纳升整数计算以保证精确守恒。</p>
 */
@Service
public class CustodyService {

    private static final String KIND_SPLIT = "ALIQUOT_SPLIT";
    private static final String KIND_RESERVE = "ASSAY_RESERVE";
    private static final String DEFAULT_QUARANTINE_CUSTODIAN = "QUARANTINE";

    private final SpecimenRepository specimens;
    private final AliquotRepository aliquots;
    private final ReservationRepository reservations;
    private final CustodyEventRepository events;
    private final IdempotentRequestRepository idempotentRequests;
    private final ViewAssembler views;
    private final LabClock clock;
    private final ObjectMapper objectMapper;

    public CustodyService(SpecimenRepository specimens,
                          AliquotRepository aliquots,
                          ReservationRepository reservations,
                          CustodyEventRepository events,
                          IdempotentRequestRepository idempotentRequests,
                          ViewAssembler views,
                          LabClock clock,
                          ObjectMapper objectMapper) {
        this.specimens = specimens;
        this.aliquots = aliquots;
        this.reservations = reservations;
        this.events = events;
        this.idempotentRequests = idempotentRequests;
        this.views = views;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ============================ 入库 ============================

    /**
     * 原始样本入库：specimenId 唯一，记录首任保管人与 INTAKE 事件。
     */
    @Transactional
    public SpecimenView intake(IntakeRequest request) {
        Instant now = clock.now();
        long volumeNl = Volume.parsePositiveNl(request.volumeUl(), "volumeUl");
        try {
            specimens.insert(request.specimenId(), volumeNl, request.custodian(), now);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("specimenId 已存在: " + request.specimenId());
        }
        appendEvent(EntityType.SPECIMEN, request.specimenId(), EventKind.INTAKE,
                null, request.custodian(), 0, 0, now, "样本入库");
        SpecimenRow row = specimens.findBySpecimenId(request.specimenId())
                .orElseThrow(() -> new IllegalStateException("入库后样本不存在"));
        return views.toSpecimenView(row);
    }

    // ============================ 分装 ============================

    /**
     * 分装：携带 requestId 与 expectedVersion，一次生成多个唯一 aliquot；
     * 任一条目非法（体积精度、非正、超额、别名重复）整批回滚。
     * 同 requestId 同参返回首次结果，异参（含 expectedVersion 变化）返回 409。
     */
    @Transactional
    public SplitResponse split(SplitRequest request) {
        Instant now = clock.now();

        SpecimenRow specimen = specimens.lockBySpecimenId(request.specimenId())
                .orElseThrow(() -> new NotFoundException("样本不存在: " + request.specimenId()));
        // 行锁串行化后再次检查幂等记录：并发同键请求在首个事务提交后可直接重放首次结果。
        Optional<IdempotentRequestRow> existing = idempotentRequests.find(request.requestId());
        if (existing.isPresent()) {
            return replaySplit(request, existing.get());
        }
        if (request.expectedVersion() != specimen.version()) {
            throw new ConflictException("样本版本不匹配，expectedVersion=" + request.expectedVersion()
                    + "，实际=" + specimen.version());
        }

        List<String> fingerprintParts = splitFingerprintParts(request);

        Set<String> uniqueAliquotIds = new LinkedHashSet<>();
        long totalNl = 0L;
        for (SplitItemRequest item : request.aliquots()) {
            if (!uniqueAliquotIds.add(item.aliquotId())) {
                throw new BadRequestException("分装请求内 aliquotId 重复: " + item.aliquotId());
            }
            long itemVolume = Volume.parsePositiveNl(item.volumeUl(), "volumeUl");
            totalNl = Math.addExact(totalNl, itemVolume);
            if (aliquots.findByAliquotId(item.aliquotId()).isPresent()) {
                throw new ConflictException("aliquotId 已存在: " + item.aliquotId());
            }
            resolveCustodian(item.custodian(), request.custodian(), specimen.custodian());
        }
        if (totalNl > specimen.remainingNl()) {
            throw new BadRequestException("分装总量 " + Volume.formatUl(totalNl)
                    + "µL 超过剩余量 " + Volume.formatUl(specimen.remainingNl()) + "µL");
        }

        String fingerprint = Fingerprint.sha256(KIND_SPLIT, fingerprintParts);
        int updated = specimens.decreaseRemaining(
                request.specimenId(), totalNl, specimen.version(), now);
        if (updated != 1) {
            throw new ConflictException("样本版本或剩余量已变化，分装被拒绝");
        }

        List<AliquotView> aliquotViews = new ArrayList<>();
        for (SplitItemRequest item : request.aliquots()) {
            long itemVolume = Volume.parsePositiveNl(item.volumeUl(), "volumeUl");
            String effectiveCustodian = resolveCustodian(item.custodian(),
                    request.custodian(), specimen.custodian());
            try {
                aliquots.insert(item.aliquotId(), request.specimenId(), itemVolume,
                        effectiveCustodian, now);
            } catch (DuplicateKeyException ex) {
                throw new ConflictException("aliquotId 已存在: " + item.aliquotId());
            }
            appendEvent(EntityType.ALIQUOT, item.aliquotId(), EventKind.CREATE,
                    null, effectiveCustodian, 0, 0, now,
                    "由样本 " + request.specimenId() + " 分装");
            AliquotRow inserted = aliquots.findByAliquotId(item.aliquotId())
                    .orElseThrow(() -> new IllegalStateException("分装后 aliquot 不存在"));
            aliquotViews.add(views.toAliquotView(inserted, 0L));
        }

        SpecimenRow refreshed = specimens.findBySpecimenId(request.specimenId())
                .orElseThrow(() -> new IllegalStateException("分装后样本不存在"));
        SplitResponse response = new SplitResponse(false, views.toSpecimenView(refreshed), aliquotViews);
        persistIdempotent(request.requestId(), KIND_SPLIT, fingerprint, response);
        return response;
    }

    private SplitResponse replaySplit(SplitRequest request, IdempotentRequestRow record) {
        if (!KIND_SPLIT.equals(record.requestKind())) {
            throw new ConflictException("requestId 已用于其他类型请求: " + request.requestId());
        }
        String currentFingerprint = Fingerprint.sha256(KIND_SPLIT, splitFingerprintParts(request));
        if (!record.fingerprint().equals(currentFingerprint)) {
            throw new ConflictException("requestId=" + request.requestId() + " 参数与首次请求不一致");
        }
        SplitResponse replayed = readJson(record.responseJson(), SplitResponse.class);
        return new SplitResponse(true, replayed.specimen(), replayed.aliquots());
    }

    /**
     * 分装指纹只依赖请求显式字段：标本、期望版本、请求级保管人、
     * 每个条目的 aliquotId、分配体积与条目保管人。首次调用与重放使用完全一致的规范化序列。
     */
    private List<String> splitFingerprintParts(SplitRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.specimenId());
        parts.add(String.valueOf(request.expectedVersion()));
        parts.add(request.custodian() == null ? "" : request.custodian());
        for (SplitItemRequest item : request.aliquots()) {
            long itemVolume = Volume.parsePositiveNl(item.volumeUl(), "volumeUl");
            parts.add(item.aliquotId());
            parts.add(Long.toString(itemVolume));
            parts.add(item.custodian() == null ? "" : item.custodian());
        }
        return parts;
    }

    // ============================ 检测申请/占用 ============================

    /**
     * 检测申请：aliquot 从可用体积中原子占用，AVAILABLE 状态才允许；
     * 并发申请通过 aliquot 行锁串行化，超额返回 409。
     * 同 requestId 同参返回首次结果，异参（含 expectedVersion、占用量变化）返回 409。
     */
    @Transactional
    public ReservationView reserve(ReserveRequest request) {
        Instant now = clock.now();

        AliquotRow aliquot = aliquots.lockByAliquotId(request.aliquotId())
                .orElseThrow(() -> new NotFoundException("分装管不存在: " + request.aliquotId()));
        // 行锁串行化后再次检查幂等记录：并发同键申请返回首次结果。
        Optional<IdempotentRequestRow> existing = idempotentRequests.find(request.requestId());
        if (existing.isPresent()) {
            return replayReserve(request, existing.get());
        }
        long volumeNl = Volume.parsePositiveNl(request.volumeUl(), "volumeUl");
        String effectiveCustodian = request.custodian() == null
                ? aliquot.custodian() : request.custodian();

        if (aliquot.status() != com.example.starter.domain.AliquotStatus.AVAILABLE) {
            throw new ConflictException("分装管已隔离，不能新建占用: " + request.aliquotId());
        }
        if (request.expectedVersion() != aliquot.version()) {
            throw new ConflictException("分装管版本不匹配，expectedVersion=" + request.expectedVersion()
                    + "，实际=" + aliquot.version());
        }
        long alreadyReservedNl = reservations.sumReservedNl(request.aliquotId());
        long usedNl = aliquot.consumedNl() + alreadyReservedNl + volumeNl;
        if (usedNl > aliquot.volumeNl()) {
            throw new ConflictException("可用体积不足：申请占用 " + Volume.formatUl(volumeNl)
                    + "µL，已消耗 " + Volume.formatUl(aliquot.consumedNl())
                    + "µL，已预留 " + Volume.formatUl(alreadyReservedNl)
                    + "µL，总体积 " + Volume.formatUl(aliquot.volumeNl()) + "µL");
        }

        String fingerprint = Fingerprint.sha256(KIND_RESERVE, List.of(
                request.aliquotId(),
                Long.toString(volumeNl),
                request.projectCode(),
                String.valueOf(request.expectedVersion()),
                request.custodian() == null ? "" : request.custodian()));

        try {
            reservations.insertReserved(request.requestId(), request.aliquotId(),
                    request.projectCode(), volumeNl, effectiveCustodian, now);
        } catch (DuplicateKeyException ex) {
            return handleReserveDuplicate(request);
        }
        appendEvent(EntityType.RESERVATION, request.requestId(), EventKind.CREATE,
                null, effectiveCustodian, 0, 0, now,
                "项目 " + request.projectCode() + " 占用 " + Volume.formatUl(volumeNl) + "µL");

        ReservationRow row = reservations.findByRequestId(request.requestId())
                .orElseThrow(() -> new IllegalStateException("占用创建后记录不存在"));
        ReservationView view = views.toReservationView(row);
        String responseJson;
        try {
            responseJson = objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("占用响应快照序列化失败", ex);
        }
        try {
            idempotentRequests.insert(request.requestId(), KIND_RESERVE, fingerprint, responseJson, now);
        } catch (DuplicateKeyException ex) {
            return handleReserveDuplicate(request);
        }
        return view;
    }

    private ReservationView handleReserveDuplicate(ReserveRequest request) {
        IdempotentRequestRow record = idempotentRequests.find(request.requestId())
                .orElseThrow(() -> new ConflictException("requestId 冲突: " + request.requestId()));
        return replayReserve(request, record);
    }

    private ReservationView replayReserve(ReserveRequest request, IdempotentRequestRow record) {
        if (!KIND_RESERVE.equals(record.requestKind())) {
            throw new ConflictException("requestId 已用于其他类型请求: " + request.requestId());
        }
        long volumeNl = Volume.parsePositiveNl(request.volumeUl(), "volumeUl");
        String currentFingerprint = Fingerprint.sha256(KIND_RESERVE, List.of(
                request.aliquotId(),
                Long.toString(volumeNl),
                request.projectCode(),
                String.valueOf(request.expectedVersion()),
                request.custodian() == null ? "" : request.custodian()));
        if (!record.fingerprint().equals(currentFingerprint)) {
            throw new ConflictException("requestId=" + request.requestId() + " 参数与首次请求不一致");
        }
        return readJson(record.responseJson(), ReservationView.class);
    }

    // ============================ 开始检测 ============================

    /**
     * 检测开始：RESERVED 原子转为 CONSUMED，占用量计入 aliquot 已消耗。
     */
    @Transactional
    public ReservationView start(String requestId, StartRequest request) {
        Instant now = clock.now();
        ReservationRow reservation = lockReservationAndAliquot(requestId);
        if (request.expectedVersion() != reservation.version()) {
            throw new ConflictException("申请版本不匹配，expectedVersion=" + request.expectedVersion()
                    + "，实际=" + reservation.version());
        }
        if (reservation.status() != ReservationStatus.RESERVED) {
            throw new ConflictException("仅 RESERVED 申请可以开始检测，当前状态=" + reservation.status());
        }
        AliquotRow aliquot = aliquots.lockByAliquotId(reservation.aliquotId())
                .orElseThrow(() -> new IllegalStateException("占用关联的分装管不存在"));

        int aliquotUpdated = aliquots.addConsumed(
                aliquot.aliquotId(), reservation.volumeNl(), aliquot.version(), now);
        if (aliquotUpdated != 1) {
            throw new ConflictException("分装管状态或版本已变化，无法开始检测");
        }
        int reservationUpdated = reservations.compareAndUpdateStatus(
                requestId, ReservationStatus.RESERVED, ReservationStatus.CONSUMED,
                reservation.version(), now);
        if (reservationUpdated != 1) {
            throw new ConflictException("申请状态已变化，无法开始检测");
        }
        return reloadReservation(requestId);
    }

    // ============================ 取消 ============================

    /**
     * 取消：仅 RESERVED（尚未开始）可取消并释放体积；CONSUMED/CANCELLED 返回 409。
     */
    @Transactional
    public ReservationView cancel(String requestId, CancelRequest request) {
        Instant now = clock.now();
        ReservationRow reservation = lockReservationAndAliquot(requestId);
        if (request.expectedVersion() != reservation.version()) {
            throw new ConflictException("申请版本不匹配，expectedVersion=" + request.expectedVersion()
                    + "，实际=" + reservation.version());
        }
        if (reservation.status() != ReservationStatus.RESERVED) {
            throw new ConflictException("仅尚未开始的预留可以取消，当前状态=" + reservation.status());
        }
        int updated = reservations.compareAndUpdateStatus(
                requestId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED,
                reservation.version(), now);
        if (updated != 1) {
            throw new ConflictException("申请状态已变化，取消失败");
        }
        return reloadReservation(requestId);
    }

    // ============================ 检测结果 ============================

    /**
     * 结果上报：仅 CONSUMED 可写一次；RESERVED（未开始）或 CANCELLED（取消后迟到）返回 409，
     * 取消后到达的结果不恢复占用、不写入结果。
     */
    @Transactional
    public ReservationView recordResult(String requestId, ResultRequest request) {
        Instant now = clock.now();
        ReservationRow reservation = reservations.findByRequestId(requestId)
                .orElseThrow(() -> new NotFoundException("检测申请不存在: " + requestId));
        if (request.expectedVersion() != reservation.version()) {
            throw new ConflictException("申请版本不匹配，expectedVersion=" + request.expectedVersion()
                    + "，实际=" + reservation.version());
        }
        if (reservation.status() == ReservationStatus.CANCELLED) {
            throw new ConflictException("申请已取消，迟到结果不予接收");
        }
        if (reservation.status() != ReservationStatus.CONSUMED) {
            throw new ConflictException("检测尚未开始，不能上报结果，当前状态=" + reservation.status());
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(request.result());
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("结果载荷无法序列化");
        }
        if (payload.length() > 3900) {
            throw new BadRequestException("结果载荷过大");
        }
        int updated = reservations.writeResult(requestId, reservation.version(), payload, now);
        if (updated != 1) {
            throw new ConflictException("结果已存在或申请版本已变化");
        }
        return reloadReservation(requestId);
    }

    // ============================ 污染隔离 ============================

    /**
     * 污染事件：指定 aliquot AVAILABLE→QUARANTINED，其全部 RESERVED 申请联动 CANCELLED，
     * 已消耗量与历史保管事件保留。整批原子完成。
     */
    @Transactional
    public AliquotView quarantine(QuarantineRequest request) {
        Instant now = clock.now();
        AliquotRow aliquot = aliquots.lockByAliquotId(request.aliquotId())
                .orElseThrow(() -> new NotFoundException("分装管不存在: " + request.aliquotId()));
        if (request.expectedVersion() != aliquot.version()) {
            throw new ConflictException("分装管版本不匹配，expectedVersion=" + request.expectedVersion()
                    + "，实际=" + aliquot.version());
        }
        if (!request.expectedCustodian().equals(aliquot.custodian())) {
            throw new ConflictException("当前持有人不匹配，expectedCustodian=" + request.expectedCustodian()
                    + "，实际=" + aliquot.custodian());
        }
        if (aliquot.status() != com.example.starter.domain.AliquotStatus.AVAILABLE) {
            throw new ConflictException("分装管已处于隔离状态: " + request.aliquotId());
        }
        String quarantineCustodian = request.quarantineCustodian() == null
                || request.quarantineCustodian().isBlank()
                ? DEFAULT_QUARANTINE_CUSTODIAN : request.quarantineCustodian();

        List<ReservationRow> reserved = reservations.lockReservedByAliquot(request.aliquotId());
        int aliquotUpdated = aliquots.quarantine(
                request.aliquotId(), quarantineCustodian, aliquot.version(), now);
        if (aliquotUpdated != 1) {
            throw new ConflictException("分装管状态或版本已变化，隔离失败");
        }
        for (ReservationRow row : reserved) {
            int cancelled = reservations.compareAndUpdateStatus(
                    row.requestId(), ReservationStatus.RESERVED, ReservationStatus.CANCELLED,
                    row.version(), now);
            if (cancelled != 1) {
                throw new ConflictException("申请状态已变化，联动取消失败: " + row.requestId());
            }
        }
        appendEvent(EntityType.ALIQUOT, request.aliquotId(), EventKind.QUARANTINE,
                aliquot.custodian(), quarantineCustodian,
                aliquot.version(), aliquot.version() + 1, now,
                request.note() == null ? "" : request.note());

        AliquotRow refreshed = aliquots.findByAliquotId(request.aliquotId())
                .orElseThrow(() -> new IllegalStateException("隔离后分装管不存在"));
        return views.toAliquotView(refreshed, 0L);
    }

    // ============================ 保管方交接 ============================

    /**
     * 保管方交接：校验实体存在、当前持有人、版本与时间不倒退；
     * 完全相同的交接重复提交时幂等返回既有事件，不新增事件、不推进版本。
     */
    @Transactional
    public CustodyEventView transfer(TransferRequest request) {
        EntityType entityType = parseEntityType(request.entityType());
        Instant now = clock.now();

        EntitySnapshot snapshot = loadAndLockEntity(entityType, request.entityId());
        // 完全相同的交接在当前持有人仍为目标保管人时幂等返回：
        // 先加载实体可区分“原始交接后的重试”与“实体已再次转手后的过期报文”。
        if (events.existsSameTransfer(entityType, request.entityId(),
                request.fromCustodian(), request.toCustodian())
                && request.toCustodian().equals(snapshot.custodian())) {
            CustodyEventRow previous = events.findLastByEntity(entityType, request.entityId())
                    .filter(event -> event.eventKind() == EventKind.TRANSFER
                            && request.fromCustodian().equals(event.fromCustodian())
                            && request.toCustodian().equals(event.toCustodian()))
                    .orElseGet(() -> events.findByEntity(entityType, request.entityId()).stream()
                            .filter(event -> event.eventKind() == EventKind.TRANSFER
                                    && request.fromCustodian().equals(event.fromCustodian())
                                    && request.toCustodian().equals(event.toCustodian()))
                            .reduce((first, second) -> second)
                            .orElseThrow(() -> new IllegalStateException("已存在的交接事件丢失")));
            return views.toEventView(previous);
        }

        if (request.expectedVersion() != snapshot.version()) {
            throw new ConflictException("实体版本不匹配，expectedVersion=" + request.expectedVersion()
                    + "，实际=" + snapshot.version());
        }
        if (!request.fromCustodian().equals(snapshot.custodian())) {
            throw new ConflictException("当前持有人不匹配，fromCustodian=" + request.fromCustodian()
                    + "，实际=" + snapshot.custodian());
        }
        if (request.fromCustodian().equals(request.toCustodian())) {
            throw new BadRequestException("目标保管人不能与当前保管人相同");
        }
        assertNoTimeRegression(entityType, request.entityId(), now);

        int versionBefore = snapshot.version();
        int updated = switch (entityType) {
            case SPECIMEN -> specimens.updateCustodian(
                    request.entityId(), request.toCustodian(), versionBefore, now);
            case ALIQUOT -> aliquots.updateCustodian(
                    request.entityId(), request.toCustodian(), versionBefore, now);
            case RESERVATION -> reservations.updateCustodian(
                    request.entityId(), request.toCustodian(), versionBefore, now);
        };
        if (updated != 1) {
            throw new ConflictException("实体版本已变化，交接失败");
        }
        appendEvent(entityType, request.entityId(), EventKind.TRANSFER,
                request.fromCustodian(), request.toCustodian(),
                versionBefore, versionBefore + 1, now, null);
        CustodyEventRow created = events.findLastByEntity(entityType, request.entityId())
                .orElseThrow(() -> new IllegalStateException("交接事件写入后不存在"));
        return views.toEventView(created);
    }

    // ============================ 查询 ============================

    /**
     * 体积账本：样本剩余量、各 aliquot 的总量/已消耗/已预留/可用量及全部申请。
     */
    @Transactional(readOnly = true)
    public VolumeLedgerResponse ledger(String specimenId) {
        SpecimenRow specimen = specimens.findBySpecimenId(specimenId)
                .orElseThrow(() -> new NotFoundException("样本不存在: " + specimenId));
        List<AliquotRow> aliquotRows = aliquots.findBySpecimenId(specimenId);
        List<AliquotView> aliquotViews = new ArrayList<>();
        List<ReservationView> reservationViews = new ArrayList<>();
        for (AliquotRow aliquot : aliquotRows) {
            long reservedNl = reservations.sumReservedNl(aliquot.aliquotId());
            aliquotViews.add(views.toAliquotView(aliquot, reservedNl));
            for (ReservationRow reservation : reservations.findByAliquotId(aliquot.aliquotId())) {
                reservationViews.add(views.toReservationView(reservation));
            }
        }
        return new VolumeLedgerResponse(
                views.toSpecimenView(specimen), aliquotViews, reservationViews);
    }

    @Transactional(readOnly = true)
    public SpecimenView getSpecimen(String specimenId) {
        return views.toSpecimenView(specimens.findBySpecimenId(specimenId)
                .orElseThrow(() -> new NotFoundException("样本不存在: " + specimenId)));
    }

    @Transactional(readOnly = true)
    public AliquotView getAliquot(String aliquotId) {
        AliquotRow aliquot = aliquots.findByAliquotId(aliquotId)
                .orElseThrow(() -> new NotFoundException("分装管不存在: " + aliquotId));
        long reservedNl = reservations.sumReservedNl(aliquotId);
        return views.toAliquotView(aliquot, reservedNl);
    }

    @Transactional(readOnly = true)
    public ReservationView getReservation(String requestId) {
        return views.toReservationView(reservations.findByRequestId(requestId)
                .orElseThrow(() -> new NotFoundException("检测申请不存在: " + requestId)));
    }

    @Transactional(readOnly = true)
    public List<CustodyEventView> getEvents(String entityType, String entityId) {
        EntityType type = parseEntityType(entityType);
        return events.findByEntity(type, entityId).stream().map(views::toEventView).toList();
    }

    // ============================ 内部辅助 ============================

    private ReservationRow lockReservationAndAliquot(String requestId) {
        ReservationRow reservation = reservations.findByRequestId(requestId)
                .orElseThrow(() -> new NotFoundException("检测申请不存在: " + requestId));
        // 先锁 aliquot，与申请占用、污染隔离使用相同加锁顺序，避免死锁。
        aliquots.lockByAliquotId(reservation.aliquotId());
        return reservations.findByRequestId(requestId)
                .orElseThrow(() -> new NotFoundException("检测申请不存在: " + requestId));
    }

    private ReservationView reloadReservation(String requestId) {
        return views.toReservationView(reservations.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("申请记录不存在: " + requestId)));
    }

    private EntitySnapshot loadAndLockEntity(EntityType entityType, String entityId) {
        return switch (entityType) {
            case SPECIMEN -> {
                SpecimenRow row = specimens.lockBySpecimenId(entityId)
                        .orElseThrow(() -> new NotFoundException("样本不存在: " + entityId));
                yield new EntitySnapshot(row.custodian(), row.version());
            }
            case ALIQUOT -> {
                AliquotRow row = aliquots.lockByAliquotId(entityId)
                        .orElseThrow(() -> new NotFoundException("分装管不存在: " + entityId));
                yield new EntitySnapshot(row.custodian(), row.version());
            }
            case RESERVATION -> {
                ReservationRow row = reservations.findByRequestId(entityId)
                        .orElseThrow(() -> new NotFoundException("检测申请不存在: " + entityId));
                aliquots.lockByAliquotId(row.aliquotId());
                yield new EntitySnapshot(row.custodian(), row.version());
            }
        };
    }

    private void appendEvent(EntityType entityType, String entityId, EventKind kind,
                             String fromCustodian, String toCustodian,
                             int versionBefore, int versionAfter, Instant now, String note) {
        assertNoTimeRegression(entityType, entityId, now);
        events.append(entityType, entityId, kind, fromCustodian, toCustodian,
                versionBefore, versionAfter, now, note);
    }

    private void assertNoTimeRegression(EntityType entityType, String entityId, Instant now) {
        events.findLastByEntity(entityType, entityId).ifPresent(last -> {
            if (now.isBefore(last.occurredAt())) {
                throw new ConflictException("事件时间早于该实体最近一次保管事件，时间不得倒退");
            }
        });
    }

    private void persistIdempotent(String requestId, String kind, String fingerprint, Object response) {
        try {
            idempotentRequests.insert(requestId, kind, fingerprint,
                    objectMapper.writeValueAsString(response), clock.now());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("requestId 冲突: " + requestId);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应快照序列化失败", ex);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应快照无法解析", ex);
        }
    }

    private static EntityType parseEntityType(String raw) {
        try {
            return EntityType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("不支持的实体类型: " + raw);
        }
    }

    private static String resolveCustodian(String itemCustodian, String requestCustodian,
                                           String fallbackCustodian) {
        if (itemCustodian != null && !itemCustodian.isBlank()) {
            return itemCustodian;
        }
        if (requestCustodian != null && !requestCustodian.isBlank()) {
            return requestCustodian;
        }
        return fallbackCustodian;
    }

    private record EntitySnapshot(String custodian, int version) {
    }
}
