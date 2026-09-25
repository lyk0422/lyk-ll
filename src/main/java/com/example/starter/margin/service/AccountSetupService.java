package com.example.starter.margin.service;

import com.example.starter.margin.api.AccountView;
import com.example.starter.margin.api.SetupAccountRequest;
import com.example.starter.margin.api.SnapshotRequest;
import com.example.starter.margin.domain.LiabilityStatus;
import com.example.starter.margin.po.AccountPo;
import com.example.starter.margin.po.JobPo;
import com.example.starter.margin.po.LiabilityPo;
import com.example.starter.margin.po.NoticePo;
import com.example.starter.margin.po.SnapshotPo;
import com.example.starter.margin.repo.AccountDataRepository;
import com.example.starter.margin.repo.RiskRepository;
import com.example.starter.margin.support.MarginClock;
import com.example.starter.margin.support.RiskRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 合成数据装配与账户查询：创建账户+逐笔负债+首条已确认快照、追加快照、查询全景。
 * 全部为本地合成数据，不依赖外部行情。
 */
@Service
public class AccountSetupService {

    private final AccountDataRepository accountData;
    private final RiskRepository riskRepo;
    private final MarginClock clock;

    public AccountSetupService(AccountDataRepository accountData, RiskRepository riskRepo, MarginClock clock) {
        this.accountData = accountData;
        this.riskRepo = riskRepo;
        this.clock = clock;
    }

    /**
     * 创建合成信用账户：写入账户、逐笔未偿负债及首条 confirmed 快照。
     * totalLiabilities 由请求显式给出（应等于各笔负债合计），允许演示独立行情口径。
     */
    @Transactional
    public AccountView setup(SetupAccountRequest req) {
        Instant now = clock.now();
        if (accountData.findAccount(req.accountNo()) != null) {
            throw new IllegalArgumentException("账户已存在: " + req.accountNo());
        }
        accountData.insertAccount(new AccountPo(req.accountNo(), req.clientName(), req.currency(), now));
        BigDecimal sum = BigDecimal.ZERO.setScale(2);
        for (SetupAccountRequest.LiabilitySeed seed : req.liabilities()) {
            BigDecimal amount = RiskRules.money(seed.amount());
            accountData.insertLiability(new LiabilityPo(null, req.accountNo(), seed.liabilityNo(),
                    req.currency(), amount, LiabilityStatus.OUTSTANDING, now, null));
            sum = sum.add(amount);
        }
        BigDecimal totalLiabilities = RiskRules.money(sum);
        accountData.insertSnapshot(new SnapshotPo(null, req.accountNo(), RiskRules.money(req.totalAssets()),
                totalLiabilities, true, now, "初始合成快照", now));
        return view(req.accountNo());
    }

    /** 追加一条已确认快照（模拟行情/账务变动后的最新确认口径）。 */
    @Transactional
    public AccountView addSnapshot(String accountNo, SnapshotRequest req) {
        if (accountData.findAccount(accountNo) == null) {
            throw new IllegalArgumentException("账户不存在: " + accountNo);
        }
        Instant now = clock.now();
        accountData.insertSnapshot(new SnapshotPo(null, accountNo, RiskRules.money(req.totalAssets()),
                RiskRules.money(req.totalLiabilities()), true, now,
                req.remark() == null ? "行情更新快照" : req.remark(), now));
        return view(accountNo);
    }

    /** 查询账户全景。 */
    @Transactional(readOnly = true)
    public AccountView view(String accountNo) {
        AccountPo account = accountData.findAccount(accountNo);
        if (account == null) {
            throw new IllegalArgumentException("账户不存在: " + accountNo);
        }
        SnapshotPo snap = accountData.findLatestConfirmedSnapshot(accountNo);
        BigDecimal assets = snap == null ? null : snap.totalAssets();
        BigDecimal liabilities = snap == null ? null : snap.totalLiabilities();
        BigDecimal ratio = snap == null ? null
                : RiskRules.ratio(snap.totalAssets(), snap.totalLiabilities());
        NoticePo open = riskRepo.findOpenNotice(accountNo);
        JobPo pending = riskRepo.findPendingJob(accountNo);
        JobPo latest = riskRepo.findLatestJobAny(accountNo);
        JobPo executed = latest != null && latest.status().name().equals("EXECUTED") ? latest : null;
        return new AccountView(accountNo, account.clientName(), account.baseCurrency(),
                assets, liabilities, ratio,
                open == null ? null : open.status().name(),
                open == null ? null : open.deadline(),
                pending == null ? null : pending.status().name(),
                pending == null ? null : pending.id(),
                executed == null ? null : executed.status().name(),
                executed == null ? null : executed.id());
    }
}
