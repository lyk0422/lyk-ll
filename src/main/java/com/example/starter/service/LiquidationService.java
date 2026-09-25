package com.example.starter.service;

import com.example.starter.domain.LiquidationReason;
import com.example.starter.domain.LiquidationStatus;
import com.example.starter.domain.MarginMath;
import com.example.starter.dto.ApiDtos;
import com.example.starter.error.ApiException;
import com.example.starter.repo.AccountRepository;
import com.example.starter.repo.LiabilityRepository;
import com.example.starter.repo.LiquidationRepository;
import com.example.starter.repo.MarginCallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 强平服务。强平指令生成后先处于 PENDING（可因比例恢复被撤销）；
 * 一旦执行即终态不可撤销。执行按负债从大到小逐笔平仓，
 * 平到比例恢复至 140% 以上（含）即停，最后一笔可部分平仓，不得多平。
 */
@Service
public class LiquidationService {

    private final AccountRepository accountRepo;
    private final LiabilityRepository liabilityRepo;
    private final MarginCallRepository marginCallRepo;
    private final LiquidationRepository liquidationRepo;
    private final Clock clock;

    public LiquidationService(AccountRepository accountRepo, LiabilityRepository liabilityRepo,
                              MarginCallRepository marginCallRepo, LiquidationRepository liquidationRepo,
                              Clock clock) {
        this.accountRepo = accountRepo;
        this.liabilityRepo = liabilityRepo;
        this.marginCallRepo = marginCallRepo;
        this.liquidationRepo = liquidationRepo;
        this.clock = clock;
    }

    /**
     * 生成待发强平指令；账户已有 PENDING 指令时复用（同一未结清周期不重复生成）。
     * 调用方须持有账户行锁。
     */
    public long createPendingOrder(long accountId, Long marginCallId, LiquidationReason reason,
                                   LocalDateTime now) {
        var pending = liquidationRepo.findPendingByAccount(accountId);
        if (!pending.isEmpty()) {
            return pending.get(0).id();
        }
        accountRepo.updateStatus(accountId, "LIQUIDATING");
        return liquidationRepo.insertOrder(accountId, marginCallId, reason.name(), now);
    }

    /** 恢复结算结果：被结清的追保通知与被撤销的待发强平指令。 */
    public record SettlementResult(Long resolvedMarginCallId, List<Long> cancelledOrderIds) {
        public boolean changed() {
            return resolvedMarginCallId != null || !cancelledOrderIds.isEmpty();
        }
    }

    /**
     * 比例恢复至 140% 以上后的结算：结清未结追保通知、撤销全部待发强平指令。
     * 调用方须持有账户行锁。
     */
    public SettlementResult settleRecovered(long accountId, LocalDateTime now) {
        Long resolvedCallId = null;
        var openCall = marginCallRepo.findOpenByAccount(accountId);
        if (openCall.isPresent()) {
            marginCallRepo.resolve(openCall.get().id(), now);
            resolvedCallId = openCall.get().id();
        }
        List<Long> cancelled = new ArrayList<>();
        for (var order : liquidationRepo.findPendingByAccount(accountId)) {
            liquidationRepo.transitionIfPending(order.id(), LiquidationStatus.CANCELLED.name(), now);
            cancelled.add(order.id());
        }
        if (resolvedCallId != null || !cancelled.isEmpty()) {
            accountRepo.updateStatus(accountId, "NORMAL");
        }
        return new SettlementResult(resolvedCallId, cancelled);
    }

    /**
     * 逾期扫描：为所有已过补足截止时间仍未结清的追保通知生成强平指令。
     *
     * @param asOf 判定时刻（为空取当前时钟）
     */
    @Transactional
    public ApiDtos.ProcessDueResponse processDue(LocalDateTime asOf) {
        LocalDateTime now = asOf != null ? asOf : LocalDateTime.now(clock);
        List<Long> created = new ArrayList<>();
        for (var call : marginCallRepo.findOverdueOpen(now)) {
            // 逐账户加锁后复核：可能已被并发入账结清
            AccountRepository.AccountRow account = accountRepo.lockById(call.accountId());
            var openCall = marginCallRepo.findOpenByAccount(call.accountId());
            if (openCall.isEmpty()) {
                continue;
            }
            if (MarginMath.atLeast(account.totalAssets(), account.totalLiabilities(),
                    MarginMath.RECOVER_THRESHOLD_PCT)) {
                settleRecovered(call.accountId(), now);
                continue;
            }
            long orderId = createPendingOrder(call.accountId(), openCall.get().id(),
                    LiquidationReason.OVERDUE, now);
            if (!created.contains(orderId)) {
                created.add(orderId);
            }
        }
        return new ApiDtos.ProcessDueResponse(created);
    }

    /**
     * 执行强平指令：账户行锁内按负债从大到小逐笔平仓，比例恢复至 140% 即停。
     * 与保证金入账互斥：先到达者决定结果，后到入账按余下负债重算。
     */
    @Transactional
    public ApiDtos.LiquidationView execute(long orderId) {
        var order = liquidationRepo.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("强平指令不存在: " + orderId));
        if (!LiquidationStatus.PENDING.name().equals(order.status())) {
            throw ApiException.conflict("强平指令已为终态不可撤销: " + order.status());
        }
        AccountRepository.AccountRow account = accountRepo.lockById(order.accountId());
        // 锁内复核：等待锁期间可能已被恢复结算撤销
        order = liquidationRepo.findOrderById(orderId).orElseThrow();
        if (!LiquidationStatus.PENDING.name().equals(order.status())) {
            throw ApiException.conflict("强平指令已为终态不可撤销: " + order.status());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        long assets = account.totalAssets();
        long liabilities = account.totalLiabilities();
        long remaining = MarginMath.requiredRepaymentCents(assets, liabilities);

        List<ApiDtos.ItemView> items = new ArrayList<>();
        if (remaining > 0) {
            for (var liability : liabilityRepo.findOpenOrderByOutstandingDesc(order.accountId())) {
                if (remaining <= 0) {
                    break;
                }
                long close = Math.min(liability.outstanding(), remaining);
                liquidationRepo.insertItem(orderId, liability.id(), close);
                liabilityRepo.repay(liability.id(), close);
                items.add(new ApiDtos.ItemView(liability.id(), MarginMath.formatCents(close)));
                remaining -= close;
                assets -= close;
                liabilities -= close;
            }
            accountRepo.updateTotals(order.accountId(), assets, liabilities);
        }

        liquidationRepo.transitionIfPending(orderId, LiquidationStatus.DONE.name(), now);
        marginCallRepo.findOpenByAccount(order.accountId())
                .ifPresent(c -> marginCallRepo.markLiquidated(c.id(), now));
        if (liquidationRepo.findPendingByAccount(order.accountId()).isEmpty()) {
            accountRepo.updateStatus(order.accountId(), "NORMAL");
        }
        return new ApiDtos.LiquidationView(orderId, order.accountId(), order.reason(),
                LiquidationStatus.DONE.name(), items, MarginMath.ratioBp(assets, liabilities));
    }

    /** 查询指令当前状态（含已执行明细）。 */
    @Transactional(readOnly = true)
    public ApiDtos.LiquidationView view(long orderId) {
        var order = liquidationRepo.findOrderById(orderId)
                .orElseThrow(() -> ApiException.notFound("强平指令不存在: " + orderId));
        var account = accountRepo.findById(order.accountId());
        var items = liquidationRepo.findItemsByOrder(orderId).stream()
                .map(i -> new ApiDtos.ItemView(i.liabilityId(), MarginMath.formatCents(i.amount())))
                .toList();
        return new ApiDtos.LiquidationView(order.id(), order.accountId(), order.reason(), order.status(),
                items, MarginMath.ratioBp(account.totalAssets(), account.totalLiabilities()));
    }
}
