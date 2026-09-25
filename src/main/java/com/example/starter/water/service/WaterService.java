package com.example.starter.water.service;

import com.example.starter.water.error.ApiException;
import com.example.starter.water.repo.Rows;
import com.example.starter.water.repo.WaterRepository;
import com.example.starter.water.web.ApiDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 取水许可水量分配与计量对冲业务服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>每取水户持年度许可总量；月度申报按自然月（UTC），可多次修改取最后已确认版本；</li>
 *   <li>申报受理量计入年度额度快照，达到剩余额度仍可受理，超出即拒绝整月申报；</li>
 *   <li>当月实际取水量按计量读数差分累计，超年度许可部分 1:1 结转扣减次年额度；</li>
 *   <li>河流生态流量限取：限取期间新申报减半受理，已确认计划不变；</li>
 *   <li>requestId 幂等：同键同参返回首次结果，同键异参返回 409 且不产生新版本；</li>
 *   <li>读数按计量点+表底时刻去重，乱序到达按表底时刻归月；</li>
 *   <li>年度计划封存后为终态，封存后修改与迟到申报一律拒绝。</li>
 * </ul>
 */
@Service
public class WaterService {

    /** 幂等首次结果状态：已确认。 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 幂等首次结果状态：已拒绝。 */
    public static final String STATUS_REJECTED = "REJECTED";

    private static final String REASON_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    private static final String REASON_PLAN_SEALED = "PLAN_SEALED";

    private final WaterRepository repository;
    private final Clock clock;

    public WaterService(WaterRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // ---------- 合成数据管理 ----------

    @Transactional
    public ApiDtos.UserResponse createUser(ApiDtos.CreateUserRequest request) {
        repository.ensureRestrictionRow(request.reachCode());
        try {
            long id = repository.insertUser(request.userCode(), request.userName(),
                    request.reachCode(), request.annualQuota());
            return new ApiDtos.UserResponse(id, request.userCode(), request.userName(),
                    request.reachCode(), request.annualQuota());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("USER_EXISTS", "取水户编码已存在: " + request.userCode());
        }
    }

    @Transactional
    public ApiDtos.MeterPointResponse createMeterPoint(ApiDtos.CreateMeterPointRequest request) {
        Rows.UserRow user = requireUser(request.userCode());
        try {
            long id = repository.insertMeterPoint(request.pointCode(), user.id());
            return new ApiDtos.MeterPointResponse(id, request.pointCode(), request.userCode());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("METER_POINT_EXISTS", "计量点编码已存在: " + request.pointCode());
        }
    }

    // ---------- 月度计划申报 ----------

    @Transactional
    public ApiDtos.DeclarationResponse declare(ApiDtos.DeclarationRequest request) {
        LocalDateTime now = nowUtc();
        // 1. 幂等检查在加锁前完成：requestId 唯一，同键重放不依赖取水户行锁，结果确定。
        Rows.IdempotencyRow existing = repository.findIdempotency(request.requestId()).orElse(null);
        if (existing != null) {
            if (!sameFingerprint(existing, request)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 携带了不同的取水户/月份/申报水量参数");
            }
            return replayResponse(request.userCode(), existing);
        }

        // 2. 串行化同一取水户的并发申报，并与封存、限取按行锁排序。
        Rows.UserRow user = repository.lockUserByCode(request.userCode())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND",
                        "取水户不存在: " + request.userCode()));

        // 双重检查：持有用户行锁后再查一次幂等记录，消除并发同键请求的竞态。
        Rows.IdempotencyRow raced = repository.findIdempotency(request.requestId()).orElse(null);
        if (raced != null) {
            if (!sameFingerprint(raced, request)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "相同 requestId 携带了不同的取水户/月份/申报水量参数");
            }
            return replayResponse(request.userCode(), raced);
        }

        int year = yearOf(request.month());

        // 3. 封存终态：封存后修改（同月再申报）与迟到申报（任何月份）一律拒绝。
        if (repository.findSeal(user.id(), year).isPresent()) {
            if (!tryInsertIdempotency(request, user, STATUS_REJECTED, BigDecimal.ZERO, null,
                    REASON_PLAN_SEALED, now)) {
                return replayAfterIdempotencyRace(request);
            }
            return rejectedResponse(request, REASON_PLAN_SEALED, now);
        }

        // 4. 限取判定：锁用户后再锁河段，保证“限取生效”与“申报受理”形成唯一先后时序，
        //    不会出现限取生效同刻仍按全额受理。
        Rows.RestrictionRow restriction = repository.lockRestriction(user.reachCode()).orElseThrow();
        boolean restricted = restriction.active();
        BigDecimal acceptedAmount = restricted
                ? request.plannedAmount().divide(BigDecimal.valueOf(2), 3, RoundingMode.HALF_UP)
                : request.plannedAmount();

        // 5. 额度判定基于已确认额度快照（仅已确认申报，读数不参与申报校验）。
        Rows.LedgerRow ledger = getOrCreateLedger(user, year, now);
        BigDecimal effectiveQuota = ledger.baseQuota().subtract(ledger.carryoverDeduction());
        BigDecimal otherMonths = repository.sumAcceptedForYear(user.id(), year)
                .subtract(repository.currentMonthAccepted(user.id(), request.month()));
        BigDecimal usedAfterAccept = otherMonths.add(acceptedAmount);

        if (usedAfterAccept.compareTo(effectiveQuota) > 0) {
            // 超出剩余额度：拒绝整月申报，不产生新版本。
            if (!tryInsertIdempotency(request, user, STATUS_REJECTED, BigDecimal.ZERO, null,
                    REASON_QUOTA_EXCEEDED, now)) {
                return replayAfterIdempotencyRace(request);
            }
            return rejectedResponse(request, REASON_QUOTA_EXCEEDED, now);
        }

        // 6. 受理：先占位幂等记录（declaration_id 暂空），再生成确认版本，最后回填版本ID。
        //    这样并发下若幂等插入落败，本事务尚无任何业务写入，可直接重放首次结果。
        if (!tryInsertIdempotency(request, user, STATUS_CONFIRMED, acceptedAmount, null,
                null, now)) {
            return replayAfterIdempotencyRace(request);
        }
        int nextVersion = repository.maxDeclarationVersion(user.id(), request.month()) + 1;
        long declarationId = repository.insertDeclaration(user.id(), request.month(), nextVersion,
                request.plannedAmount(), acceptedAmount, restricted, request.requestId(), now);
        repository.bindIdempotencyDeclaration(request.requestId(), declarationId);

        return new ApiDtos.DeclarationResponse(request.requestId(), request.userCode(),
                request.month(), STATUS_CONFIRMED, nextVersion, request.plannedAmount(),
                acceptedAmount, restricted, null);
    }

    // ---------- 计量读数 ----------

    @Transactional
    public ApiDtos.ReadingResponse reportReading(ApiDtos.ReadingRequest request) {
        LocalDateTime now = nowUtc();
        Rows.MeterPointRow point = repository.findMeterPoint(request.meterPoint())
                .orElseThrow(() -> ApiException.notFound("METER_POINT_NOT_FOUND",
                        "计量点不存在: " + request.meterPoint()));
        LocalDateTime readAt = LocalDateTime.ofInstant(request.readAt(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        String bucketMonth = readAt.getYear() + "-" + String.format("%02d", readAt.getMonthValue());

        // 按计量点+表底时刻去重：重复上报直接幂等返回，不重累计。
        if (repository.findReading(request.meterPoint(), readAt).isPresent()) {
            return new ApiDtos.ReadingResponse(request.meterPoint(), request.readAt(),
                    request.cumulativeReading(), bucketMonth, true);
        }
        try {
            repository.insertReading(request.meterPoint(), point.userId(), readAt, bucketMonth,
                    request.cumulativeReading(), now);
        } catch (DuplicateKeyException ex) {
            // 并发下唯一约束兜底，仍按重复上报幂等处理。
            return new ApiDtos.ReadingResponse(request.meterPoint(), request.readAt(),
                    request.cumulativeReading(), bucketMonth, true);
        }

        // 读数入库后立即对冲：实际取水超年度许可部分 1:1 结转扣减次年额度。
        settleCarryover(point.userId(), readAt.getYear(), now);

        return new ApiDtos.ReadingResponse(request.meterPoint(), request.readAt(),
                request.cumulativeReading(), bucketMonth, false);
    }

    // ---------- 生态流量限取 ----------

    @Transactional
    public ApiDtos.RestrictionResponse triggerRestriction(ApiDtos.RestrictionRequest request) {
        LocalDateTime now = nowUtc();
        repository.ensureRestrictionRow(request.reachCode());
        // 行锁与申报共用：与并发申报严格排序，锁内判定不重复激活。
        Rows.RestrictionRow row = repository.lockRestriction(request.reachCode()).orElseThrow();
        if (!row.active()) {
            repository.activateRestriction(request.reachCode(), now);
        }
        Instant effectiveAt = (row.active() ? row.effectiveAt() : now)
                .atZone(ZoneOffset.UTC).toInstant();
        return new ApiDtos.RestrictionResponse(request.reachCode(), true, effectiveAt);
    }

    // ---------- 年度计划封存 ----------

    @Transactional
    public ApiDtos.SealResponse seal(ApiDtos.SealRequest request) {
        LocalDateTime now = nowUtc();
        // 与申报共用取水户行锁：封存与申报严格排序，
        // 申报在持锁期间检查封存状态，杜绝“封存同刻仍受理申报”的交叉窗口。
        Rows.UserRow user = repository.lockUserByCode(request.userCode())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND",
                        "取水户不存在: " + request.userCode()));
        Rows.SealRow existing = repository.findSeal(user.id(), request.year()).orElse(null);
        if (existing != null) {
            // 重复封存返回幂等成功，封存为终态。
            return new ApiDtos.SealResponse(request.userCode(), request.year(),
                    existing.sealedAt().atZone(ZoneOffset.UTC).toInstant(), true);
        }
        try {
            repository.insertSeal(user.id(), request.year(), now);
        } catch (DuplicateKeyException ex) {
            Rows.SealRow raced = repository.findSeal(user.id(), request.year()).orElseThrow();
            return new ApiDtos.SealResponse(request.userCode(), request.year(),
                    raced.sealedAt().atZone(ZoneOffset.UTC).toInstant(), true);
        }
        return new ApiDtos.SealResponse(request.userCode(), request.year(),
                now.atZone(ZoneOffset.UTC).toInstant(), false);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public ApiDtos.QuotaResponse quota(String userCode, int year) {
        Rows.UserRow user = requireUser(userCode);
        Rows.LedgerRow ledger = repository.findLedger(user.id(), year)
                .orElseGet(() -> new Rows.LedgerRow(0, user.id(), year, user.annualQuota(), BigDecimal.ZERO));
        BigDecimal effectiveQuota = ledger.baseQuota().subtract(ledger.carryoverDeduction());
        BigDecimal confirmedTotal = repository.sumAcceptedForYear(user.id(), year);
        BigDecimal actualUsage = yearlyActualUsage(user.id(), year);
        BigDecimal remaining = effectiveQuota.subtract(confirmedTotal);
        return new ApiDtos.QuotaResponse(userCode, year, ledger.baseQuota(),
                ledger.carryoverDeduction(), effectiveQuota, confirmedTotal, actualUsage, remaining);
    }

    @Transactional(readOnly = true)
    public ApiDtos.UsageResponse usage(String userCode, String month) {
        Rows.UserRow user = requireUser(userCode);
        BigDecimal amount = monthlyUsage(user.id()).getOrDefault(month, BigDecimal.ZERO);
        return new ApiDtos.UsageResponse(userCode, month, amount);
    }

    // ---------- 内部方法 ----------

    /**
     * 读数对冲结算：按全年实际取水量（差分归月后求和）与该年有效额度比较，
     * 超出部分 1:1 结转扣减次年额度。次年台账不存在则按许可基数创建。
     */
    private void settleCarryover(long userId, int year, LocalDateTime now) {
        Rows.LedgerRow ledger = getOrCreateLedgerById(userId, year, now);
        BigDecimal actual = yearlyActualUsage(userId, year);
        BigDecimal effectiveQuota = ledger.baseQuota().subtract(ledger.carryoverDeduction());
        BigDecimal overdraft = actual.subtract(effectiveQuota).max(BigDecimal.ZERO);
        if (overdraft.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Rows.LedgerRow next = getOrCreateLedgerById(userId, year + 1, now);
        if (overdraft.compareTo(next.carryoverDeduction()) > 0) {
            repository.updateCarryover(next.id(), overdraft);
        }
    }

    /**
     * 实际取水量按计量点差分：每个计量点相邻读数之差计入后一读数表底时刻所在月（UTC）。
     * 乱序读数因按表底时刻排序归月，不影响月累计正确性。
     */
    private java.util.Map<String, BigDecimal> monthlyUsage(long userId) {
        List<Rows.ReadingRow> readings = repository.listReadingsByUser(userId);
        java.util.Map<String, BigDecimal> result = new java.util.TreeMap<>();
        String previousPoint = null;
        BigDecimal previousReading = null;
        for (Rows.ReadingRow row : readings) {
            if (row.meterPoint().equals(previousPoint) && previousReading != null) {
                BigDecimal delta = row.cumulativeReading().subtract(previousReading);
                if (delta.compareTo(BigDecimal.ZERO) >= 0) {
                    result.merge(row.bucketMonth(), delta, BigDecimal::add);
                }
            }
            previousPoint = row.meterPoint();
            previousReading = row.cumulativeReading();
        }
        return result;
    }

    private BigDecimal yearlyActualUsage(long userId, int year) {
        return monthlyUsage(userId).entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(year + "-"))
                .map(java.util.Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Rows.LedgerRow getOrCreateLedger(Rows.UserRow user, int year, LocalDateTime now) {
        return getOrCreateLedgerById(user.id(), year, now, user.annualQuota());
    }

    private Rows.LedgerRow getOrCreateLedgerById(long userId, int year, LocalDateTime now) {
        Rows.UserRow user = repository.findUserById(userId).orElseThrow();
        return getOrCreateLedgerById(userId, year, now, user.annualQuota());
    }

    private Rows.LedgerRow getOrCreateLedgerById(long userId, int year, LocalDateTime now,
                                                 BigDecimal baseQuota) {
        return repository.lockLedger(userId, year).orElseGet(() -> {
            try {
                repository.insertLedger(userId, year, baseQuota, BigDecimal.ZERO);
            } catch (DuplicateKeyException ignored) {
                // 并发下台账已被其他事务创建，加锁读取即可
            }
            return repository.lockLedger(userId, year).orElseThrow();
        });
    }

    private boolean sameFingerprint(Rows.IdempotencyRow row, ApiDtos.DeclarationRequest request) {
        Rows.UserRow user = repository.findUserByCode(request.userCode())
                .orElse(null);
        return user != null && row.userId() == user.id()
                && row.month().equals(request.month())
                && row.plannedAmount().compareTo(request.plannedAmount()) == 0;
    }

    /**
     * 尝试写入幂等记录。返回 false 表示同 requestId 已被并发事务抢先写入，
     * 此时本事务尚未产生任何业务写入，调用方可安全重放首次结果。
     */
    private boolean tryInsertIdempotency(ApiDtos.DeclarationRequest request, Rows.UserRow user,
                                         String status, BigDecimal acceptedAmount,
                                         Long declarationId, String reason, LocalDateTime now) {
        try {
            repository.insertIdempotency(request.requestId(), user.id(), request.month(),
                    request.plannedAmount(), status, acceptedAmount, declarationId, reason, now);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 幂等竞态落败后的重放：同参返回首次结果，异参抛 409。 */
    private ApiDtos.DeclarationResponse replayAfterIdempotencyRace(ApiDtos.DeclarationRequest request) {
        Rows.IdempotencyRow winner = repository.findIdempotency(request.requestId()).orElseThrow();
        if (!sameFingerprint(winner, request)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 携带了不同的取水户/月份/申报水量参数");
        }
        return replayResponse(request.userCode(), winner);
    }

    private ApiDtos.DeclarationResponse replayResponse(String userCode, Rows.IdempotencyRow row) {
        Rows.DeclarationRow declaration = row.declarationId() == null ? null
                : repository.findDeclarationById(row.declarationId()).orElse(null);
        return new ApiDtos.DeclarationResponse(row.requestId(), userCode, row.month(),
                row.status(), declaration == null ? null : declaration.version(),
                row.plannedAmount(), row.acceptedAmount(),
                declaration != null && declaration.restricted(), row.reason());
    }

    private ApiDtos.DeclarationResponse rejectedResponse(ApiDtos.DeclarationRequest request,
                                                         String reason, LocalDateTime now) {
        return new ApiDtos.DeclarationResponse(request.requestId(), request.userCode(),
                request.month(), STATUS_REJECTED, null, request.plannedAmount(),
                BigDecimal.ZERO, false, reason);
    }

    private Rows.UserRow requireUser(String userCode) {
        return repository.findUserByCode(userCode)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND",
                        "取水户不存在: " + userCode));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static int yearOf(String month) {
        return Integer.parseInt(month.substring(0, 4));
    }
}
