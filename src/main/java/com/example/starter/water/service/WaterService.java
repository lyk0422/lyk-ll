package com.example.starter.water.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.starter.water.domain.MeterRepository;
import com.example.starter.water.domain.PlanRepository;
import com.example.starter.water.domain.ReadingRepository;
import com.example.starter.water.domain.RestrictionRepository;
import com.example.starter.water.domain.WaterUserRepository;
import com.example.starter.water.domain.MeterRepository.MeterRow;
import com.example.starter.water.domain.PlanRepository.IdempotencyRow;
import com.example.starter.water.domain.PlanRepository.PlanVersionRow;
import com.example.starter.water.domain.ReadingRepository.MonthTotal;
import com.example.starter.water.domain.ReadingRepository.ReadingRow;
import com.example.starter.water.domain.RestrictionRepository.RestrictionRow;
import com.example.starter.water.domain.WaterUserRepository.AnnualQuotaRow;
import com.example.starter.water.domain.WaterUserRepository.UserRow;
import com.example.starter.water.dto.CreateMeterRequest;
import com.example.starter.water.dto.CreateUserRequest;
import com.example.starter.water.dto.DeclarationRequest;
import com.example.starter.water.dto.DeclarationResult;
import com.example.starter.water.dto.LedgerView;
import com.example.starter.water.dto.MeterView;
import com.example.starter.water.dto.ReadingRequest;
import com.example.starter.water.dto.ReadingResult;
import com.example.starter.water.dto.RestrictionRequest;
import com.example.starter.water.dto.RestrictionResult;
import com.example.starter.water.dto.SealResult;
import com.example.starter.water.dto.UserView;
import com.example.starter.water.support.BusinessRejectedException;
import com.example.starter.water.support.IdempotencyConflictException;
import com.example.starter.water.support.NotFoundException;
import com.example.starter.water.support.TimeProvider;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取水许可水量分配与计量对冲应用服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>申报校验只依赖“已确认额度快照”（许可总量 − 上年结转扣减 − 本自然年各月最后已确认版本），
 *       计量读数不参与申报校验；</li>
 *   <li>受理边界按受理水量比较：达到剩余额度可受理（含边界），超出则整月拒绝、不产生新版本；</li>
 *   <li>限取期间新申报减半受理（受理水量=申报水量/2，m³ 保留 3 位小数四舍五入），已确认计划不变；</li>
 *   <li>封存按实际读数结算超许可部分，1:1 结转次年额度，封存为终态。</li>
 * </ul>
 */
@Service
public class WaterService {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int VOLUME_SCALE = 3;

    private final WaterUserRepository userRepository;
    private final MeterRepository meterRepository;
    private final PlanRepository planRepository;
    private final ReadingRepository readingRepository;
    private final RestrictionRepository restrictionRepository;
    private final TimeProvider timeProvider;

    public WaterService(WaterUserRepository userRepository,
                        MeterRepository meterRepository,
                        PlanRepository planRepository,
                        ReadingRepository readingRepository,
                        RestrictionRepository restrictionRepository,
                        TimeProvider timeProvider) {
        this.userRepository = userRepository;
        this.meterRepository = meterRepository;
        this.planRepository = planRepository;
        this.readingRepository = readingRepository;
        this.restrictionRepository = restrictionRepository;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public UserView createUser(CreateUserRequest request) {
        Instant now = timeProvider.now();
        UserRow row = userRepository.create(request.name(), request.riverSection(),
                scale(request.annualQuota()), now);
        boolean restricted = restrictionRepository.findActive(row.riverSection()).isPresent();
        return new UserView(row.id(), row.name(), row.riverSection(), row.annualQuota(), restricted);
    }

    @Transactional
    public MeterView createMeter(Long userId, CreateMeterRequest request) {
        UserRow user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("取水户不存在: " + userId));
        MeterRow meter = meterRepository.create(user.id(), request.name());
        return new MeterView(meter.id(), meter.userId(), meter.name());
    }

    @Transactional
    public DeclarationResult declare(DeclarationRequest request) {
        YearMonth month = YearMonth.parse(request.month(), MONTH_FORMATTER);
        BigDecimal declaredVolume = scale(request.volume());

        // 1. 幂等记录优先：同键直接回放首次结果；异参在锁外即可判冲突。
        Optional<IdempotencyRow> existing = planRepository.findIdempotency(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(request, declaredVolume, existing.get());
        }

        Instant confirmedAt = timeProvider.now();

        // 2. 锁定取水户行：同取水户并发申报串行化；限取触发也锁同一行，二者形成唯一先后顺序。
        UserRow user = userRepository.lockById(request.userId());
        if (user == null) {
            throw new NotFoundException("取水户不存在: " + request.userId());
        }

        // 2.1 锁内二次检查幂等键，消除“锁外未见、并发请求抢先提交”的窗口。
        Optional<IdempotencyRow> concurrent = planRepository.findIdempotency(request.requestId());
        if (concurrent.isPresent()) {
            return replayOrConflict(request, declaredVolume, concurrent.get());
        }

        // 3. 封存终态：修改与迟到申报一律拒绝（拒绝结果同样幂等记录）。
        int year = month.getYear();
        AnnualQuotaRow quota = userRepository.getOrCreateAnnualQuota(user.id(), year, user.annualQuota());
        if (quota.sealed()) {
            return recordRejection(request, declaredVolume, confirmedAt, quota, null,
                    "年度计划已封存，拒绝迟到申报");
        }

        // 4. 限取判定：持锁后读取当前激活事件；生效时刻不晚于确认时刻（含同刻）即减半受理。
        Optional<RestrictionRow> active = restrictionRepository.findActive(user.riverSection());
        boolean restricted = active.isPresent()
                && !active.get().effectiveAt().isAfter(confirmedAt);
        Long restrictionId = restricted ? active.get().id() : null;
        BigDecimal acceptedVolume = restricted
                ? declaredVolume.divide(BigDecimal.valueOf(2), VOLUME_SCALE, RoundingMode.HALF_UP)
                : declaredVolume;

        // 5. 已确认额度快照：本自然年各月最后已确认版本；当月旧版本将被本次替换，需扣除。
        List<PlanVersionRow> latestVersions = planRepository.findLatestVersionsOfYear(user.id(), year);
        BigDecimal othersAccepted = BigDecimal.ZERO;
        for (PlanVersionRow v : latestVersions) {
            if (!v.month().equals(request.month())) {
                othersAccepted = othersAccepted.add(v.acceptedVolume());
            }
        }
        BigDecimal effectiveQuota = quota.permittedQuota().subtract(quota.carryoverDeduction());
        BigDecimal candidateOccupied = othersAccepted.add(acceptedVolume);

        if (candidateOccupied.compareTo(effectiveQuota) > 0) {
            // 超出剩余额度：整月拒绝，不产生新版本。
            return recordRejection(request, declaredVolume, confirmedAt, quota, restrictionId,
                    "申报超出许可剩余额度，整月申报拒绝");
        }

        // 6. 受理：追加确认版本并落幂等指纹；唯一约束兜底并发同 requestId。
        PlanVersionRow version;
        try {
            version = planRepository.appendVersion(user.id(), request.month(), declaredVolume,
                    acceptedVolume, restricted, restrictionId, request.requestId(), confirmedAt);
            planRepository.insertIdempotency(new IdempotencyRow(request.requestId(), user.id(),
                    request.month(), declaredVolume, version.id(), false, null, confirmedAt));
        } catch (DuplicateKeyException dup) {
            IdempotencyRow winner = planRepository.findIdempotency(request.requestId())
                    .orElseThrow(() -> dup);
            return replayOrConflict(request, declaredVolume, winner);
        }

        return new DeclarationResult(request.requestId(), user.id(), request.month(), "CONFIRMED",
                declaredVolume, acceptedVolume, version.version(), restricted,
                restricted ? active.get().effectiveAt().toString() : null, null);
    }

    @Transactional
    public ReadingResult reportReading(ReadingRequest request) {
        Instant readingTime = Instant.parse(request.readingTime());
        BigDecimal cumulative = scale(request.cumulative());

        MeterRow meter = meterRepository.findById(request.meterPointId())
                .orElseThrow(() -> new NotFoundException("计量点不存在: " + request.meterPointId()));

        // 锁外快速去重；锁内仍会再查一次以消除并发窗口。
        Optional<ReadingRow> duplicate = readingRepository.findByMeterAndTime(meter.id(), readingTime);
        if (duplicate.isPresent()) {
            return toReadingResult(duplicate.get(), true);
        }

        // 计量点父行锁串行化同点并发上报；读数不触碰申报额度。
        List<ReadingRow> ordered = readingRepository.lockOrderedReadings(meter.id());
        Optional<ReadingRow> concurrentDuplicate = readingRepository.findByMeterAndTime(
                meter.id(), readingTime);
        if (concurrentDuplicate.isPresent()) {
            return toReadingResult(concurrentDuplicate.get(), true);
        }

        // 封存终态：锁取水户行后校验读数归属年度是否已封存，迟到读数一律拒绝。
        String month = YearMonth.from(readingTime.atOffset(ZoneOffset.UTC)).format(MONTH_FORMATTER);
        int readingYear = Integer.parseInt(month.substring(0, 4));
        UserRow owner = userRepository.lockById(meter.userId());
        AnnualQuotaRow yearQuota = userRepository.getOrCreateAnnualQuota(
                owner.id(), readingYear, owner.annualQuota());
        if (yearQuota.sealed()) {
            throw new BusinessRejectedException(
                    readingYear + " 年度计划已封存，拒绝迟到计量读数: " + request.readingTime());
        }
        ReadingRow predecessor = null;
        ReadingRow successor = null;
        for (ReadingRow r : ordered) {
            if (r.readingTime().isBefore(readingTime) || r.readingTime().equals(readingTime)) {
                predecessor = r;
            } else if (successor == null) {
                successor = r;
            }
        }
        BigDecimal delta;
        if (predecessor == null) {
            // 首个表底：增量以表底值作为起始用量。
            delta = cumulative;
        } else {
            delta = cumulative.subtract(predecessor.cumulative());
        }
        if (delta.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRejectedException("表底读数相对前一表底回退，拒绝入库: " + request.readingTime());
        }
        if (successor != null && successor.cumulative().compareTo(cumulative) < 0) {
            throw new BusinessRejectedException(
                    "表底读数大于后一表底读数，乱序插入会使后继增量为负，拒绝入库: " + request.readingTime());
        }

        ReadingRow inserted = readingRepository.insert(meter.id(), readingTime, month,
                cumulative, delta, timeProvider.now());

        // 乱序到达：新读数插入到表底序列中间时，重算后一读数的增量，保证月累计正确。
        if (successor != null) {
            BigDecimal successorDelta = successor.cumulative().subtract(cumulative);
            readingRepository.updateDelta(successor.id(), successorDelta);
        }
        return toReadingResult(inserted, false);
    }

    @Transactional
    public RestrictionResult triggerRestriction(RestrictionRequest request) {
        // 先锁定河段全部取水户（按 ID 排序）：与已持锁的并发申报排队，形成唯一先后结果。
        List<UserRow> users = userRepository.lockByRiverSection(request.riverSection());
        Instant now = timeProvider.now();
        Instant requested = request.effectiveAt() == null || request.effectiveAt().isBlank()
                ? null : Instant.parse(request.effectiveAt());
        // 生效时刻不得早于拿到锁的时刻：排在本触发之前提交的申报不可能受尚未触发的事件影响。
        Instant effectiveAt = requested == null || requested.isBefore(now) ? now : requested;
        RestrictionRow row = restrictionRepository.trigger(request.riverSection(), effectiveAt,
                request.reason(), now);
        return new RestrictionResult(row.id(), row.riverSection(), row.effectiveAt().toString(),
                row.reason(), users.size());
    }

    @Transactional(readOnly = true)
    public LedgerView ledger(Long userId, Integer year) {
        UserRow user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("取水户不存在: " + userId));
        int targetYear = year != null ? year : timeProvider.now().atOffset(ZoneOffset.UTC).getYear();
        AnnualQuotaRow quota = userRepository.findAnnualQuota(user.id(), targetYear)
                .orElseGet(() -> new AnnualQuotaRow(null, user.id(), targetYear,
                        user.annualQuota(), BigDecimal.ZERO, false, null, null, null));

        Map<String, MonthLineAccumulator> lines = new HashMap<>();
        BigDecimal declaredTotal = BigDecimal.ZERO;
        for (PlanVersionRow v : planRepository.findLatestVersionsOfYear(user.id(), targetYear)) {
            MonthLineAccumulator line = lines.computeIfAbsent(v.month(), m -> new MonthLineAccumulator(m));
            line.declared = v.declaredVolume();
            line.accepted = v.acceptedVolume();
            line.version = v.version();
            declaredTotal = declaredTotal.add(v.acceptedVolume());
        }
        BigDecimal actualTotal = BigDecimal.ZERO;
        for (MonthTotal t : readingRepository.monthlyTotalsOfUserYear(user.id(), targetYear)) {
            MonthLineAccumulator line = lines.computeIfAbsent(t.month(), MonthLineAccumulator::new);
            line.actual = t.total();
            actualTotal = actualTotal.add(t.total());
        }

        BigDecimal effectiveQuota = quota.permittedQuota().subtract(quota.carryoverDeduction());
        BigDecimal remaining = effectiveQuota.subtract(declaredTotal);
        List<LedgerView.MonthLine> monthLines = new ArrayList<>();
        lines.values().stream().sorted((a, b) -> a.month.compareTo(b.month)).forEach(a ->
                monthLines.add(new LedgerView.MonthLine(a.month,
                        nz(a.declared), nz(a.accepted), nz(a.actual), a.version)));
        return new LedgerView(user.id(), targetYear, quota.permittedQuota(), quota.carryoverDeduction(),
                effectiveQuota, declaredTotal, actualTotal, remaining, quota.excess(), quota.sealed(),
                monthLines);
    }

    @Transactional
    public SealResult seal(Long userId, Integer year) {
        UserRow user = userRepository.lockById(userId);
        if (user == null) {
            throw new NotFoundException("取水户不存在: " + userId);
        }
        int targetYear = year != null ? year : timeProvider.now().atOffset(ZoneOffset.UTC).getYear();
        AnnualQuotaRow quota = userRepository.getOrCreateAnnualQuota(user.id(), targetYear, user.annualQuota());

        BigDecimal actualTotal = BigDecimal.ZERO;
        for (MonthTotal t : readingRepository.monthlyTotalsOfUserYear(user.id(), targetYear)) {
            actualTotal = actualTotal.add(t.total());
        }

        if (quota.sealed()) {
            // 重复封存幂等：回放封存时刻冻结的实际水量与结算结果，终态固定。
            BigDecimal settledActual = quota.sealedActualTotal() == null ? BigDecimal.ZERO : quota.sealedActualTotal();
            BigDecimal settledExcess = quota.excess() == null ? BigDecimal.ZERO : quota.excess();
            return new SealResult(user.id(), targetYear, settledActual, quota.permittedQuota(),
                    quota.carryoverDeduction(),
                    quota.permittedQuota().subtract(quota.carryoverDeduction()),
                    settledExcess, settledExcess, true);
        }

        // 超“许可年度总量”部分 1:1 结转次年；未超则结转 0（覆盖次年行可能存在的历史值）。
        BigDecimal excess = actualTotal.subtract(quota.permittedQuota()).max(BigDecimal.ZERO);
        userRepository.applySeal(quota, timeProvider.now(), actualTotal, excess, targetYear + 1);
        return new SealResult(user.id(), targetYear, actualTotal, quota.permittedQuota(),
                quota.carryoverDeduction(),
                quota.permittedQuota().subtract(quota.carryoverDeduction()),
                excess, excess, true);
    }

    // ---- 独立辅助方法（置于作用域末尾）----

    /**
     * 幂等回放或指纹冲突判定。
     */
    private DeclarationResult replayOrConflict(DeclarationRequest request, BigDecimal declaredVolume,
                                               IdempotencyRow row) {
        if (!row.userId().equals(request.userId())
                || !row.month().equals(request.month())
                || row.declaredVolume().compareTo(declaredVolume) != 0) {
            throw new IdempotencyConflictException(
                    "requestId 已用于不同申报参数（取水户/月份/水量不一致）: " + request.requestId());
        }
        if (row.rejected()) {
            return new DeclarationResult(request.requestId(), row.userId(), row.month(), "REJECTED",
                    row.declaredVolume(), null, null, false, null, row.rejectReason());
        }
        PlanVersionRow version = planRepository.findVersionById(row.planVersionId())
                .orElseThrow(() -> new IllegalStateException("幂等记录指向的版本丢失: " + row.planVersionId()));
        String effectiveAt = null;
        if (version.restrictionId() != null) {
            effectiveAt = restrictionRepository.findById(version.restrictionId())
                    .map(r -> r.effectiveAt().toString()).orElse(null);
        }
        return new DeclarationResult(request.requestId(), version.userId(), version.month(), "CONFIRMED",
                version.declaredVolume(), version.acceptedVolume(), version.version(),
                version.restricted(), effectiveAt, null);
    }

    /**
     * 记录一次被规则拒绝的申报（落幂等指纹，不产生计划版本）并返回 REJECTED。
     */
    private DeclarationResult recordRejection(DeclarationRequest request, BigDecimal declaredVolume,
                                              Instant confirmedAt, AnnualQuotaRow quota,
                                              Long restrictionId, String reason) {
        try {
            planRepository.insertIdempotency(new IdempotencyRow(request.requestId(), request.userId(),
                    request.month(), declaredVolume, null, true, reason, confirmedAt));
        } catch (DuplicateKeyException dup) {
            IdempotencyRow winner = planRepository.findIdempotency(request.requestId())
                    .orElseThrow(() -> dup);
            return replayOrConflict(request, declaredVolume, winner);
        }
        return new DeclarationResult(request.requestId(), request.userId(), request.month(), "REJECTED",
                declaredVolume, null, null, restrictionId != null, null, reason);
    }

    private ReadingResult toReadingResult(ReadingRow row, boolean duplicate) {
        return new ReadingResult(row.meterPointId(), row.readingTime().toString(), row.month(),
                row.cumulative(), row.delta(), duplicate);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(VOLUME_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(VOLUME_SCALE) : v;
    }

    /**
     * 台账按月行累加器。
     */
    private static final class MonthLineAccumulator {
        private final String month;
        private BigDecimal declared;
        private BigDecimal accepted;
        private BigDecimal actual;
        private Integer version;

        private MonthLineAccumulator(String month) {
            this.month = month;
        }
    }
}
