package com.example.starter.wbb.service;

import com.example.starter.wbb.domain.ManifestStatus;
import com.example.starter.wbb.domain.PayloadCategory;
import com.example.starter.wbb.domain.ReviewRole;
import com.example.starter.wbb.dto.CheckinRequest;
import com.example.starter.wbb.dto.CloseRequest;
import com.example.starter.wbb.dto.FuelChangeRequest;
import com.example.starter.wbb.dto.LateEventResponse;
import com.example.starter.wbb.dto.LmcRequest;
import com.example.starter.wbb.dto.LoadConfirmRequest;
import com.example.starter.wbb.dto.ManifestResponse;
import com.example.starter.wbb.dto.PayloadItemDto;
import com.example.starter.wbb.dto.PreplanRequest;
import com.example.starter.wbb.dto.ReviewRequest;
import com.example.starter.wbb.exception.BusinessRuleException;
import com.example.starter.wbb.exception.IdempotencyConflictException;
import com.example.starter.wbb.exception.NotFoundException;
import com.example.starter.wbb.po.AircraftConfig;
import com.example.starter.wbb.po.EnvelopePointPo;
import com.example.starter.wbb.po.IdempotentRequestPo;
import com.example.starter.wbb.po.LateEventPo;
import com.example.starter.wbb.po.ManifestPo;
import com.example.starter.wbb.po.PayloadItemPo;
import com.example.starter.wbb.po.ReviewPo;
import com.example.starter.wbb.repo.WbbRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 载重平衡舱单核心领域服务：预配→值机→装载确认→双岗复核→放行关闭。
 * <p>所有写操作在单事务内完成；requestId 指纹幂等；油量与重心同事务联动；
 * LMC 超限重打新版本；关闭为终态，迟到变化只记事件并告警。
 */
@Service
public class ManifestService {

    static final String BIZ_PREPLAN = "PREPLAN";
    static final String BIZ_CHECKIN = "CHECKIN";
    static final String BIZ_LOAD_CONFIRM = "LOAD_CONFIRM";
    static final String BIZ_LMC = "LMC";
    static final String BIZ_FUEL = "FUEL_CHANGE";
    static final String BIZ_REVIEW = "REVIEW";
    static final String BIZ_CLOSE = "CLOSE";

    private final WbbRepository repo;
    private final Clock clock;

    public ManifestService(WbbRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    // ============================ 预配 ============================

    @Transactional
    public ManifestResponse preplan(PreplanRequest req) {
        String fp = fingerprint(BIZ_PREPLAN, req.requestId(),
                req.flightNo() + "|" + req.flightDate() + "|" + req.aircraftType() + "|"
                        + req.aircraftRegistration() + "|" + norm(req.fuelKg()) + "|"
                        + norm(emptyIfNull(req.fuelArm())) + "|" + canonicalItems(req.items()));
        Optional<ManifestResponse> replay = acquireIdempotency(req.requestId(), BIZ_PREPLAN, fp);
        if (replay.isPresent()) {
            return replay.get();
        }

        AircraftConfig config = repo.findConfig(req.aircraftType())
                .orElseThrow(() -> new NotFoundException("机型不存在: " + req.aircraftType()));
        if (req.fuelKg().signum() < 0) {
            throw new BusinessRuleException("加油量不得为负");
        }
        String flightKey = flightKey(req.flightNo(), req.flightDate());
        if (repo.findActiveManifestId(flightKey).isPresent()) {
            throw new BusinessRuleException("航班已存在有效舱单版本，同一航班同时刻仅允许一个有效版本");
        }

        List<PayloadItemDto> items = req.items() == null ? List.of() : req.items();
        Balance calc = compute(config, items, req.fuelKg(),
                req.fuelArm() == null ? config.defaultFuelArm() : req.fuelArm());
        calc.assertValid();

        ManifestPo po = new ManifestPo(null, flightKey, req.flightNo(), req.flightDate(),
                req.aircraftType(), req.aircraftRegistration(), 1,
                ManifestStatus.PREPARED.name(), null, req.requestId(), fp,
                calc.fuelKg, calc.fuelArm, calc.fuelMoment, calc.totals.passengerCount(),
                calc.totals.weightKg(), calc.totals.moment(), calc.zfw, calc.zfwMoment, calc.zfwCg,
                calc.tow, calc.towMoment, calc.towCg, 0, BigDecimal.ZERO, false, null, null, null);
        long id;
        try {
            id = repo.insertManifest(po);
            repo.insertActiveManifest(flightKey, id, 1);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("航班已存在有效舱单版本，同一航班同时刻仅允许一个有效版本");
        }
        for (PayloadItemDto item : items) {
            insertItem(id, item);
        }
        repo.updateIdempotentOutcome(req.requestId(), id, "PREPARED");
        return toResponse(repo.findManifest(id).orElseThrow(), calc);
    }

    // ============================ 值机 ============================

    @Transactional
    public ManifestResponse checkin(String flightKey, CheckinRequest req) {
        String fp = fingerprint(BIZ_CHECKIN, req.requestId(),
                flightKey + "|" + canonicalItems(req.items()));
        Optional<ManifestResponse> replay = acquireIdempotency(req.requestId(), BIZ_CHECKIN, fp);
        if (replay.isPresent()) {
            return replay.get();
        }
        ManifestPo m = lockActive(flightKey);
        List<PayloadItemDto> items = req.items() == null ? List.of() : req.items();

        if (ManifestStatus.CLOSED.name().equals(m.status())) {
            return lateChange(flightKey, m, "CHECK_IN", req.requestId(), fp,
                    "关闭后收到值机全量明细 " + items.size() + " 行，未写入舱单");
        }
        assertNotFrozen(m);
        if (!Set.of(ManifestStatus.PREPARED.name(), ManifestStatus.CHECK_IN.name()).contains(m.status())) {
            throw new BusinessRuleException("当前状态 " + m.status() + " 不允许值机上载，装载确认后请走 LMC");
        }

        AircraftConfig config = configOf(m);
        Balance calc = compute(config, items, m.fuelKg(), m.fuelArm());
        calc.assertValid();

        repo.deletePayloadItems(m.id());
        for (PayloadItemDto item : items) {
            insertItem(m.id(), item);
        }
        ManifestPo updated = withComputed(m, ManifestStatus.CHECK_IN.name(), calc, m.lmcDeltaPax(),
                m.lmcDeltaKg(), m.frozen(), m.closedAt());
        repo.updateComputedManifest(updated);
        repo.updateIdempotentOutcome(req.requestId(), m.id(), ManifestStatus.CHECK_IN.name());
        return toResponse(repo.findManifest(m.id()).orElseThrow(), calc);
    }

    // ============================ 装载确认 ============================

    @Transactional
    public ManifestResponse loadConfirm(String flightKey, LoadConfirmRequest req) {
        String fp = fingerprint(BIZ_LOAD_CONFIRM, req.requestId(), flightKey);
        Optional<ManifestResponse> replay = acquireIdempotency(req.requestId(), BIZ_LOAD_CONFIRM, fp);
        if (replay.isPresent()) {
            return replay.get();
        }
        ManifestPo m = lockActive(flightKey);
        if (ManifestStatus.LOADED.name().equals(m.status())
                || ManifestStatus.REVIEWED.name().equals(m.status())
                || ManifestStatus.CLOSED.name().equals(m.status())) {
            throw new BusinessRuleException("舱单已完成装载确认，不可重复确认");
        }
        if (!Set.of(ManifestStatus.PREPARED.name(), ManifestStatus.CHECK_IN.name()).contains(m.status())) {
            throw new BusinessRuleException("当前状态 " + m.status() + " 不允许装载确认");
        }
        repo.updateStatus(m.id(), ManifestStatus.LOADED.name());
        repo.updateIdempotentOutcome(req.requestId(), m.id(), ManifestStatus.LOADED.name());
        return toResponse(repo.findManifest(m.id()).orElseThrow(), balanceOf(m));
    }

    // ============================ LMC ============================

    @Transactional
    public ManifestResponse lmc(String flightKey, LmcRequest req) {
        String fp = fingerprint(BIZ_LMC, req.requestId(),
                flightKey + "|" + canonicalItems(req.deltaItems()));
        Optional<ManifestResponse> replay = acquireIdempotency(req.requestId(), BIZ_LMC, fp);
        if (replay.isPresent()) {
            return replay.get();
        }
        ManifestPo m = lockActive(flightKey);
        List<PayloadItemDto> deltas = req.deltaItems() == null ? List.of() : req.deltaItems();
        BalanceCalculator.PayloadTotals deltaTotals = BalanceCalculator.sumPayload(deltas);
        int newDeltaPax = m.lmcDeltaPax() + deltaTotals.passengerCount();
        BigDecimal newDeltaKg = m.lmcDeltaKg().add(deltaTotals.weightKg());

        if (ManifestStatus.CLOSED.name().equals(m.status())) {
            return lateChange(flightKey, m, "LMC", req.requestId(), fp,
                    "关闭后收到 LMC：人数增量 " + newDeltaPax + "，重量增量 " + newDeltaKg + "kg，未写入舱单");
        }
        assertNotFrozen(m);
        if (!ManifestStatus.LOADED.name().equals(m.status())) {
            throw new BusinessRuleException("仅装载确认后的舱单可进行 LMC，当前状态 " + m.status());
        }

        AircraftConfig config = configOf(m);
        boolean withinLimit = BalanceCalculator.withinLmcLimit(newDeltaPax, newDeltaKg);

        if (withinLimit) {
            // 限额内：在当前版本原地追加增量行，累计增量落在 ±5 人 / ±500kg 内。
            List<PayloadItemPo> existing = repo.findPayloadItems(m.id());
            List<PayloadItemDto> projected = new ArrayList<>(toDtos(existing));
            projected.addAll(deltas);
            Balance calc = compute(config, projected, m.fuelKg(), m.fuelArm());
            calc.assertValid();
            for (PayloadItemDto d : deltas) {
                insertItem(m.id(), d);
            }
            ManifestPo updated = withComputed(m, ManifestStatus.LOADED.name(), calc, newDeltaPax,
                    newDeltaKg, false, null);
            repo.updateComputedManifest(updated);
            repo.updateIdempotentOutcome(req.requestId(), m.id(), "LMC_IN_PLACE");
            return toResponse(repo.findManifest(m.id()).orElseThrow(), calc);
        }

        // 超限：重打新版本（全量明细 = 旧明细 + 本次增量），旧版本 SUPERSEDED，版本链接续。
        List<PayloadItemDto> projected = new ArrayList<>(toDtos(repo.findPayloadItems(m.id())));
        projected.addAll(deltas);
        Balance calc = compute(config, projected, m.fuelKg(), m.fuelArm());
        calc.assertValid();

        int newVersion = m.versionNo() + 1;
        ManifestPo reprint = new ManifestPo(null, flightKey, m.flightNo(), m.flightDate(),
                m.aircraftType(), m.aircraftRegistration(), newVersion,
                ManifestStatus.LOADED.name(), m.id(), req.requestId(), fp,
                calc.fuelKg, calc.fuelArm, calc.fuelMoment, calc.totals.passengerCount(),
                calc.totals.weightKg(), calc.totals.moment(), calc.zfw, calc.zfwMoment, calc.zfwCg,
                calc.tow, calc.towMoment, calc.towCg, 0, BigDecimal.ZERO, false, null, null, null);
        long newId = repo.insertManifest(reprint);
        for (PayloadItemDto item : projected) {
            insertItem(newId, item);
        }
        repo.updateStatus(m.id(), ManifestStatus.SUPERSEDED.name());
        repo.updateActiveManifest(flightKey, newId, newVersion);
        repo.updateIdempotentOutcome(req.requestId(), newId, "REPRINTED_V" + newVersion);
        return toResponse(repo.findManifest(newId).orElseThrow(), calc);
    }

    // ============================ 加油变更 ============================

    @Transactional
    public ManifestResponse changeFuel(String flightKey, FuelChangeRequest req) {
        String fp = fingerprint(BIZ_FUEL, req.requestId(),
                flightKey + "|" + norm(req.fuelKg()) + "|" + norm(emptyIfNull(req.fuelArm())));
        Optional<ManifestResponse> replay = acquireIdempotency(req.requestId(), BIZ_FUEL, fp);
        if (replay.isPresent()) {
            return replay.get();
        }
        ManifestPo m = lockActive(flightKey);
        if (req.fuelKg().signum() < 0) {
            throw new BusinessRuleException("加油量不得为负");
        }
        if (ManifestStatus.CLOSED.name().equals(m.status())) {
            return lateChange(flightKey, m, "FUEL_CHANGE", req.requestId(), fp,
                    "关闭后收到加油量变更 " + req.fuelKg() + "kg，未改油也未重算重心");
        }
        assertNotFrozen(m);

        AircraftConfig config = configOf(m);
        BigDecimal fuelArm = req.fuelArm() == null ? config.defaultFuelArm() : req.fuelArm();
        List<PayloadItemDto> items = toDtos(repo.findPayloadItems(m.id()));
        // 油量变更与重心重算在同一事务、同一 Balance 内完成，杜绝改油不改重心。
        Balance calc = compute(config, items, req.fuelKg(), fuelArm);
        calc.assertValid();
        ManifestPo updated = withComputed(m, m.status(), calc, m.lmcDeltaPax(), m.lmcDeltaKg(),
                m.frozen(), m.closedAt());
        repo.updateComputedManifest(updated);
        repo.updateIdempotentOutcome(req.requestId(), m.id(), "FUEL_RECALCULATED");
        return toResponse(repo.findManifest(m.id()).orElseThrow(), calc);
    }

    // ============================ 双岗复核 ============================

    @Transactional
    public ManifestResponse review(String flightKey, ReviewRequest req) {
        ReviewRole role;
        try {
            role = ReviewRole.valueOf(req.role().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("非法复核岗位: " + req.role() + "，仅支持 WBO/DSP");
        }
        String fp = fingerprint(BIZ_REVIEW, req.requestId(),
                flightKey + "|" + role.name() + "|" + req.reviewer());
        Optional<ManifestResponse> replay = acquireIdempotency(req.requestId(), BIZ_REVIEW, fp);
        if (replay.isPresent()) {
            return replay.get();
        }
        ManifestPo m = lockActive(flightKey);
        if (ManifestStatus.CLOSED.name().equals(m.status())) {
            throw new BusinessRuleException("舱单已放行关闭，不可再复核");
        }
        if (!ManifestStatus.LOADED.name().equals(m.status())) {
            throw new BusinessRuleException("仅装载确认后的舱单可复核，当前状态 " + m.status());
        }
        List<ReviewPo> existing = repo.findReviews(m.id());
        for (ReviewPo r : existing) {
            if (r.reviewRole().equals(role.name())) {
                throw new BusinessRuleException(role.name() + " 岗已完成复核，不可重复签字");
            }
            if (r.reviewer().equals(req.reviewer())) {
                throw new BusinessRuleException("双岗复核须由不同人员完成，" + req.reviewer() + " 已签另一岗");
            }
        }
        try {
            repo.insertReview(new ReviewPo(null, m.id(), role.name(), req.reviewer(),
                    req.requestId(), fp, null));
        } catch (DuplicateKeyException e) {
            // 同岗位并发复核：唯一约束保证只有一笔成功。
            throw new BusinessRuleException(role.name() + " 岗复核并发冲突，仅一笔签字成功");
        }
        List<ReviewPo> after = repo.findReviews(m.id());
        boolean bothSigned = after.stream().anyMatch(r -> r.reviewRole().equals(ReviewRole.WBO.name()))
                && after.stream().anyMatch(r -> r.reviewRole().equals(ReviewRole.DSP.name()));
        String outcome;
        if (bothSigned) {
            // 两岗齐签方生效：复核通过，舱单冻结。
            ManifestPo frozen = withComputed(m, ManifestStatus.REVIEWED.name(), balanceOf(m),
                    m.lmcDeltaPax(), m.lmcDeltaKg(), true, null);
            repo.updateComputedManifest(frozen);
            outcome = "REVIEWED_FROZEN";
        } else {
            outcome = role.name() + "_SIGNED";
        }
        repo.updateIdempotentOutcome(req.requestId(), m.id(), outcome);
        return toResponse(repo.findManifest(m.id()).orElseThrow(), balanceOf(m));
    }

    // ============================ 放行关闭 ============================

    @Transactional
    public ManifestResponse close(String flightKey, CloseRequest req) {
        ManifestPo m = lockActive(flightKey);
        if (ManifestStatus.CLOSED.name().equals(m.status())) {
            // 终态：重复关闭始终幂等成功，即使 requestId 不同或为空。
            return toResponse(m, balanceOf(m));
        }
        String fp = null;
        if (req.requestId() != null && !req.requestId().isBlank()) {
            fp = fingerprint(BIZ_CLOSE, req.requestId(), flightKey);
            Optional<ManifestResponse> replay = acquireIdempotency(req.requestId(), BIZ_CLOSE, fp);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (!ManifestStatus.REVIEWED.name().equals(m.status()) || !Boolean.TRUE.equals(m.frozen())) {
            throw new BusinessRuleException("须双岗复核通过并冻结后方可放行关闭，单人签字不生效");
        }
        ManifestPo closed = withComputed(m, ManifestStatus.CLOSED.name(), balanceOf(m),
                m.lmcDeltaPax(), m.lmcDeltaKg(), true, now());
        repo.updateComputedManifest(closed);
        if (fp != null) {
            repo.updateIdempotentOutcome(req.requestId(), m.id(), ManifestStatus.CLOSED.name());
        }
        return toResponse(repo.findManifest(m.id()).orElseThrow(), balanceOf(m));
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public ManifestResponse getActive(String flightKey) {
        Long id = repo.findActiveManifestId(flightKey)
                .orElseThrow(() -> new NotFoundException("航班无有效舱单: " + flightKey));
        ManifestPo m = repo.findManifest(id).orElseThrow();
        return toResponse(m, balanceOf(m));
    }

    @Transactional(readOnly = true)
    public ManifestResponse getById(long id) {
        ManifestPo m = repo.findManifest(id)
                .orElseThrow(() -> new NotFoundException("舱单版本不存在: " + id));
        return toResponse(m, balanceOf(m));
    }

    @Transactional(readOnly = true)
    public List<ManifestResponse> versions(String flightKey) {
        return repo.findVersionChain(flightKey).stream()
                .map(m -> toResponse(m, balanceOf(m)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LateEventResponse> lateEvents(String flightKey) {
        return repo.findLateEvents(flightKey).stream()
                .map(e -> new LateEventResponse(e.id(), e.flightKey(), e.manifestId(),
                        e.eventType(), e.detail(), e.alarmed(), e.createdAt().toString()))
                .toList();
    }

    // ============================ 内部辅助 ============================

    private java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(clock);
    }

    private ManifestPo lockActive(String flightKey) {
        Long id = repo.lockActiveManifestId(flightKey)
                .orElseThrow(() -> new NotFoundException("航班无有效舱单: " + flightKey));
        return repo.findManifest(id).orElseThrow();
    }

    private AircraftConfig configOf(ManifestPo m) {
        return repo.findConfig(m.aircraftType())
                .orElseThrow(() -> new NotFoundException("机型不存在: " + m.aircraftType()));
    }

    private ManifestResponse lateChange(String flightKey, ManifestPo m, String eventType,
                                        String requestId, String fp, String detail) {
        // 终态保护：不改舱单，仅记迟到事件并告警。
        long eventId = repo.insertLateEvent(flightKey, m.id(), eventType, detail);
        repo.updateIdempotentOutcome(requestId, m.id(), "LATE_EVENT_" + eventId);
        return toResponse(m, balanceOf(m));
    }

    /**
     * 幂等占位结果：空表示当前事务拿到占位、应执行业务；非空表示同键同参重放的首次结果。
     */
    private Optional<ManifestResponse> acquireIdempotency(String requestId, String bizType, String fingerprint) {
        IdempotentRequestPo existing = repo.lockIdempotent(requestId).orElse(null);
        final IdempotentRequestPo seen;
        if (existing == null) {
            if (repo.tryInsertPendingIdempotent(requestId, bizType, fingerprint)) {
                // 本事务插入 PENDING 成功，执行业务。
                return Optional.empty();
            }
            // 并发插入落败：行锁等待首笔事务结束后重读其已提交结果。
            seen = repo.lockIdempotent(requestId)
                    .orElseThrow(() -> new IllegalStateException("幂等记录缺失: " + requestId));
        } else {
            seen = existing;
        }
        if (!seen.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(requestId);
        }
        if ("PENDING".equals(seen.outcome())) {
            // 仅可能是本事务刚插入的占位行。
            return Optional.empty();
        }
        final Long firstManifestId = seen.manifestId();
        // 同键同参：返回首次结果（即便首版已被重打取代，也返回当时的版本）。
        return Optional.of(repo.findManifest(firstManifestId)
                .map(m -> toResponse(m, balanceOf(m)))
                .orElseThrow(() -> new IllegalStateException("首次结果舱单缺失: " + firstManifestId)));
    }

    private void insertItem(long manifestId, PayloadItemDto item) {
        PayloadCategory.valueOf(item.category());
        BigDecimal moment = item.weightKg().multiply(item.arm())
                .setScale(2, RoundingMode.HALF_UP);
        repo.insertPayloadItem(manifestId, new PayloadItemPo(null, manifestId,
                item.category(), item.zoneCode(), item.pieceCount(),
                item.weightKg(), item.arm(), moment));
    }

    private List<PayloadItemDto> toDtos(List<PayloadItemPo> rows) {
        return rows.stream()
                .map(r -> new PayloadItemDto(r.category(), r.zoneCode(), r.pieceCount(),
                        r.weightKg(), r.arm()))
                .toList();
    }

    private ManifestPo withComputed(ManifestPo m, String status, Balance c, int lmcPax,
                                    BigDecimal lmcKg, boolean frozen,
                                    java.time.LocalDateTime closedAt) {
        return new ManifestPo(m.id(), m.flightKey(), m.flightNo(), m.flightDate(),
                m.aircraftType(), m.aircraftRegistration(), m.versionNo(), status, m.parentId(),
                m.requestId(), m.fingerprint(), c.fuelKg, c.fuelArm, c.fuelMoment,
                c.totals.passengerCount(), c.totals.weightKg(), c.totals.moment(),
                c.zfw, c.zfwMoment, c.zfwCg, c.tow, c.towMoment, c.towCg,
                lmcPax, lmcKg, frozen, closedAt, m.createdAt(), now());
    }

    private Balance balanceOf(ManifestPo m) {
        AircraftConfig config = configOf(m);
        List<EnvelopePointPo> pts = repo.findEnvelopePoints(config.id());
        return new Balance(toDtos(repo.findPayloadItems(m.id())),
                m.fuelKg(), m.fuelArm(), m.fuelMoment(),
                new BalanceCalculator.PayloadTotals(m.payloadKg(), m.payloadMoment(), m.passengerCount()),
                m.zeroFuelWeightKg(), m.zfwMoment(), m.zfwCgArm(),
                m.takeoffWeightKg(), m.towMoment(), m.towCgArm(),
                config, envelope(pts));
    }

    private ManifestResponse toResponse(ManifestPo m, Balance c) {
        List<ReviewPo> reviews = repo.findReviews(m.id());
        boolean wbo = reviews.stream().anyMatch(r -> r.reviewRole().equals(ReviewRole.WBO.name()));
        boolean dsp = reviews.stream().anyMatch(r -> r.reviewRole().equals(ReviewRole.DSP.name()));
        return new ManifestResponse(m.id(), m.flightKey(), m.flightNo(), m.flightDate(),
                m.aircraftType(), m.aircraftRegistration(), m.versionNo(), m.status(), m.parentId(),
                m.requestId(), m.fuelKg(), m.fuelArm(), m.passengerCount(), m.payloadKg(),
                m.zeroFuelWeightKg(), m.zfwCgArm(), m.takeoffWeightKg(), m.towCgArm(),
                m.lmcDeltaPax(), m.lmcDeltaKg(), m.frozen(),
                c.envelopeOk(), c.payloadLimitOk(), wbo, dsp, m.closedAt(), m.createdAt(),
                toDtos(repo.findPayloadItems(m.id())));
    }

    private Balance compute(AircraftConfig config, List<PayloadItemDto> items,
                            BigDecimal fuelKg, BigDecimal fuelArm) {
        BalanceCalculator.PayloadTotals totals = BalanceCalculator.sumPayload(items);
        BigDecimal fuelMoment = fuelKg.multiply(fuelArm).setScale(2, RoundingMode.HALF_UP);
        BigDecimal zfw = config.operatingEmptyWeightKg().add(totals.weightKg());
        BigDecimal zfwMoment = config.operatingEmptyMoment().add(totals.moment());
        BigDecimal zfwCg = BalanceCalculator.cgArm(zfwMoment, zfw);
        BigDecimal tow = zfw.add(fuelKg);
        BigDecimal towMoment = zfwMoment.add(fuelMoment);
        BigDecimal towCg = BalanceCalculator.cgArm(towMoment, tow);
        List<EnvelopePointPo> pts = repo.findEnvelopePoints(config.id());
        return new Balance(items, fuelKg, fuelArm, fuelMoment, totals, zfw, zfwMoment, zfwCg,
                tow, towMoment, towCg, config, envelope(pts));
    }

    private List<BalanceCalculator.EnvelopePoint> envelope(List<EnvelopePointPo> pts) {
        return pts.stream()
                .map(p -> new BalanceCalculator.EnvelopePoint(p.weightKg(), p.forwardArm(), p.aftArm()))
                .toList();
    }

    private void assertNotFrozen(ManifestPo m) {
        if (Boolean.TRUE.equals(m.frozen())
                && !ManifestStatus.CLOSED.name().equals(m.status())) {
            throw new BusinessRuleException("舱单已双岗复核冻结，业载/油量不可修改，如需变更须重打舱单");
        }
    }

    /**
     * 一次完整重量平衡计算的不可变结果包。
     */
    private record Balance(
            List<PayloadItemDto> items,
            BigDecimal fuelKg,
            BigDecimal fuelArm,
            BigDecimal fuelMoment,
            BalanceCalculator.PayloadTotals totals,
            BigDecimal zfw,
            BigDecimal zfwMoment,
            BigDecimal zfwCg,
            BigDecimal tow,
            BigDecimal towMoment,
            BigDecimal towCg,
            AircraftConfig config,
            List<BalanceCalculator.EnvelopePoint> envelopePoints
    ) {
        boolean payloadLimitOk() {
            return BalanceCalculator.withinZeroFuelLimit(totals.weightKg(),
                    config.operatingEmptyWeightKg(), config.maxZeroFuelWeightKg());
        }

        boolean envelopeOk() {
            return BalanceCalculator.withinEnvelope(zfw, zfwCg, envelopePoints)
                    && BalanceCalculator.withinEnvelope(tow, towCg, envelopePoints);
        }

        void assertValid() {
            if (!payloadLimitOk()) {
                throw new BusinessRuleException(
                        "总业载超过最大无油重量减使用空重（ZFW=" + zfw + "kg > MZFW="
                                + config.maxZeroFuelWeightKg() + "kg）");
            }
            if (!envelopeOk()) {
                throw new BusinessRuleException("重心超出合成包线：ZFW 力臂 " + zfwCg
                        + "m / TOW 力臂 " + towCg + "m");
            }
        }
    }

    private String fingerprint(String bizType, String requestId, String payload) {
        return Fingerprint.sha256(bizType + "|" + requestId + "|" + payload);
    }

    private String canonicalItems(List<PayloadItemDto> items) {
        if (items == null || items.isEmpty()) {
            return "EMPTY";
        }
        return items.stream()
                .map(i -> String.join("~", i.category(), i.zoneCode(),
                        String.valueOf(i.pieceCount()), norm(i.weightKg()), norm(i.arm())))
                .sorted()
                .reduce((a, b) -> a + ";" + b)
                .orElse("EMPTY");
    }

    private static String norm(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal emptyIfNull(BigDecimal v) {
        return v == null ? null : v;
    }

    /**
     * 航班业务键：航班号-业务日期。
     */
    public static String flightKey(String flightNo, LocalDate flightDate) {
        return flightNo + "-" + flightDate;
    }
}
