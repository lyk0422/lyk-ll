package com.example.starter.rolling.service;

import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.api.dto.RollView;
import com.example.starter.rolling.repo.IdAllocator;
import com.example.starter.rolling.repo.RollLifeRepository;
import com.example.starter.rolling.repo.RollLifeRepository.IdempotentRow;
import com.example.starter.rolling.repo.RollLifeRepository.PhysicalRollRow;
import com.example.starter.rolling.repo.RollLifeRepository.RollActualRow;
import com.example.starter.rolling.repo.RollLifeRepository.RollRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 轧辊寿命台账领域服务：上机申请、换辊实绩回报、日冻结、台账复算。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>实绩入库与累计里程更新在同一事务内完成；累计里程达到周期上限立即禁排，
 *       超限实绩整单回滚不得入库；</li>
 *   <li>新辊上机后前 5 卷按实绩时刻归位加首用标记，沿用既有烫辊规则；</li>
 *   <li>寿命到期轧辊申请上机直接拒绝并给出剩余里程；</li>
 *   <li>同一轧辊同时刻只允许一张实绩回报（唯一索引裁决），并发按 requestId 幂等：
 *       指纹覆盖 requestId 与全部业务字段，同键同参返回首次结果，同键异参 409；</li>
 *   <li>日冻结后迟到实绩只记迟到事件，不改已冻结里程；缺轧辊号的实绩直接拒绝，
 *       拒绝记录不增加里程。</li>
 * </ul>
 */
@Service
public class RollLifeService {

    /** 新辊上机后沿用烫辊规则并加首用标记的卷数，恰第 5 卷为最后首用位。 */
    public static final int FIRST_USE_COILS = 5;

    private static final String BIZ_MOUNT = "ROLL_MOUNT";
    private static final String BIZ_ACTUAL = "ROLL_ACTUAL";
    private static final String BIZ_FREEZE = "ROLL_FREEZE";

    private static final String STATUS_IN_SERVICE = "IN_SERVICE";
    private static final String STATUS_BANNED = "BANNED";

    private final RollLifeRepository repo;
    private final IdAllocator idAllocator;
    private final Clock clock;

    public RollLifeService(RollLifeRepository repo, IdAllocator idAllocator, Clock clock) {
        this.repo = repo;
        this.idAllocator = idAllocator;
        this.clock = clock;
    }

    // ---------------- 上机申请 ----------------

    @Transactional
    public RollView mount(RollMountRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_MOUNT, fp);
        if (replay != null) {
            return (RollView) replay;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        RollRow roll = repo.findRoll(request.rollNo());
        if (roll != null) {
            BigDecimal remaining = roll.cycleLimitKm().subtract(roll.totalKm());
            if (roll.totalKm().compareTo(roll.cycleLimitKm()) >= 0) {
                // 寿命到期：直接拒绝并给出剩余里程，不产生任何台账写入。
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "轧辊 " + request.rollNo() + " 寿命到期，禁止上机：累计里程 "
                                + roll.totalKm().stripTrailingZeros().toPlainString()
                                + " 公里已达周期上限 " + roll.cycleLimitKm().stripTrailingZeros().toPlainString()
                                + " 公里，剩余里程 " + remaining.stripTrailingZeros().toPlainString() + " 公里");
            }
            // 换辊重新上机：卷数归零，前 5 卷重新进入首用烫辊位；累计里程不清零。
            repo.remount(request.rollNo(), request.mountedAt(), now);
        } else {
            // 首代绑定已有辊号：登记合成物理辊唯一编号，代次链从第 1 代起。
            long physicalId = idAllocator.nextId();
            repo.insertRoll(new RollRow(request.rollNo(), physicalId, 1, STATUS_IN_SERVICE,
                    BigDecimal.ZERO, request.cycleLimitKm(), null, 0, request.mountedAt(),
                    null, now, now));
            repo.insertPhysical(new PhysicalRollRow(physicalId, request.rollNo(), 1, 0, 1, now, now));
        }

        RollView view = buildView(repo.findRoll(request.rollNo()));
        saveIdempotent(request.requestId(), BIZ_MOUNT, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 换辊实绩回报 ----------------

    @Transactional
    public RollActualView reportActual(RollActualRequest request) {
        String fp = FingerprintUtil.fingerprint(request);
        Object replay = replayIfSame(request.requestId(), BIZ_ACTUAL, fp);
        if (replay != null) {
            return (RollActualView) replay;
        }

        // 缺轧辊号直接拒绝并提示补录；拒绝记录不写库、不增加里程，允许补录后用同键重报。
        if (request.rollNo() == null || request.rollNo().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "实绩缺少轧辊号，请补录轧辊号后重新回报；本次拒绝记录未增加任何里程");
        }

        RollRow roll = repo.findRoll(request.rollNo());
        if (roll == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "轧辊 " + request.rollNo() + " 不存在，请先申请上机");
        }

        // 按“轧辊号＋实绩时刻”去重：已存在的实绩按首次结果幂等返回。
        RollActualRow existing = repo.findActual(request.rollNo(), request.recordedAt());
        if (existing != null) {
            return dedupActualView(request.requestId(), fp, existing);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 日冻结终态：迟到实绩不改已冻结里程与禁排结论。
        if (roll.frozenAt() != null) {
            PhysicalRollRow physical = repo.findPhysical(roll.physicalId());
            if (physical != null && !physical.currentRollNo().equals(request.rollNo())) {
                // 该代已被返修新代替代：迟到旧实绩只落旧代迟到证据，
                // 不改旧代冻结里程、也不影响新代独立台账。
                long evidenceId = idAllocator.nextId();
                repo.insertRepairLateEvidence(evidenceId, request.rollNo(), roll.physicalId(),
                        request.requestId(), request.rollingKm(), request.recordedAt(), now);
                RollActualView evidenceView = new RollActualView(request.rollNo(), null, false, true,
                        false, roll.totalKm(), remaining(roll), isBanned(roll), request.recordedAt());
                saveIdempotent(request.requestId(), BIZ_ACTUAL, fp, 200,
                        FingerprintUtil.toJson(evidenceView));
                return evidenceView;
            }
            long eventId = idAllocator.nextId();
            repo.insertLateEvent(eventId, request.rollNo(), request.requestId(),
                    request.rollingKm(), request.recordedAt(), now);
            RollActualView lateView = new RollActualView(request.rollNo(), null, false, true,
                    false, roll.totalKm(), remaining(roll), isBanned(roll), request.recordedAt());
            saveIdempotent(request.requestId(), BIZ_ACTUAL, fp, 200,
                    FingerprintUtil.toJson(lateView));
            return lateView;
        }

        // 首用标记：按实绩时刻归位，本卷之前（含上机时刻起）已入库卷数小于 5 即首用烫辊位。
        boolean firstUse = repo.countCoilsSinceMountBefore(request.rollNo(), roll.mountedAt(),
                request.recordedAt()) < FIRST_USE_COILS;

        long actualId = idAllocator.nextId();
        try {
            repo.insertActual(new RollActualRow(actualId, request.rollNo(), request.rollingKm(),
                    firstUse, request.recordedAt(), request.requestId(), fp, now));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发同辊同时刻回报：唯一索引裁决后按首次实绩幂等返回。
            RollActualRow winner = repo.findActual(request.rollNo(), request.recordedAt());
            if (winner != null) {
                return dedupActualView(request.requestId(), fp, winner);
            }
            throw e;
        }

        // 里程累计与实绩入库同事务：超限则整单回滚，超限实绩不得入库。
        boolean withinLimit = repo.addMileageIfWithinLimit(request.rollNo(), request.rollingKm(), now);
        if (!withinLimit) {
            RollRow current = repo.findRoll(request.rollNo());
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "本次实绩 " + request.rollingKm().stripTrailingZeros().toPlainString()
                            + " 公里将使轧辊 " + request.rollNo() + " 累计里程超过周期上限 "
                            + current.cycleLimitKm().stripTrailingZeros().toPlainString()
                            + " 公里（当前累计 " + current.totalKm().stripTrailingZeros().toPlainString()
                            + " 公里，剩余里程 " + remaining(current).stripTrailingZeros().toPlainString()
                            + " 公里），超限实绩不得入库");
        }

        // 累计里程达到周期上限立即禁排。
        repo.banIfReachedLimit(request.rollNo(), now);

        RollRow refreshed = repo.findRoll(request.rollNo());
        RollActualView view = new RollActualView(request.rollNo(), request.rollingKm(), true,
                false, firstUse, refreshed.totalKm(), remaining(refreshed), isBanned(refreshed),
                request.recordedAt());
        saveIdempotent(request.requestId(), BIZ_ACTUAL, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 日冻结 ----------------

    @Transactional
    public RollView freeze(String rollNo, RollFreezeRequest request) {
        String fp = FingerprintUtil.fingerprint(new FreezeFingerprint(request.requestId(), rollNo));
        Object replay = replayIfSame(request.requestId(), BIZ_FREEZE, fp);
        if (replay != null) {
            return (RollView) replay;
        }

        RollRow roll = requireRoll(rollNo);
        if (roll.frozenAt() == null) {
            repo.markFrozen(rollNo, LocalDateTime.now(clock));
        }
        // 终态重复冻结：幂等成功，不重复写状态。
        RollView view = buildView(repo.findRoll(rollNo));
        saveIdempotent(request.requestId(), BIZ_FREEZE, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    // ---------------- 台账查询与复算 ----------------

    @Transactional(readOnly = true)
    public RollView get(String rollNo) {
        return buildView(requireRoll(rollNo));
    }

    /**
     * 台账复算：由实绩记录按时刻归位重算累计里程、本班卷数与首用标记，
     * 禁排状态按周期上限重判；复算后重复查询结果稳定。
     */
    @Transactional
    public RollView recompute(String rollNo) {
        RollRow roll = requireRoll(rollNo);
        List<RollActualRow> actuals = repo.findActualsByRoll(rollNo);

        BigDecimal total = BigDecimal.ZERO;
        int coilsSinceMount = 0;
        for (RollActualRow actual : actuals) {
            total = total.add(actual.rollingKm());
            boolean inCampaign = !actual.recordedAt().isBefore(roll.mountedAt());
            boolean firstUse = inCampaign && coilsSinceMount < FIRST_USE_COILS;
            if (inCampaign) {
                coilsSinceMount++;
            }
            if (actual.firstUse() != firstUse) {
                repo.updateFirstUse(actual.id(), firstUse);
            }
        }

        boolean banned = total.compareTo(roll.cycleLimitKm()) >= 0;
        repo.rewriteLedger(rollNo, total, coilsSinceMount, banned, LocalDateTime.now(clock));
        return buildView(repo.findRoll(rollNo));
    }

    // ---------------- 内部方法 ----------------

    private RollRow requireRoll(String rollNo) {
        RollRow roll = repo.findRoll(rollNo);
        if (roll == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "轧辊不存在: " + rollNo);
        }
        return roll;
    }

    /**
     * 构造“轧辊号＋时刻”重复回传的幂等视图并登记本次 requestId 的幂等记录。
     */
    private RollActualView dedupActualView(String requestId, String fp, RollActualRow existing) {
        RollRow roll = requireRoll(existing.rollNo());
        RollActualView view = new RollActualView(existing.rollNo(), existing.rollingKm(), false,
                false, existing.firstUse(), roll.totalKm(), remaining(roll), isBanned(roll),
                existing.recordedAt());
        saveIdempotent(requestId, BIZ_ACTUAL, fp, 200, FingerprintUtil.toJson(view));
        return view;
    }

    private RollView buildView(RollRow roll) {
        return new RollView(roll.rollNo(), roll.status(), roll.totalKm(), roll.cycleLimitKm(),
                remaining(roll), roll.coilsSinceMount(), roll.mountedAt(), roll.frozenAt());
    }

    private static BigDecimal remaining(RollRow roll) {
        return roll.cycleLimitKm().subtract(roll.totalKm());
    }

    private static boolean isBanned(RollRow roll) {
        return STATUS_BANNED.equals(roll.status());
    }

    /**
     * 同键重放：指纹一致返回首次响应；同键异参抛 409 且不产生任何写入。
     *
     * @return 首次响应对象；null 表示该 requestId 首次出现
     */
    private Object replayIfSame(String requestId, String bizType, String fingerprint) {
        IdempotentRow row = repo.findIdempotent(requestId);
        if (row == null) {
            return null;
        }
        if (!row.fingerprint().equals(fingerprint) || !row.bizType().equals(bizType)) {
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 已用于不同业务参数的请求，拒绝执行且不产生任何写入");
        }
        return switch (bizType) {
            case BIZ_ACTUAL -> FingerprintUtil.fromJson(row.responseBody(), RollActualView.class);
            default -> FingerprintUtil.fromJson(row.responseBody(), RollView.class);
        };
    }

    private void saveIdempotent(String requestId, String bizType, String fingerprint,
                                int httpStatus, String body) {
        boolean inserted = repo.tryInsertIdempotent(requestId, bizType, fingerprint, httpStatus,
                body, LocalDateTime.now(clock));
        if (!inserted) {
            // 并发同键首调竞争：唯一约束裁决后本事务所有写入将随异常回滚，
            // 客户端用相同 requestId 重放即可取得首次结果。
            throw new ConflictException(HttpStatus.CONFLICT,
                    "requestId " + requestId + " 正在被并发处理，唯一记录已存在，请用相同参数重放");
        }
    }

    /** 冻结指纹载体：覆盖 requestId 与目标轧辊号。 */
    private record FreezeFingerprint(String requestId, String rollNo) {
    }
}
