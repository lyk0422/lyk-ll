package com.example.starter.margin.service;

import com.example.starter.margin.AbstractMarginH2Test;
import com.example.starter.margin.api.DepositRequest;
import com.example.starter.margin.api.DepositResponse;
import com.example.starter.margin.api.LiquidationResponse;
import com.example.starter.margin.api.RiskAssessmentResponse;
import com.example.starter.margin.api.SnapshotRequest;
import com.example.starter.margin.domain.DepositResult;
import com.example.starter.margin.domain.JobStatus;
import com.example.starter.margin.domain.NoticeStatus;
import com.example.starter.margin.po.LiabilityPo;
import com.example.starter.margin.po.NoticePo;
import com.example.starter.margin.po.SnapshotPo;
import com.example.starter.margin.repo.RiskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 风控主流程 H2 事务测试：130/140/110 阈值、通知唯一、入账幂等、阶梯强平即止、金额精度与逾期流程。
 */
class RiskServiceTest extends AbstractMarginH2Test {

    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    @Autowired
    private RiskService riskService;
    @Autowired
    private RiskRepository riskRepo;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private SnapshotPo latestSnapshot(String accountNo) {
        return accountData.findLatestConfirmedSnapshot(accountNo);
    }

    @Test
    void exactly130NoAction_below130OpensNotice_exactly110StillNotice() {
        // 恰好 130%：不触发
        setupAccount("A130", "1300.00", "CNY", new String[][]{{"L1", "1000.00"}});
        RiskAssessmentResponse r = riskService.assess("A130");
        assertThat(r.action()).isEqualTo("NONE");
        assertThat(riskRepo.findOpenNotice("A130")).isNull();

        // 125%：触发追保
        setupAccount("A125", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        RiskAssessmentResponse r2 = riskService.assess("A125");
        assertThat(r2.action()).isEqualTo("MARGIN_NOTICE");
        assertThat(r2.noticeCreated()).isTrue();
        NoticePo notice = riskRepo.findOpenNotice("A125");
        assertThat(notice.status()).isEqualTo(NoticeStatus.OPEN);

        // 截止时间为次一交易日 15:00（UTC+8）
        LocalDate noticeDay = Instant.now().atZone(CN).toLocalDate();
        LocalDate expectedDeadlineDay = noticeDay.plusDays(1);
        while (expectedDeadlineDay.getDayOfWeek().getValue() > 5) {
            expectedDeadlineDay = expectedDeadlineDay.plusDays(1);
        }
        assertThat(notice.deadline().atZone(CN).toLocalDate()).isEqualTo(expectedDeadlineDay);
        assertThat(notice.deadline().atZone(CN).getHour()).isEqualTo(15);

        // 恰好 110%：仍走追保，不直接强平
        setupAccount("A110", "1100.00", "CNY", new String[][]{{"L1", "1000.00"}});
        RiskAssessmentResponse r3 = riskService.assess("A110");
        assertThat(r3.action()).isEqualTo("MARGIN_NOTICE");
        assertThat(riskRepo.findPendingJob("A110")).isNull();

        // 低于 110%：跳过追保直接强平
        setupAccount("A109", "1090.00", "CNY", new String[][]{{"L1", "1000.00"}});
        RiskAssessmentResponse r4 = riskService.assess("A109");
        assertThat(r4.action()).isEqualTo("LIQUIDATE");
        assertThat(r4.jobId()).isNotNull();
        assertThat(riskRepo.findOpenNotice("A109")).isNull();
    }

    @Test
    void repeatedAssessmentMergesIntoSingleOpenNotice() {
        setupAccount("AM", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        RiskAssessmentResponse first = riskService.assess("AM");
        RiskAssessmentResponse second = riskService.assess("AM");
        RiskAssessmentResponse third = riskService.assess("AM");
        assertThat(first.noticeCreated()).isTrue();
        assertThat(second.noticeCreated()).isFalse();
        assertThat(third.noticeCreated()).isFalse();
        // 同一未结清周期只有一条 OPEN 通知（ux_notice_open 唯一约束 + 合并逻辑）
        assertThat(riskRepo.findOpenNotice("AM")).isNotNull();
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM margin_notice WHERE account_no = ? AND status = 'OPEN'",
                Integer.class, "AM");
        assertThat(openCount).isEqualTo(1);
    }

    @Test
    void depositIsIdempotentSameFingerprintAnd409OnMismatch() {
        setupAccount("AD", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        riskService.assess("AD");

        DepositRequest req = new DepositRequestRequestBuilder()
                .requestId("REQ-1").accountNo("AD").amount("150.00").currency("CNY").build();
        DepositResponse first = riskService.deposit(req);
        assertThat(first.result()).isEqualTo(DepositResult.ACCEPTED);
        assertThat(first.replayed()).isFalse();
        assertThat(first.ratioAfter()).isEqualByComparingTo("1.4000");
        assertThat(first.noticeResolved()).isTrue();

        // 同键同参：重放首次结果，不重复入账
        DepositResponse replay = riskService.deposit(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.result()).isEqualTo(DepositResult.ACCEPTED);
        assertThat(replay.ratioAfter()).isEqualByComparingTo("1.4000");
        // 总资产只增加一次 150
        assertThat(latestSnapshot("AD").totalAssets()).isEqualByComparingTo("1400.00");

        // 同键异参（金额不同）：409，不改变任何数据
        DepositRequest conflict = new DepositRequestRequestBuilder()
                .requestId("REQ-1").accountNo("AD").amount("999.00").currency("CNY").build();
        assertThatThrownBy(() -> riskService.deposit(conflict))
                .isInstanceOf(DepositConflictException.class);
        assertThat(latestSnapshot("AD").totalAssets()).isEqualByComparingTo("1400.00");

        // 同键异账户：同样 409
        DepositRequest conflictAccount = new DepositRequestRequestBuilder()
                .requestId("REQ-1").accountNo("OTHER").amount("150.00").currency("CNY").build();
        assertThatThrownBy(() -> riskService.deposit(conflictAccount))
                .isInstanceOf(DepositConflictException.class);

        // 同键异币种：409
        DepositRequest conflictCcy = new DepositRequestRequestBuilder()
                .requestId("REQ-1").accountNo("AD").amount("150.00").currency("USD").build();
        assertThatThrownBy(() -> riskService.deposit(conflictCcy))
                .isInstanceOf(DepositConflictException.class);
    }

    @Test
    void depositBelow140KeepsNoticeOpen_atOrAbove140Resolves() {
        setupAccount("AB", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        riskService.assess("AB");
        // 追加 149.99 → 1399.99/1000 = 139.99%，仍未解除
        DepositResponse notEnough = riskService.deposit(new DepositRequestRequestBuilder()
                .requestId("R-NE").accountNo("AB").amount("149.99").currency("CNY").build());
        assertThat(notEnough.noticeResolved()).isFalse();
        assertThat(riskRepo.findOpenNotice("AB").status()).isEqualTo(NoticeStatus.OPEN);

        // 再追加 0.01 → 恰好 140%，解除（含 140%）
        DepositResponse enough = riskService.deposit(new DepositRequestRequestBuilder()
                .requestId("R-OK").accountNo("AB").amount("0.01").currency("CNY").build());
        assertThat(enough.ratioAfter()).isEqualByComparingTo("1.4000");
        assertThat(enough.noticeResolved()).isTrue();
        assertThat(riskRepo.findOpenNotice("AB")).isNull();
    }

    @Test
    void liquidationLadderGoesDebtsDescAndStopsAt140NoOverLiquidation() {
        // 三笔负债从大到小：500 / 300 / 200，合计 1000；资产 1100（恰好 110%，走追保后逾期）
        setupAccount("AL", "1100.00", "CNY",
                new String[][]{{"L-BIG", "500.00"}, {"L-MID", "300.00"}, {"L-SML", "200.00"}});
        riskService.assess("AL");
        // 拨动时钟到截止时刻（整点即逾期），overdue 生成 OVERDUE 作业
        NoticePo open = riskRepo.findOpenNotice("AL");
        clock.setFixed(open.deadline());
        RiskAssessmentResponse overdue = riskService.processOverdue("AL");
        assertThat(overdue.action()).isEqualTo("LIQUIDATE");
        Long jobId = overdue.jobId();
        assertThat(jobId).isNotNull();

        LiquidationResponse resp = riskService.executeJob(jobId);
        assertThat(resp.status()).isEqualTo(JobStatus.EXECUTED.name());
        // 需先全额平最大笔 500（平后 600/500=120% 仍不足），再部分平第二笔 250（350/250=140%）即停
        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).liabilityNo()).isEqualTo("L-BIG");
        assertThat(resp.items().get(0).repaidAmount()).isEqualByComparingTo("500.00");
        assertThat(resp.items().get(0).ratioAfterPct()).isEqualByComparingTo("1.2000");
        assertThat(resp.items().get(1).liabilityNo()).isEqualTo("L-MID");
        assertThat(resp.items().get(1).repaidAmount()).isEqualByComparingTo("250.00");
        // 不得多平：最小负债 L-SML 完全未动
        List<LiabilityPo> outstanding = accountData.findOutstandingLiabilities("AL");
        assertThat(outstanding).extracting(LiabilityPo::liabilityNo)
                .containsExactlyInAnyOrder("L-MID", "L-SML");
        assertThat(outstanding).filteredOn(l -> l.liabilityNo().equals("L-MID"))
                .first().extracting(LiabilityPo::outstandingAmount)
                .isEqualTo(new BigDecimal("50.00"));

        // 最终比例恰好 140%
        BigDecimal finalRatio = resp.finalRatio();
        assertThat(finalRatio).isEqualByComparingTo("1.4000");

        // 终态不可撤销：再次执行不产生新指令
        LiquidationResponse again = riskService.executeJob(jobId);
        assertThat(again.status()).isEqualTo(JobStatus.EXECUTED.name());
        assertThat(again.items()).hasSameSizeAs(resp.items());

        // 最终快照与实际未偿负债一致
        SnapshotPo snap = latestSnapshot("AL");
        BigDecimal outstandingSum = outstanding.stream()
                .map(LiabilityPo::outstandingAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(snap.totalLiabilities()).isEqualByComparingTo(outstandingSum);
        assertThat(RiskRules_ratio(snap)).isEqualByComparingTo(finalRatio);
    }

    @Test
    void liquidationRoundsToCentAndLastItemCanBePartial() {
        // 构造末笔部分偿还：负债 1000，资产 1250 → 需要 r >= (1400-1250)/0.4 = 375
        setupAccount("AP", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        RiskAssessmentResponse assessed = riskService.assess("AP");
        Long jobId;
        if (assessed.jobId() == null) {
            clock.setFixed(riskRepo.findOpenNotice("AP").deadline());
            jobId = riskService.processOverdue("AP").jobId();
        } else {
            jobId = assessed.jobId();
        }
        LiquidationResponse resp = riskService.executeJob(jobId);
        assertThat(resp.items()).hasSize(1);
        // 375 部分偿还，负债剩余 625
        assertThat(resp.items().get(0).repaidAmount()).isEqualByComparingTo("375.00");
        LiabilityPo remaining = accountData.findOutstandingLiabilities("AP").get(0);
        assertThat(remaining.outstandingAmount()).isEqualByComparingTo("625.00");
        // 资产 875 / 负债 625 = 140%
        assertThat(resp.finalRatio()).isEqualByComparingTo("1.4000");
    }

    @Test
    void overdueNotTriggeredBeforeDeadlineAndAfterCured() {
        setupAccount("AO", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        riskService.assess("AO");
        NoticePo open = riskRepo.findOpenNotice("AO");

        // 截止前一刻：不逾期
        clock.setFixed(open.deadline().minusSeconds(1));
        assertThat(riskService.processOverdue("AO").action()).isEqualTo("NONE");
        assertThat(riskRepo.findOpenNotice("AO").status()).isEqualTo(NoticeStatus.OPEN);

        // 截止前补足到 140%，到截止时刻也不逾期
        riskService.deposit(new DepositRequestRequestBuilder()
                .requestId("R-CURE").accountNo("AO").amount("150.00").currency("CNY").build());
        clock.setFixed(open.deadline());
        assertThat(riskService.processOverdue("AO").action()).isEqualTo("NONE");
        assertThat(riskRepo.findPendingJob("AO")).isNull();
    }

    @Test
    void depositThatCuresCancelsPendingLiquidation() {
        // 108% 直接生成 PENDING
        setupAccount("AC", "1080.00", "CNY", new String[][]{{"L1", "1000.00"}});
        RiskAssessmentResponse assessed = riskService.assess("AC");
        Long jobId = assessed.jobId();
        assertThat(jobId).isNotNull();

        // 在执行前追加担保物使比例达标：撤销待发强平
        DepositResponse resp = riskService.deposit(new DepositRequestRequestBuilder()
                .requestId("R-CANCEL").accountNo("AC").amount("320.00").currency("CNY").build());
        assertThat(resp.jobCancelled()).isTrue();
        assertThat(riskRepo.findJobById(jobId).status()).isEqualTo(JobStatus.CANCELLED);
        // 撤销后执行不再平仓
        LiquidationResponse exec = riskService.executeJob(jobId);
        assertThat(exec.status()).isEqualTo(JobStatus.CANCELLED.name());
        assertThat(exec.items()).isEmpty();
        assertThat(accountData.findOutstandingLiabilities("AC")).hasSize(1);
    }

    @Test
    void lateDepositAfterExecutionAppliedToRemainingDebt() {
        // 1080/1000，强平需 r >= (1400-1080)/0.4=800 → 平后资产280 负债200（140%）
        setupAccount("AE", "1080.00", "CNY", new String[][]{{"L1", "1000.00"}});
        Long jobId = riskService.assess("AE").jobId();
        riskService.executeJob(jobId);
        assertThat(riskRepo.findJobById(jobId).status()).isEqualTo(JobStatus.EXECUTED);

        // 迟到的 100 元保证金：计入余下负债重算，不撤销已执行终态
        DepositResponse late = riskService.deposit(new DepositRequestRequestBuilder()
                .requestId("R-LATE").accountNo("AE").amount("100.00").currency("CNY").build());
        assertThat(late.result()).isEqualTo(DepositResult.APPLIED_TO_REMAINING);
        assertThat(late.jobCancelled()).isFalse();
        // 资产 280+100=380，余下负债 200 → 190%
        assertThat(latestSnapshot("AE").totalAssets()).isEqualByComparingTo("380.00");
        assertThat(latestSnapshot("AE").totalLiabilities()).isEqualByComparingTo("200.00");
        // requestId 幂等仍生效
        assertThat(riskService.deposit(new DepositRequestRequestBuilder()
                .requestId("R-LATE").accountNo("AE").amount("100.00").currency("CNY").build())
                .replayed()).isTrue();
        assertThat(latestSnapshot("AE").totalAssets()).isEqualByComparingTo("380.00");
    }

    @Test
    void depositRejectsSubCentAmount() {
        setupAccount("AX", "1250.00", "CNY", new String[][]{{"L1", "1000.00"}});
        assertThatThrownBy(() -> riskService.deposit(new DepositRequestRequestBuilder()
                .requestId("R-FRAC").accountNo("AX").amount("10.005").currency("CNY").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(riskRepo.findDeposit("R-FRAC")).isNull();
    }

    @Test
    void assessmentAlwaysUsesLatestConfirmedSnapshot() {
        setupAccount("AU", "1500.00", "CNY", new String[][]{{"L1", "1000.00"}});
        assertThat(riskService.assess("AU").action()).isEqualTo("NONE");
        // 行情下跌：新快照 1250/1000
        setupService.addSnapshot("AU", new SnapshotRequest(
                new BigDecimal("1250.00"), new BigDecimal("1000.00"), "行情下跌"));
        assertThat(riskService.assess("AU").action()).isEqualTo("MARGIN_NOTICE");
    }

    private static BigDecimal RiskRules_ratio(SnapshotPo snap) {
        return com.example.starter.margin.support.RiskRules
                .ratio(snap.totalAssets(), snap.totalLiabilities());
    }

    /** 测试用请求构造器。 */
    private static final class DepositRequestRequestBuilder {
        private String requestId;
        private String accountNo;
        private String amount;
        private String currency;

        DepositRequestRequestBuilder requestId(String v) { this.requestId = v; return this; }
        DepositRequestRequestBuilder accountNo(String v) { this.accountNo = v; return this; }
        DepositRequestRequestBuilder amount(String v) { this.amount = v; return this; }
        DepositRequestRequestBuilder currency(String v) { this.currency = v; return this; }

        DepositRequest build() {
            return new DepositRequest(requestId, accountNo, new BigDecimal(amount), currency);
        }
    }
}
