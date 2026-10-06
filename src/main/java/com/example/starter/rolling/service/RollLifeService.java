package com.example.starter.rolling.service;

import com.example.starter.rolling.api.dto.RollActualItemView;
import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollFreezeView;
import com.example.starter.rolling.api.dto.RollLedgerView;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.repo.IdAllocator;
import com.example.starter.rolling.repo.RollLedgerRepository;
import com.example.starter.rolling.repo.RollLedgerRepository.LedgerRow;
import com.example.starter.rolling.repo.RollLedgerRepository.RollActualRow;
import com.example.starter.rolling.repo.RollingScheduleRepository;
import com.example.starter.rolling.repo.RollingScheduleRepository.IdempotentRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 轧辊寿命台账领域服务：换辊实绩回报、上机申请、日冻结、台账查询。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>实绩入库与累计里程更新在同一事务内完成；累计恰达周期上限立即禁排，超限实绩不得入库；</li>
 *   <li>新辊上机后前 5 卷沿用既有烫辊规则并加首用标记；寿命到期轧辊申请上机直接拒绝并给出剩余里程；</li>
 *   <li>同一轧辊同时刻只允许一张实绩（唯一约束裁决），乱序回传按时刻归位；缺轧辊号直接拒绝并提示补录；</li>
 *   <li>幂等：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参 409；</li>
 *   <li>日冻结后迟到实绩只记迟到事件，不改已冻结里程；台账可由实绩复算，重复查询稳定。</li>
 * </ul>
 */
@Service
public class RollLifeService {

    /** 新辊上机后的首用烫辊卷数，前 5 卷加首用标记。 */
    public static final int FIRST_USE_COILS = 5;

    private static final String BIZ_ROLL_ACTUAL = "ROLL_ACTUAL";
    private static final String BIZ_ROLL_MOUNT = "ROLL_MOUNT";
    private static final String BIZ_ROLL_FREEZE = "ROLL_FREEZE";

    private final RollLedgerRepository rollRepo;
    private final RollingScheduleRepository sharedRepo;
    private final IdAllocator idAllocator;
    private final Clock clock;

    public RollLifeService(RollLedgerRepository rollRepo,
                           RollingScheduleRepository sharedRepo,
                           IdAllocator idAllocator,
                           Clock clock) {
        this.rollRepo = rollRepo;
        this.sharedRepo = sharedRepo;
        this.idAllocator = idAllocator;
        this.clock = clock;
    }

    // ---------------- 换辊实绩回报 ----------------

    @Transactional
    public RollActualView reportActual(RollActualRequest request) {
        // 缺轧辊号直接拒绝并提示补录：抛错回滚，拒绝记录不入库也不增加里程。
        if (request.rollNo() == null || request.rollNo().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "实绩缺少轧辊号，请补录轧辊号后重新回报；该记录不入库也不增加里程");
        }

        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_ROLL_ACTUAL, fp);
        if (replay != null) {
            return (RollActualView) replay;
        }

        LedgerRow ledger = requireLedger(request.rollNo());

        // 按“轧辊号＋实绩时刻”去重：已存在的实绩按首次结果幂等返回。
        RollActualRow existing = rollRepo.findActual(request.rollNo(), request.recordedAt());
        if (existing != null) {
            return dedupActualView(request.requestId(), fp, ledger, existing);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 日冻结终态：迟到实绩只记迟到事件，不改已冻结里程与禁排结论。
        if (ledger.frozenAt() != null) {
            long eventId = idAllocator.nextId();
            rollRepo.insertLateEvent(eventId, request.rollNo(), request.requestId(),
                    request.rollingKm(), request.recordedAt(), now);
            RollActualView lateView = new RollActualView(request.rollNo(), null, false, true, false,
                    ledger.totalKm(), remainingKm(ledger), ledger.banned(), request.recordedAt());
            saveIdempotent(request.requestId(), BIZ_ROLL_ACTUAL, fp, 200,
                    FingerprintUtil.toJson(lateView));
            return lateView;
        }

        // 超限实绩不得入库：抛错回滚，里程与卷数保持不变。
        BigDecimal newTotal = ledger.totalKm().add(request.rollingKm());
        if (newTotal.compareTo(ledger.cycleLimitKm()) > 0) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "实绩将使轧辊 " + request.rollNo() + " 累计里程达 "
                            + newTotal.stripTrailingZeros().toPlainString()
                            + " 公里，超过周期上限 " + ledger.cycleLimitKm().stripTrailingZeros().toPlainString()
                            + " 公里（剩余 " + remainingKm(ledger).stripTrailingZeros().toPlainString()
                            + " 公里），超限实绩不得入库");
        }

        // 新辊上机后前 5 卷加首用标记（沿用既有烫辊位规则）。
        boolean firstUse = ledger.coilsUsed() < FIRST_USE_COILS;

        long actualId = idAllocator.nextId();
        try {
            rollRepo.insertActual(new RollActualRow(actualId, request.rollNo(), request.rollingKm(),
                    request.recordedAt(), firstUse, request.requestId(), fp, now));
        } catch (DuplicateKeyException e) {
            // 并发回传同一“轧辊号＋时刻”：唯一约束裁决后按首次实绩幂等返回。
            RollActualRow winner = rollRepo.findActual(request.rollNo(), request.recordedAt());
            if (winner != null) {
                return dedupActualView(request.requestId(), fp, requireLedger(request.rollNo()), winner);
            }
            throw e;
        }

        // 实绩入库与累计里程更新在同一事务内；累计恰达周期上限立即禁排。
        rollRepo.addMileage(request.rollNo(), request.rollingKm(), now);

        LedgerRow refreshed = requireLedger(request.rollNo());
        RollActualView view = new RollActualView(request.rollNo(), request.rollingKm(), true, false,
                firstUse, refreshed.totalKm(), remainingKm(refreshed), refreshed.banned(),
                request.recordedAt());
        saveIdempotent(request.requestId(), BIZ_ROLL_ACTUAL, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 上机申请 ----------------

    @Transactional
    public RollLedgerView mount(RollMountRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_ROLL_MOUNT, fp);
        if (replay != null) {
            return (RollLedgerView) replay;
        }

        LedgerRow ledger = requireLedger(request.rollNo());
        BigDecimal remaining = remainingKm(ledger);
        if (ledger.banned() || remaining.signum() <= 0) {
            // 寿命到期直接拒绝并给出剩余里程。
            throw new ConflictException(HttpStatus.CONFLICT,
                    "轧辊 " + request.rollNo() + " 寿命到期，拒绝上机：累计里程 "
                            + ledger.totalKm().stripTrailingZeros().toPlainString()
                            + " 公里 / 周期上限 " + ledger.cycleLimitKm().stripTrailingZeros().toPlainString()
                            + " 公里，剩余里程 " + remaining.stripTrailingZeros().toPlainString() + " 公里");
        }

        if (!ledger.onMachine()) {
            rollRepo.mount(request.rollNo(), LocalDateTime.now(clock));
        }
        RollLedgerView view = ledgerView(requireLedger(request.rollNo()));
        saveIdempotent(request.requestId(), BIZ_ROLL_MOUNT, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 日冻结 ----------------

    @Transactional
    public RollFreezeView freezeAll(RollFreezeRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_ROLL_FREEZE, fp);
        if (replay != null) {
            return (RollFreezeView) replay;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int frozen = rollRepo.markAllFrozen(now);
        RollFreezeView view = new RollFreezeView(frozen, now);
        saveIdempotent(request.requestId(), BIZ_ROLL_FREEZE, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 台账查询 ----------------

    /**
     * 台账查询：只读无副作用，重复查询稳定；recomputedTotalKm 由实绩逐条复算，可核对台账。
     */
    @Transactional(readOnly = true)
    public RollLedgerView getLedger(String rollNo) {
        return ledgerView(requireLedger(rollNo));
    }

    /**
     * 实绩明细：乱序回传按实绩时刻升序归位。
     */
    @Transactional(readOnly = true)
    public List<RollActualItemView> listActuals(String rollNo) {
        requireLedger(rollNo);
        return rollRepo.findActualsByRoll(rollNo).stream()
                .map(a -> new RollActualItemView(a.rollingKm(), a.recordedAt(), a.firstUse(), a.requestId()))
                .toList();
    }

    // ---------------- 内部方法 ----------------

    private LedgerRow requireLedger(String rollNo) {
        LedgerRow ledger = rollRepo.findLedger(rollNo);
        if (ledger == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "轧辊台账不存在: " + rollNo);
        }
        return ledger;
    }

    private BigDecimal remainingKm(LedgerRow ledger) {
        BigDecimal remaining = ledger.cycleLimitKm().subtract(ledger.totalKm());
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private RollLedgerView ledgerView(LedgerRow ledger) {
        return new RollLedgerView(ledger.rollNo(), ledger.cycleLimitKm(), ledger.totalKm(),
                remainingKm(ledger), rollRepo.sumActualKm(ledger.rollNo()), ledger.coilsUsed(),
                Math.max(0, FIRST_USE_COILS - ledger.coilsUsed()), ledger.onMachine(),
                ledger.banned(), ledger.frozenAt());
    }

    /**
     * 构造“轧辊号＋时刻”重复回传的幂等视图并登记本次 requestId 的幂等记录。
     */
    private RollActualView dedupActualView(String requestId, String fp, LedgerRow ledger,
                                           RollActualRow existing) {
        RollActualView view = new RollActualView(existing.rollNo(), existing.rollingKm(), false, false,
                existing.firstUse(), ledger.totalKm(), remainingKm(ledger), ledger.banned(),
                existing.recordedAt());
        saveIdempotent(requestId, BIZ_ROLL_ACTUAL, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    /**
     * 同键重放：指纹一致返回首次响应；同键异参抛 409 且不产生任何写入。
     *
     * @return 首次响应对象；null 表示该 requestId 首次出现
     */
    private Object replayIfSame(String requestId, String bizType, String fingerprint) {
        IdempotentRow row = sharedRepo.findIdempotent(requestId);
        if (row == null) {
            return null;
        }
        if (!row.fingerprint().equals(fingerprint) || !row.bizType().equals(bizType)) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 已用于不同业务参数的请求，拒绝执行且不产生任何写入");
        }
        return switch (bizType) {
            case BIZ_ROLL_ACTUAL -> FingerprintUtil.fromJson(row.responseBody(), RollActualView.class);
            case BIZ_ROLL_MOUNT -> FingerprintUtil.fromJson(row.responseBody(), RollLedgerView.class);
            default -> FingerprintUtil.fromJson(row.responseBody(), RollFreezeView.class);
        };
    }

    private void saveIdempotent(String requestId, String bizType, String fingerprint,
                                int httpStatus, String body) {
        boolean inserted = sharedRepo.tryInsertIdempotent(requestId, bizType, fingerprint, httpStatus,
                body, LocalDateTime.now(clock));
        if (!inserted) {
            // 并发同键首调竞争：唯一约束裁决后本事务所有写入将随异常回滚；
            // 客户端用相同 requestId 重放即可取得首次结果。
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 正在被并发处理，唯一记录已存在，请用相同参数重放");
        }
    }
}
