package com.example.starter.roaming.service;

import com.example.starter.roaming.api.EndSessionRequest;
import com.example.starter.roaming.api.MeterSnapshotRequest;
import com.example.starter.roaming.api.MeterSnapshotResponse;
import com.example.starter.roaming.api.SessionResponse;
import com.example.starter.roaming.api.StartSessionRequest;
import com.example.starter.roaming.error.ConflictException;
import com.example.starter.roaming.error.NotFoundException;
import com.example.starter.roaming.error.UnprocessableException;
import com.example.starter.roaming.repo.MeterSnapshotRepository;
import com.example.starter.roaming.repo.MeterSnapshotRepository.SnapshotRow;
import com.example.starter.roaming.repo.ReferenceDataRepository.PileRow;
import com.example.starter.roaming.repo.ReferenceDataRepository.VehicleRow;
import com.example.starter.roaming.repo.SessionRepository;
import com.example.starter.roaming.repo.SessionRepository.SessionRow;
import com.example.starter.roaming.repo.SettlementRepository;
import com.example.starter.roaming.repo.SettlementRepository.SuspenseRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 充电会话启停与计量。
 * <p>启停与计量均以 requestId 幂等；计量快照按“会话号＋抄表序号”去重、乱序按序号归位、
 * 重复不重计电量；会话结束与批次封存经 UTC 日清分行锁串行，保证会话唯一归属；
 * 封存后迟到快照形成差异挂账。</p>
 */
@Service
public class ChargingSessionService {

    private final Clock clock;
    private final SessionRepository sessionRepository;
    private final MeterSnapshotRepository meterRepository;
    private final SettlementRepository settlementRepository;
    private final com.example.starter.roaming.repo.ReferenceDataRepository referenceDataRepository;
    private final IdempotencySupport idempotency;

    public ChargingSessionService(Clock clock,
                                  SessionRepository sessionRepository,
                                  MeterSnapshotRepository meterRepository,
                                  SettlementRepository settlementRepository,
                                  com.example.starter.roaming.repo.ReferenceDataRepository referenceDataRepository,
                                  IdempotencySupport idempotency) {
        this.clock = clock;
        this.sessionRepository = sessionRepository;
        this.meterRepository = meterRepository;
        this.settlementRepository = settlementRepository;
        this.referenceDataRepository = referenceDataRepository;
        this.idempotency = idempotency;
    }

    /** 会话启动：可凭授权会话号承接，也可凭指纹/桩/时刻直启（并发时唯一约束裁决）。 */
    @Transactional
    public SessionResponse start(StartSessionRequest request) {
        var result = idempotency.execute(
                request.requestId(), "START", request, 201, SessionResponse.class,
                () -> doStart(request));
        SessionResponse body = result.body();
        return new SessionResponse(body.sessionNo(), body.status(), body.startTime(), body.endTime(),
                body.totalEnergyKwh(), result.replayed());
    }

    private SessionResponse doStart(StartSessionRequest request) {
        Instant now = Instant.now(clock);
        if (request.sessionNo() != null && !request.sessionNo().isBlank()) {
            SessionRow session = getSession(request.sessionNo());
            if ("AUTHORIZED".equals(session.status())) {
                sessionRepository.markCharging(session.id(), now);
                session = new SessionRow(session.id(), session.sessionNo(), session.certFingerprint(),
                        session.pileNo(), session.homeOperatorId(), session.servingOperatorId(),
                        "CHARGING", session.startTime(), session.endTime(), session.settlementDay(),
                        session.totalEnergyKwh(), session.accountedSeq(), session.batchId(),
                        session.createdAt(), now);
            } else if (!"CHARGING".equals(session.status())) {
                throw new UnprocessableException("会话已结束，不能重复启动：" + session.sessionNo());
            }
            return toResponse(session, false);
        }

        if (request.certFingerprint() == null || request.certFingerprint().isBlank()
                || request.pileNo() == null || request.pileNo().isBlank() || request.startTime() == null) {
            throw new UnprocessableException("直启须提供证书指纹、桩编号与启动时刻");
        }
        VehicleRow vehicle = referenceDataRepository.findVehicle(request.certFingerprint())
                .orElseThrow(() -> new NotFoundException("车辆不存在：" + request.certFingerprint()));
        if (!vehicle.enabled()) {
            throw new UnprocessableException("车辆已停用，不允许启动充电");
        }
        PileRow pile = referenceDataRepository.findPile(request.pileNo())
                .orElseThrow(() -> new NotFoundException("充电桩不存在：" + request.pileNo()));
        if (!pile.enabled()) {
            throw new UnprocessableException("充电桩已停用，不允许启动充电");
        }
        SessionRow row = new SessionRow(
                null, newSessionNo(request.startTime()), request.certFingerprint(), request.pileNo(),
                vehicle.homeOperatorId(), pile.operatorId(), "CHARGING", request.startTime(),
                null, null, BigDecimal.ZERO, -1, null, now, now);
        try {
            sessionRepository.insert(row);
        } catch (DuplicateKeyException e) {
            // 与授权或另一路启动并发：同车同刻只允许一个会话成功，竞争失败方 409。
            throw new ConflictException("同车同刻已存在会话，唯一会话竞争失败");
        }
        return toResponse(row, false);
    }

    /** 会话结束；与清分封存经同一 UTC 日行锁串行，归属唯一批次。 */
    @Transactional
    public SessionResponse end(String sessionNo, EndSessionRequest request) {
        var result = idempotency.execute(
                request.requestId(), "STOP", java.util.List.of(sessionNo, request), 200,
                SessionResponse.class, () -> doEnd(sessionNo, request.endTime()));
        SessionResponse body = result.body();
        return new SessionResponse(body.sessionNo(), body.status(), body.startTime(), body.endTime(),
                body.totalEnergyKwh(), result.replayed());
    }

    private SessionResponse doEnd(String sessionNo, Instant endTime) {
        Instant now = Instant.now(clock);
        SessionRow session = getSession(sessionNo);
        if ("ENDED".equals(session.status())) {
            return toResponse(session, false);
        }
        if (!"CHARGING".equals(session.status())) {
            throw new UnprocessableException("会话尚未启动充电，不能结束：" + sessionNo);
        }
        if (endTime.isBefore(session.startTime())) {
            throw new UnprocessableException("结束时刻不能早于启动时刻");
        }
        LocalDate day = LocalDate.ofInstant(endTime, ZoneOffset.UTC);

        // 与封存互斥：行锁在本事务提交前持有，保证“计入本批”与“留待下批”互斥且完备。
        settlementRepository.insertDayLockIfAbsent(day);
        var dayLock = settlementRepository.lockDay(day);
        if (dayLock.sealed()) {
            // 当日批次已终态封存：结束事务回滚，会话绝不静默游离或重复计入，由业务侧改日/挂账处理。
            throw new ConflictException("当日清分批已封存，会话结束与封存并发竞争失败：" + day);
        }

        BigDecimal totalEnergy = meterRepository.findLatest(sessionNo)
                .map(SnapshotRow::energyKwh).orElse(BigDecimal.ZERO);
        sessionRepository.markEnded(session.id(), endTime, day, totalEnergy, now);

        SessionRow ended = new SessionRow(session.id(), session.sessionNo(), session.certFingerprint(),
                session.pileNo(), session.homeOperatorId(), session.servingOperatorId(), "ENDED",
                session.startTime(), endTime, day, totalEnergy, session.accountedSeq(), session.batchId(),
                session.createdAt(), now);
        return toResponse(ended, false);
    }

    /** 计量快照上传：去重、乱序归位；封存后迟到则差异挂账。 */
    @Transactional
    public MeterSnapshotResponse meter(String sessionNo, MeterSnapshotRequest request) {
        var result = idempotency.execute(
                request.requestId(), "METER", java.util.List.of(sessionNo, request), 201,
                MeterSnapshotResponse.class, () -> doMeter(sessionNo, request));
        MeterSnapshotResponse body = result.body();
        return new MeterSnapshotResponse(body.sessionNo(), body.readingSeq(), body.energyKwh(),
                body.deduplicated(), body.totalEnergyKwh(), body.suspended(), body.suspenseNo(),
                result.replayed());
    }

    private MeterSnapshotResponse doMeter(String sessionNo, MeterSnapshotRequest request) {
        Instant now = Instant.now(clock);
        SessionRow session = getSession(sessionNo);
        if (request.readingSeq() < 0) {
            throw new UnprocessableException("抄表序号不能为负");
        }

        SnapshotRow incoming = new SnapshotRow(null, sessionNo, request.readingSeq(),
                request.energyKwh(), request.recordedAt(), request.requestId(), now);
        boolean inserted = meterRepository.insertIfAbsent(incoming);
        boolean deduplicated = !inserted;
        String suspenseNo = null;
        boolean suspended = false;

        if (!inserted) {
            // 重传同序号但读数不一致属于计量数据冲突，不覆盖、不计费。
            SnapshotRow existing = meterRepository.find(sessionNo, request.readingSeq()).orElseThrow();
            if (existing.energyKwh().compareTo(request.energyKwh()) != 0
                    || !existing.recordedAt().equals(request.recordedAt())) {
                throw new ConflictException("抄表序号 " + request.readingSeq() + " 已存在且计量数据不一致");
            }
        }

        List<SnapshotRow> all = meterRepository.findBySession(sessionNo);
        if (inserted && !isMonotonic(all)) {
            // 读数倒退等非法计量：随事务回滚，快照不落库、不计费。
            throw new UnprocessableException("电表累计读数必须随抄表序号单调不减");
        }
        SnapshotRow latest = all.stream().max(Comparator.comparing(SnapshotRow::readingSeq))
                .orElseThrow(() -> new UnprocessableException("计量数据缺失"));

        if (session.batchId() == null) {
            sessionRepository.updateTotalEnergy(session.id(), latest.energyKwh(), now);
        } else if (inserted) {
            // 会话已封存：迟到快照一律进入差异挂账（含更早序号的补传），人工核销。
            suspenseNo = createSuspense(session, incoming, all, now);
            suspended = true;
        }

        return new MeterSnapshotResponse(sessionNo, request.readingSeq(), request.energyKwh(),
                deduplicated, latest.energyKwh(), suspended, suspenseNo, false);
    }

    /**
     * 计算本次迟到快照的边际差异：已封存读数 E0 之上，扣除此前已挂账增量，
     * 保证乱序/连续迟到快照挂账电量之和恰为“最终最大读数 − E0”，不重复计费。
     */
    private String createSuspense(SessionRow session, SnapshotRow incoming,
                                  List<SnapshotRow> allAfterInsert, Instant now) {
        var batch = settlementRepository.findBatchById(session.batchId())
                .orElseThrow(() -> new IllegalStateException("已归属批次不存在：" + session.batchId()));
        var home = referenceDataRepository.findOperator(session.homeOperatorId())
                .orElseThrow(() -> new IllegalStateException("归属运营商不存在"));
        var serving = referenceDataRepository.findOperator(session.servingOperatorId())
                .orElseThrow(() -> new IllegalStateException("服务运营商不存在"));

        BigDecimal accountedEnergy = session.totalEnergyKwh();
        BigDecimal priorLateMax = allAfterInsert.stream()
                .filter(s -> !s.readingSeq().equals(incoming.readingSeq()))
                .filter(s -> s.readingSeq() > session.accountedSeq())
                .map(SnapshotRow::energyKwh)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        BigDecimal baseForDelta = accountedEnergy.max(priorLateMax);
        BigDecimal deltaEnergy = incoming.energyKwh().subtract(baseForDelta).max(BigDecimal.ZERO);
        BigDecimal receivableDelta = MoneyUtil.yuan(deltaEnergy.multiply(home.tariffReceivable()));
        BigDecimal payableDelta = MoneyUtil.yuan(deltaEnergy.multiply(serving.tariffPayable()));

        String no = "SUS-" + batch.batchNo() + "-" + session.id() + "-" + incoming.readingSeq();
        SuspenseRow row = new SuspenseRow(null, no, session.id(), session.sessionNo(), session.batchId(),
                incoming.readingSeq(), deltaEnergy, receivableDelta, payableDelta,
                "封存后迟到计量快照（序号" + incoming.readingSeq() + "）", "PENDING", now);
        try {
            settlementRepository.insertSuspense(row);
        } catch (DuplicateKeyException e) {
            // 同一会话同一序号只挂一次账（与 requestId 幂等共同兜底）。
            throw new ConflictException("该迟到快照已挂账：" + no);
        }
        return no;
    }

    private boolean isMonotonic(List<SnapshotRow> orderedBySeq) {
        for (int i = 1; i < orderedBySeq.size(); i++) {
            if (orderedBySeq.get(i).energyKwh().compareTo(orderedBySeq.get(i - 1).energyKwh()) < 0) {
                return false;
            }
        }
        return true;
    }

    private SessionRow getSession(String sessionNo) {
        return sessionRepository.findBySessionNo(sessionNo)
                .orElseThrow(() -> new NotFoundException("充电会话不存在：" + sessionNo));
    }

    private SessionResponse toResponse(SessionRow session, boolean replayed) {
        return new SessionResponse(session.sessionNo(), session.status(), session.startTime(),
                session.endTime(), session.totalEnergyKwh(), replayed);
    }

    private String newSessionNo(Instant startTime) {
        return "SESS-" + startTime.toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
