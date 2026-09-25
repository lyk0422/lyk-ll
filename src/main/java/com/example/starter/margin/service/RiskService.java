package com.example.starter.margin.service;

import com.example.starter.margin.api.DepositRequest;
import com.example.starter.margin.api.DepositResponse;
import com.example.starter.margin.api.LiquidationResponse;
import com.example.starter.margin.api.RiskAssessmentResponse;
import com.example.starter.margin.domain.DepositResult;
import com.example.starter.margin.domain.JobStatus;
import com.example.starter.margin.domain.LiquidationReason;
import com.example.starter.margin.domain.NoticeStatus;
import com.example.starter.margin.po.DepositPo;
import com.example.starter.margin.po.JobPo;
import com.example.starter.margin.po.LiabilityPo;
import com.example.starter.margin.po.LiquidationItemPo;
import com.example.starter.margin.po.NoticePo;
import com.example.starter.margin.po.SnapshotPo;
import com.example.starter.margin.repo.AccountDataRepository;
import com.example.starter.margin.repo.RiskRepository;
import com.example.starter.margin.support.MarginClock;
import com.example.starter.margin.support.RiskRules;
import com.example.starter.margin.support.TradingCalendar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 信用账户风控核心服务：维持担保比例评估、追保通知、追加保证金幂等入账与阶梯强制平仓。
 *
 * <p>竞态口径：入账与强平等所有写操作在同一事务内先对 account 行加 SELECT … FOR UPDATE 悲观锁，
 * 按账户串行化，先获得锁并提交者决定结果——入账先达标则撤销待发强平；强平先翻转为 EXECUTED
 * 则迟到入账计入余下负债重算。数据库唯一索引（OPEN 通知、PENDING 作业、requestId）兜底。</p>
 */
@Service
public class RiskService {

    private final AccountDataRepository accountData;
    private final RiskRepository riskRepo;
    private final TradingCalendar calendar;
    private final MarginClock clock;

    public RiskService(AccountDataRepository accountData, RiskRepository riskRepo,
                       TradingCalendar calendar, MarginClock clock) {
        this.accountData = accountData;
        this.riskRepo = riskRepo;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * 基于账户最新已确认快照评估：
     * 比例 &lt; 110% 直接生成 PENDING 强平（BELOW_110）；110% &le; 比例 &lt; 130% 生成/合并追保通知；
     * 比例 &ge; 130%（含恰好 130%）不动作。同一未结清周期重复触发只保留一条 OPEN 通知。
     */
    @Transactional
    public RiskAssessmentResponse assess(String accountNo) {
        accountData.lockAccount(accountNo);
        SnapshotPo snap = requireSnapshot(accountNo);
        BigDecimal ratio = RiskRules.ratio(snap.totalAssets(), snap.totalLiabilities());
        NoticePo openNotice = riskRepo.findOpenNotice(accountNo);
        Long jobId = null;
        boolean noticeCreated = false;
        String action = "NONE";
        Instant deadline = openNotice == null ? null : openNotice.deadline();
        Long noticeId = openNotice == null ? null : openNotice.id();

        if (RiskRules.ratioLessThan(snap.totalAssets(), snap.totalLiabilities(),
                RiskRules.FORCE_LIQUIDATION_LINE)) {
            // 低于 110%（不含）：跳过追保直接强平；恰好 110% 不进此分支
            action = "LIQUIDATE";
            jobId = riskRepo.tryInsertPendingJob(accountNo, LiquidationReason.BELOW_110, clock.now());
        } else if (RiskRules.ratioLessThan(snap.totalAssets(), snap.totalLiabilities(),
                RiskRules.MARGIN_CALL_LINE)) {
            // [110%, 130%)：追保；恰好 130% 不触发
            action = "MARGIN_NOTICE";
            if (openNotice == null) {
                Instant dl = calendar.nextTradingDayDeadline(clock.now());
                noticeCreated = riskRepo.tryInsertOpenNotice(accountNo, ratio, dl, clock.now());
                if (noticeCreated) {
                    openNotice = riskRepo.findOpenNotice(accountNo);
                    noticeId = openNotice.id();
                    deadline = dl;
                }
            }
        }
        return new RiskAssessmentResponse(accountNo, snap.totalAssets(), snap.totalLiabilities(), ratio,
                action, noticeCreated, noticeId, deadline, jobId);
    }

    /**
     * 追加保证金入账（requestId 幂等）。指纹 = requestId+账户+金额+币种。
     * 同键同参返回首次结果；同键异参抛 {@link DepositConflictException}（映射 409），不重复入账。
     */
    @Transactional
    public DepositResponse deposit(DepositRequest req) {
        validateMoney(req.amount());

        // 指纹优先：requestId 已存在时，同参返回首次结果、异参直接 409（即使目标账户缺失）
        DepositPo first = riskRepo.findDeposit(req.requestId());
        if (first != null) {
            return replayOrConflict(first, req);
        }

        var account = accountData.findAccount(req.accountNo());
        if (account == null) {
            throw new IllegalArgumentException("账户不存在: " + req.accountNo());
        }
        if (!account.baseCurrency().equals(req.currency())) {
            throw new IllegalArgumentException("入账币种与账户记账币种不一致: " + req.currency());
        }

        // 账户行锁：与强平执行串行化，先到者决定结果
        accountData.lockAccount(req.accountNo());

        // 加锁后双检：防止两个并发请求首插同一 requestId
        DepositPo existing = riskRepo.findDeposit(req.requestId());
        if (existing != null) {
            return replayOrConflict(existing, req);
        }

        SnapshotPo snap = requireSnapshot(req.accountNo());
        BigDecimal liabilities = snap.totalLiabilities();
        boolean executed = riskRepo.existsExecutedJob(req.accountNo());

        DepositResult result;
        BigDecimal newAssets;
        BigDecimal newRatio;
        if (liabilities.signum() == 0) {
            // 负债已全额强平清零：不保留保证金，拒绝入账（requestId 仍落库以维持幂等）
            result = DepositResult.REJECTED_CLOSED;
            newAssets = snap.totalAssets();
            newRatio = null;
        } else {
            result = executed ? DepositResult.APPLIED_TO_REMAINING : DepositResult.ACCEPTED;
            newAssets = RiskRules.money(snap.totalAssets().add(req.amount()));
            newRatio = RiskRules.ratio(newAssets, liabilities);
            persistSnapshot(req.accountNo(), newAssets, liabilities,
                    executed ? "强平后迟到保证金计入余下负债" : "追加保证金入账");
        }

        boolean backToMaintenance = liabilities.signum() > 0
                && RiskRules.ratioAtLeast(newAssets, liabilities, RiskRules.MAINTENANCE_LINE);
        boolean noticeResolved = false;
        boolean jobCancelled = false;
        if (backToMaintenance) {
            NoticePo open = riskRepo.findOpenNotice(req.accountNo());
            if (open != null) {
                noticeResolved = riskRepo.resolveNotice(open.id(), clock.now());
            }
            // 待发（尚未开始执行）强平在入账达标时撤销；EXECUTED 终态不受影响
            JobPo pendingJob = riskRepo.findPendingJob(req.accountNo());
            if (pendingJob != null) {
                jobCancelled = riskRepo.cancelPendingJob(pendingJob.id());
            }
        }

        DepositPo po = new DepositPo(req.requestId(), req.accountNo(), RiskRules.money(req.amount()),
                req.currency(), result, false, newRatio, noticeResolved, jobCancelled, clock.now());
        try {
            riskRepo.insertDeposit(po);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 并发同 requestId 首插竞争落败：回查并按重放/冲突处理，不重复入账
            DepositPo winner = riskRepo.findDeposit(req.requestId());
            if (winner == null) {
                throw dup;
            }
            return replayOrConflict(winner, req);
        }
        return toDepositResponse(po);
    }

    /**
     * 同 requestId 幂等判定：指纹一致返回首次结果（标记重放）；指纹不一致抛 409。
     */
    private DepositResponse replayOrConflict(DepositPo existing, DepositRequest req) {
        if (!sameFingerprint(existing, req)) {
            throw new DepositConflictException(req.requestId());
        }
        return new DepositResponse(existing.requestId(), existing.accountNo(), existing.amount(),
                existing.currency(), existing.result(), true, existing.ratioAfter(),
                existing.noticeResolved(), existing.jobCancelled(), existing.depositedAt());
    }

    /**
     * 逾期巡检：OPEN 通知且当前时刻已到/过截止时间，比例仍未回到 140%（含），
     * 则通知置 EXPIRED 并生成 PENDING 强平作业（OVERDUE）。截止时刻（15:00）整点即视为逾期。
     */
    @Transactional
    public RiskAssessmentResponse processOverdue(String accountNo) {
        accountData.lockAccount(accountNo);
        SnapshotPo snap = requireSnapshot(accountNo);
        BigDecimal ratio = RiskRules.ratio(snap.totalAssets(), snap.totalLiabilities());
        NoticePo open = riskRepo.findOpenNotice(accountNo);
        Long jobId = null;
        String action = "NONE";
        if (open != null && !clock.now().isBefore(open.deadline())
                && !RiskRules.ratioAtLeast(snap.totalAssets(), snap.totalLiabilities(),
                RiskRules.MAINTENANCE_LINE)) {
            if (riskRepo.expireNotice(open.id(), clock.now())) {
                jobId = riskRepo.tryInsertPendingJob(accountNo, LiquidationReason.OVERDUE, clock.now());
                action = "LIQUIDATE";
            }
        }
        return new RiskAssessmentResponse(accountNo, snap.totalAssets(), snap.totalLiabilities(), ratio,
                action, false, open == null ? null : open.id(),
                open == null ? null : open.deadline(), jobId);
    }

    /**
     * 只读查询强平作业当前状态与已生成指令，不产生任何副作用。
     */
    @Transactional(readOnly = true)
    public LiquidationResponse getJob(long jobId) {
        JobPo job = riskRepo.findJobById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("强平作业不存在: " + jobId);
        }
        return buildResponse(job);
    }

    /**
     * 执行强平作业：按未偿负债从大到小逐笔处置担保物偿还，比例恢复至 140%（含）即停，
     * 末笔只偿还使比例达标所需的最小分数金额，不得多平；负债全部清零亦停。
     * PENDING → EXECUTED 为终态；重复调用或对已撤销作业调用返回当前持久化状态，绝不重复执行。
     */
    @Transactional
    public LiquidationResponse executeJob(long jobId) {
        JobPo job = riskRepo.findJobById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("强平作业不存在: " + jobId);
        }
        accountData.lockAccount(job.accountNo());
        job = riskRepo.findJobById(jobId);
        if (job.status() != JobStatus.PENDING) {
            return buildResponse(job);
        }

        SnapshotPo snap = requireSnapshot(job.accountNo());
        BigDecimal assets = snap.totalAssets();
        BigDecimal totalDebt = snap.totalLiabilities();
        List<LiabilityPo> debts = accountData.findOutstandingLiabilities(job.accountNo());
        List<LiquidationItemPo> items = new ArrayList<>();
        int seq = 1;

        for (LiabilityPo debt : debts) {
            BigDecimal cap = debt.outstandingAmount();
            BigDecimal need = minimalRepayToCross(assets, totalDebt, cap);
            // need 为 null：即使全额偿还本笔也无法达标，整笔偿还后继续下一笔；
            // 偿还额同时以现存总资产为上限（担保物处置所得不能超过资产）。
            BigDecimal desired = need == null ? cap : need.min(cap);
            BigDecimal repay = RiskRules.money(desired.min(assets));
            if (repay.signum() == 0) {
                // 担保物已处置殆尽仍未达标：余下为不足受偿负债，终止阶梯
                break;
            }

            accountData.repayLiability(job.accountNo(), debt.liabilityNo(), repay, clock.now());
            assets = RiskRules.money(assets.subtract(repay));
            totalDebt = RiskRules.money(totalDebt.subtract(repay));
            BigDecimal ratioAfter = RiskRules.ratio(assets, totalDebt);
            LiquidationItemPo item = new LiquidationItemPo(null, jobId, job.accountNo(), debt.liabilityNo(),
                    seq++, repay, repay, ratioAfter, clock.now());
            riskRepo.insertItem(item);
            items.add(item);

            if (totalDebt.signum() == 0
                    || RiskRules.ratioAtLeast(assets, totalDebt, RiskRules.MAINTENANCE_LINE)) {
                break;
            }
        }

        BigDecimal finalRatio = RiskRules.ratio(assets, totalDebt);
        // 终态翻转：CAS PENDING→EXECUTED；账户行锁已保证并发入账无法穿插
        if (!riskRepo.markExecuted(jobId, finalRatio, clock.now())) {
            throw new IllegalStateException("强平作业状态已变化，拒绝重复执行: " + jobId);
        }
        persistSnapshot(job.accountNo(), assets, totalDebt, "强制平仓处置后确认快照");
        NoticePo open = riskRepo.findOpenNotice(job.accountNo());
        if (open != null && open.status() == NoticeStatus.OPEN) {
            riskRepo.expireNotice(open.id(), clock.now());
        }
        return buildResponse(riskRepo.findJobById(jobId));
    }

    /**
     * 计算在当前总资产 A、总负债 L 下，处置担保物偿还 r 使比例恢复至 140%（含）所需的最小偿还额
     * （精确到分，HALF_UP 口径一致并做达标校正）。
     * 由 (A-r)/(L-r) &ge; 1.40 推得 r &ge; (1.4L - A)/0.4。
     *
     * @param debtCap 本笔负债未偿上限；若全额偿还仍不能达标返回 null（继续平下一笔）
     */
    private BigDecimal minimalRepayToCross(BigDecimal assets, BigDecimal totalLiabilities, BigDecimal debtCap) {
        BigDecimal gap = RiskRules.MAINTENANCE_LINE.multiply(totalLiabilities).subtract(assets);
        if (gap.signum() <= 0) {
            // 当前比例已达到/高于 140%，无需偿还
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal raw = gap.divide(new BigDecimal("0.4"), 8, RoundingMode.HALF_UP);
        BigDecimal r = raw.setScale(2, RoundingMode.HALF_UP);
        // 含 140%：取整后若仍未达标（向下舍入情形），逐分补到达标
        BigDecimal cent = BigDecimal.ONE.movePointLeft(2);
        int guard = 0;
        while (!RiskRules.ratioAtLeast(assets.subtract(r), totalLiabilities.subtract(r),
                RiskRules.MAINTENANCE_LINE) && guard++ < 10) {
            r = r.add(cent);
        }
        if (r.compareTo(debtCap) > 0) {
            return null;
        }
        return r;
    }

    private LiquidationResponse buildResponse(JobPo job) {
        List<LiquidationResponse.Item> items = riskRepo.findItemsByJob(job.id()).stream()
                .map(i -> new LiquidationResponse.Item(i.seqNo(), i.liabilityNo(), i.repaidAmount(),
                        i.assetsReduced(), i.ratioAfterPct()))
                .toList();
        return new LiquidationResponse(job.id(), job.accountNo(), job.status().name(),
                job.reason().name(), items, job.finalRatio(), job.executedAt());
    }

    private boolean sameFingerprint(DepositPo po, DepositRequest req) {
        return po.accountNo().equals(req.accountNo())
                && po.amount().compareTo(RiskRules.money(req.amount())) == 0
                && po.currency().equals(req.currency());
    }

    private DepositResponse toDepositResponse(DepositPo po) {
        return new DepositResponse(po.requestId(), po.accountNo(), po.amount(), po.currency(),
                po.result(), po.replayed(), po.ratioAfter(), po.noticeResolved(), po.jobCancelled(),
                po.depositedAt());
    }

    private SnapshotPo requireSnapshot(String accountNo) {
        SnapshotPo snap = accountData.findLatestConfirmedSnapshot(accountNo);
        if (snap == null) {
            throw new IllegalArgumentException("账户无已确认快照，无法判定比例: " + accountNo);
        }
        return snap;
    }

    private void persistSnapshot(String accountNo, BigDecimal totalAssets,
                                 BigDecimal totalLiabilities, String remark) {
        Instant now = clock.now();
        accountData.insertSnapshot(new SnapshotPo(null, accountNo, RiskRules.money(totalAssets),
                RiskRules.money(totalLiabilities), true, now, remark, now));
    }

    private void validateMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("入账金额必须为正数");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("入账金额最小单位为分");
        }
    }
}
