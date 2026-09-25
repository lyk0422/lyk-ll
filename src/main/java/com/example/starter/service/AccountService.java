package com.example.starter.service;

import com.example.starter.domain.MarginMath;
import com.example.starter.dto.ApiDtos;
import com.example.starter.error.ApiException;
import com.example.starter.repo.AccountRepository;
import com.example.starter.repo.LiabilityRepository;
import com.example.starter.repo.LiquidationRepository;
import com.example.starter.repo.MarginCallRepository;
import com.example.starter.repo.SnapshotRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 账户与快照服务。快照确认后在同一事务内完成风险评估：
 * 低于110%直接生成强平指令；低于130%（含110%）触发/合并追保通知；
 * 比例恢复至140%以上（含）则结清追保并撤销待发强平。
 */
@Service
public class AccountService {

    private final AccountRepository accountRepo;
    private final SnapshotRepository snapshotRepo;
    private final LiabilityRepository liabilityRepo;
    private final MarginCallRepository marginCallRepo;
    private final LiquidationRepository liquidationRepo;
    private final LiquidationService liquidationService;
    private final TradingCalendar tradingCalendar;
    private final Clock clock;

    public AccountService(AccountRepository accountRepo, SnapshotRepository snapshotRepo,
                          LiabilityRepository liabilityRepo, MarginCallRepository marginCallRepo,
                          LiquidationRepository liquidationRepo, LiquidationService liquidationService,
                          TradingCalendar tradingCalendar, Clock clock) {
        this.accountRepo = accountRepo;
        this.snapshotRepo = snapshotRepo;
        this.liabilityRepo = liabilityRepo;
        this.marginCallRepo = marginCallRepo;
        this.liquidationRepo = liquidationRepo;
        this.liquidationService = liquidationService;
        this.tradingCalendar = tradingCalendar;
        this.clock = clock;
    }

    @Transactional
    public ApiDtos.AccountView createAccount(ApiDtos.CreateAccountRequest req) {
        String accountNo = req.accountNo() == null ? "" : req.accountNo().trim();
        if (accountNo.isEmpty()) {
            throw ApiException.badRequest("accountNo 不能为空");
        }
        String currency = req.currency() == null || req.currency().isBlank()
                ? "CNY" : req.currency().trim();
        long id;
        try {
            id = accountRepo.insert(accountNo, currency);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("账户编号已存在: " + accountNo);
        }
        return view(id);
    }

    @Transactional(readOnly = true)
    public ApiDtos.AccountView view(long accountId) {
        AccountRepository.AccountRow row = accountRepo.findById(accountId);
        var openCall = marginCallRepo.findOpenByAccount(accountId)
                .map(c -> new ApiDtos.MarginCallView(c.id(), c.ratioBp(),
                        c.triggerAt().toString(), c.deadline().toString(), c.status(), c.mergeCount()))
                .orElse(null);
        List<Long> pending = liquidationRepo.findPendingByAccount(accountId).stream()
                .map(LiquidationRepository.OrderRow::id).toList();
        return new ApiDtos.AccountView(row.id(), row.accountNo(), row.currency(),
                MarginMath.formatCents(row.totalAssets()), MarginMath.formatCents(row.totalLiabilities()),
                MarginMath.ratioBp(row.totalAssets(), row.totalLiabilities()), row.status(),
                openCall, pending);
    }

    /**
     * 确认资产负债快照并评估风险。账户行加悲观写锁，与入账、强平执行串行化。
     */
    @Transactional
    public ApiDtos.SnapshotResponse confirmSnapshot(long accountId, ApiDtos.SnapshotRequest req) {
        accountRepo.lockById(accountId);
        if (req.totalAssets() == null) {
            throw ApiException.badRequest("totalAssets 不能为空");
        }
        final long assets;
        try {
            assets = MarginMath.parseCents(req.totalAssets());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        List<Long> liabilityCents = new ArrayList<>();
        if (req.liabilities() != null) {
            for (String item : req.liabilities()) {
                try {
                    liabilityCents.add(MarginMath.parseCents(item));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest(e.getMessage());
                }
            }
        }
        long liabilities = liabilityCents.stream().mapToLong(Long::longValue).sum();

        LocalDateTime now = LocalDateTime.now(clock);
        long snapshotId = snapshotRepo.insert(accountId, assets, liabilities, now);
        liabilityRepo.replaceForSnapshot(accountId, snapshotId, liabilityCents);
        accountRepo.updateTotals(accountId, assets, liabilities);

        return evaluateRisk(accountId, assets, liabilities, now, snapshotId);
    }

    /**
     * 风险评估：以最新已确认快照口径（账户当前总资产/总负债）判定。
     * 须在持有账户行锁的事务内调用。
     */
    private ApiDtos.SnapshotResponse evaluateRisk(long accountId, long assets, long liabilities,
                                                  LocalDateTime now, long snapshotId) {
        Integer ratioBp = MarginMath.ratioBp(assets, liabilities);
        if (liabilities > 0 && MarginMath.below(assets, liabilities, MarginMath.FORCE_THRESHOLD_PCT)) {
            // 低于110%：跳过追保直接强平；已有未结清追保通知随之进入强平结清
            marginCallRepo.findOpenByAccount(accountId).ifPresent(c -> {
                marginCallRepo.markLiquidated(c.id(), now);
            });
            long orderId = liquidationService.createPendingOrder(accountId, null,
                    com.example.starter.domain.LiquidationReason.DIRECT_BELOW_110, now);
            return new ApiDtos.SnapshotResponse(snapshotId, ratioBp, "DIRECT_LIQUIDATION", null, orderId);
        }
        if (liabilities > 0 && MarginMath.below(assets, liabilities, MarginMath.CALL_THRESHOLD_PCT)) {
            // 低于130%（含恰好110%）：触发追保；同一未结清周期仅一条，重复触发合并
            var existing = marginCallRepo.findOpenByAccount(accountId);
            if (existing.isPresent()) {
                marginCallRepo.incrementMerge(existing.get().id());
                return new ApiDtos.SnapshotResponse(snapshotId, ratioBp, "MARGIN_CALL",
                        existing.get().id(), null);
            }
            long callId = marginCallRepo.insert(accountId, ratioBp == null ? 0 : ratioBp, now,
                    tradingCalendar.nextTradingDayDeadline(now));
            return new ApiDtos.SnapshotResponse(snapshotId, ratioBp, "MARGIN_CALL", callId, null);
        }
        if (MarginMath.atLeast(assets, liabilities, MarginMath.RECOVER_THRESHOLD_PCT)) {
            // 恢复至140%以上（含）：结清未结追保并撤销待发强平
            boolean recovered = liquidationService.settleRecovered(accountId, now).changed();
            if (recovered) {
                return new ApiDtos.SnapshotResponse(snapshotId, ratioBp, "RECOVERED", null, null);
            }
        }
        return new ApiDtos.SnapshotResponse(snapshotId, ratioBp, "NONE", null, null);
    }
}
