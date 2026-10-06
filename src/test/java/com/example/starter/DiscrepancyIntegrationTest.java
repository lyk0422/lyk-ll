package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.common.ApiException;
import com.example.starter.credit.CreditService;
import com.example.starter.credit.CreditService.CreateCreditAccountRequest;
import com.example.starter.discrepancy.DiscrepancyService;
import com.example.starter.discrepancy.DiscrepancyService.DiscrepancyView;
import com.example.starter.discrepancy.DiscrepancyService.ResolveDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.ResolveDiscrepancyResponse;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyResponse;
import com.example.starter.invoice.InvoiceService;
import com.example.starter.invoice.InvoiceService.InvoiceView;
import com.example.starter.invoice.InvoiceService.IssueBlueInvoiceRequest;
import com.example.starter.invoice.InvoiceService.IssueResponse;
import com.example.starter.invoice.InvoiceService.LineRequest;
import com.example.starter.redflush.RedFlushService;
import com.example.starter.redflush.RedFlushService.ConfirmRequest;
import com.example.starter.redflush.RedFlushService.ConfirmResponse;
import com.example.starter.redflush.RedFlushService.CreateRedFlushRequest;
import com.example.starter.redflush.RedFlushService.CreateRedFlushResponse;
import com.example.starter.redflush.RedFlushService.RedFlushRequestView;
import com.example.starter.redflush.RedFlushService.RedLineRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 购方对账差异单集成测试（H2 MySQL 兼容模式，真实库表/事务/行锁，可控时钟）。
 * 覆盖：行级争议隔离、72 小时恰满升级、差异确认后走红字信息表流程、
 * 额度不提前恢复、红冲竞态与冲突记录、提交与处置幂等。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_discrepancy;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class DiscrepancyIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private DiscrepancyService discrepancyService;
    @Autowired
    private CreditService creditService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM discrepancy_event");
        jdbc.update("DELETE FROM discrepancy_conflict");
        jdbc.update("DELETE FROM discrepancy");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM red_flush_confirmation");
        jdbc.update("DELETE FROM red_flush_line");
        jdbc.update("DELETE FROM red_flush_request");
        jdbc.update("DELETE FROM invoice_line");
        jdbc.update("DELETE FROM invoice");
        jdbc.update("DELETE FROM credit_ledger");
        jdbc.update("DELETE FROM credit_account");
        clock.setInstant(T0);
    }

    @AfterAll
    static void shutdownDatabase(@Autowired JdbcTemplate jdbc) {
        // DB_CLOSE_DELAY=-1 下显式释放测试库，避免上下文结束后悬挂
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void 主流程_确认差异后走红字信息表流程且红票生效才恢复额度() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        // 购方登记差异：行1 票面 200，主张 120，需冲减 80；单方提交不改蓝票金额与额度
        SubmitDiscrepancyResponse submitted = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("120.00"), "EV-2026-0001"));
        assertThat(submitted.result()).isEqualTo("SUBMITTED");
        assertThat(submitted.status()).isEqualTo("OPEN");
        assertThat(submitted.diffAmount()).isEqualByComparingTo("80.00");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).totalAmount())
                .isEqualByComparingTo("300.00");

        // 销方确认差异：按原红字信息表流程创建 PENDING 申请，差异单本身不改额度
        ResolveDiscrepancyResponse resolved = discrepancyService.resolve(submitted.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-1", "CONFIRM"));
        assertThat(resolved.status()).isEqualTo("CONFIRMED");
        assertThat(resolved.redRequestNo()).isNotNull();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        RedFlushRequestView autoRequest = redFlushService.getRequest(resolved.redRequestNo());
        assertThat(autoRequest.status()).isEqualTo("PENDING");
        assertThat(autoRequest.totalAmount()).isEqualByComparingTo("80.00");
        assertThat(autoRequest.lines()).hasSize(1);
        assertThat(autoRequest.lines().get(0).lineNo()).isEqualTo(1);
        assertThat(autoRequest.lines().get(0).amount()).isEqualByComparingTo("80.00");

        // 单方确认不生效：无红票、额度不变
        ConfirmResponse sellerOnly = redFlushService.confirm(resolved.redRequestNo(),
                new ConfirmRequest("SELLER", "AGREE"));
        assertThat(sellerOnly.status()).isEqualTo("PENDING");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        // 红票确认生效才恢复授信额度
        ConfirmResponse issued2 = redFlushService.confirm(resolved.redRequestNo(),
                new ConfirmRequest("BUYER", "AGREE"));
        assertThat(issued2.status()).isEqualTo("ISSUED");
        assertThat(issued2.redInvoiceNo()).isNotNull();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("780.00");

        InvoiceView view = invoiceService.getInvoice(issued.invoiceNo());
        assertThat(view.status()).isEqualTo("PARTIAL_RED");
        assertThat(view.lines().get(0).redFlushedAmount()).isEqualByComparingTo("80.00");
        assertThat(view.redChain()).hasSize(1);

        // 差异记录与审计轨迹完整，重复查询稳定
        DiscrepancyView discrepancy = discrepancyService.getDiscrepancy(submitted.discrepancyNo());
        assertThat(discrepancy.status()).isEqualTo("CONFIRMED");
        assertThat(discrepancy.redRequestNo()).isEqualTo(resolved.redRequestNo());
        assertThat(discrepancy.respondedAt()).isNotNull();
        assertThat(discrepancy.events()).extracting("event")
                .containsExactly("SUBMITTED", "CONFIRMED");
        assertThat(discrepancyService.getDiscrepancy(submitted.discrepancyNo())).isEqualTo(discrepancy);
    }

    @Test
    void 驳回_解除该行暂停但保留差异记录() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());
        SubmitDiscrepancyResponse submitted = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("100.00"), "EV-0001"));

        ResolveDiscrepancyResponse rejected = discrepancyService.resolve(submitted.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-1", "REJECT"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.redRequestNo()).isNull();

        // 差异记录保留，审计轨迹完整
        DiscrepancyView view = discrepancyService.getDiscrepancy(submitted.discrepancyNo());
        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.claimedAmount()).isEqualByComparingTo("100.00");
        assertThat(view.evidenceRef()).isEqualTo("EV-0001");
        assertThat(view.events()).extracting("event").containsExactly("SUBMITTED", "REJECTED");

        // 驳回解除该行暂停：该行新增红冲按原规则处理
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00")))));
        assertThat(created.result()).isEqualTo("CREATED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");
    }

    @Test
    void 行级隔离_未结差异只暂停对应行_整票与无争议行不锁死() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());
        discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-0001"));

        // 争议行新增红冲暂停
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");
        // 全额红冲覆盖争议行，同样暂停
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "FULL", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");

        // 无争议行仍按原规则处理
        CreateRedFlushResponse line2 = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-3", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(2, new BigDecimal("40.00")))));
        assertThat(line2.result()).isEqualTo("CREATED");
        ConfirmResponse confirmed = confirmBoth(line2.requestNo());
        assertThat(confirmed.status()).isEqualTo("ISSUED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("740.00");

        // 同一行重复登记未结差异被拒绝
        assertThatThrownBy(() -> discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-2", issued.invoiceNo(), 1, new BigDecimal("160.00"), "EV-0002")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DISCREPANCY_EXISTS");
    }

    @Test
    void 七十二小时边界_恰满即升级人工且不自动视为确认() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());
        SubmitDiscrepancyResponse d1 = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-0001"));
        SubmitDiscrepancyResponse d2 = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-2", issued.invoiceNo(), 2, new BigDecimal("40.00"), "EV-0002"));

        // 满 72 小时前 1 秒：仍为 OPEN，销方仍可处置
        clock.setInstant(T0.plusSeconds(72 * 3600 - 1));
        assertThat(discrepancyService.getDiscrepancy(d1.discrepancyNo()).status()).isEqualTo("OPEN");
        ResolveDiscrepancyResponse resolved = discrepancyService.resolve(d1.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-1", "CONFIRM"));
        assertThat(resolved.status()).isEqualTo("CONFIRMED");

        // 恰满 72 小时：升级人工，不自动视为已确认
        clock.setInstant(T0.plusSeconds(72 * 3600));
        DiscrepancyView escalated = discrepancyService.getDiscrepancy(d2.discrepancyNo());
        assertThat(escalated.status()).isEqualTo("ESCALATED");
        assertThat(escalated.redRequestNo()).isNull();
        assertThat(escalated.events()).extracting("event")
                .containsExactly("SUBMITTED", "ESCALATED");

        // 升级后销方确认/驳回均被拒绝
        assertThatThrownBy(() -> discrepancyService.resolve(d2.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-2", "CONFIRM")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DISCREPANCY_ESCALATED");
        assertThatThrownBy(() -> discrepancyService.resolve(d2.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-3", "REJECT")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DISCREPANCY_ESCALATED");

        // 已升级差异仍属未结：该行新增红冲继续暂停；已确认差异的行不再暂停
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(2, new BigDecimal("10.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");
        CreateRedFlushResponse line1 = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("100.00")))));
        assertThat(line1.result()).isEqualTo("CREATED");
        // 升级不产生红票、不恢复额度
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");
    }

    @Test
    void 竞态_差异登记与同一行红冲受理只有一方先占该行且不重复恢复额度() throws Exception {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<SubmitDiscrepancyResponse> submitResult = new AtomicReference<>();
        AtomicReference<CreateRedFlushResponse> flushResult = new AtomicReference<>();
        AtomicReference<ApiException> flushError = new AtomicReference<>();
        try {
            Future<?> submitFuture = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    submitResult.set(discrepancyService.submit(new SubmitDiscrepancyRequest(
                            "DIF-RACE", issued.invoiceNo(), 1, new BigDecimal("0.00"), "EV-RACE")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> flushFuture = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    flushResult.set(redFlushService.createRequest(new CreateRedFlushRequest(
                            "RF-RACE", issued.invoiceNo(), "PARTIAL",
                            List.of(new RedLineRequest(1, new BigDecimal("200.00"))))));
                } catch (ApiException e) {
                    flushError.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.countDown();
            submitFuture.get(30, TimeUnit.SECONDS);
            flushFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        boolean discrepancyWon = submitResult.get() != null
                && "SUBMITTED".equals(submitResult.get().result());
        boolean flushWon = flushResult.get() != null && "CREATED".equals(flushResult.get().result());
        // 只能有一个先占该行
        assertThat(discrepancyWon).isNotEqualTo(flushWon);
        if (discrepancyWon) {
            // 先登记差异：红冲挂起（拒绝），差异确认后走红字信息表流程
            assertThat(flushError.get()).isNotNull();
            assertThat(flushError.get().getCode()).isEqualTo("LINE_DISPUTED");
            ResolveDiscrepancyResponse resolved = discrepancyService.resolve(
                    submitResult.get().discrepancyNo(), new ResolveDiscrepancyRequest("RS-RACE", "CONFIRM"));
            confirmBoth(resolved.redRequestNo());
        } else {
            // 先受理红冲：差异拒绝并保留冲突记录
            assertThat(submitResult.get()).isNotNull();
            assertThat(submitResult.get().result()).isEqualTo("CONFLICT_REJECTED");
            confirmBoth(flushResult.get().requestNo());
            Integer conflicts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM discrepancy_conflict", Integer.class);
            assertThat(conflicts).isEqualTo(1);
        }
        // 不得重复恢复额度：恰好一张红票、一笔 RED_CONFIRM 台账、额度只恢复一次
        Integer redCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'RED'", Integer.class);
        assertThat(redCount).isEqualTo(1);
        Integer restoreCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_ledger WHERE reason = 'RED_CONFIRM'", Integer.class);
        assertThat(restoreCount).isEqualTo(1);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void 冲突_红冲在途或已完成则差异拒绝并保留冲突记录() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());

        // 红冲在途（PENDING）：差异拒绝并保留冲突记录
        CreateRedFlushResponse pending = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("80.00")))));
        SubmitDiscrepancyResponse inProgress = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("100.00"), "EV-0001"));
        assertThat(inProgress.result()).isEqualTo("CONFLICT_REJECTED");
        assertThat(inProgress.discrepancyNo()).isNull();
        Integer discrepancyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy", Integer.class);
        assertThat(discrepancyCount).isEqualTo(0);
        String reason = jdbc.queryForObject(
                "SELECT reason FROM discrepancy_conflict WHERE blue_invoice_no = ? AND line_no = 1",
                String.class, issued.invoiceNo());
        assertThat(reason).isEqualTo("RED_FLUSH_IN_PROGRESS");

        // 同键重放：返回首次结果，不重复写冲突记录
        SubmitDiscrepancyResponse replay = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("100.00"), "EV-0001"));
        assertThat(replay.result()).isEqualTo("CONFLICT_REJECTED");
        Integer conflictCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_conflict", Integer.class);
        assertThat(conflictCount).isEqualTo(1);

        // 红冲完成后：差异仍拒绝，冲突原因记为已完成
        confirmBoth(pending.requestNo());
        SubmitDiscrepancyResponse completed = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-2", issued.invoiceNo(), 1, new BigDecimal("100.00"), "EV-0002"));
        assertThat(completed.result()).isEqualTo("CONFLICT_REJECTED");
        reason = jdbc.queryForObject(
                "SELECT reason FROM discrepancy_conflict WHERE evidence_ref = 'EV-0002'",
                String.class);
        assertThat(reason).isEqualTo("RED_FLUSH_COMPLETED");

        // 无红冲冲突的行可正常登记差异
        SubmitDiscrepancyResponse line2 = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-3", issued.invoiceNo(), 2, new BigDecimal("50.00"), "EV-0003"));
        assertThat(line2.result()).isEqualTo("SUBMITTED");
    }

    @Test
    void 幂等_提交与处置同键同参重放_同键异参409() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        SubmitDiscrepancyRequest submit = new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("120.00"), "EV-0001");
        SubmitDiscrepancyResponse first = discrepancyService.submit(submit);
        SubmitDiscrepancyResponse replay = discrepancyService.submit(submit);
        assertThat(replay.discrepancyNo()).isEqualTo(first.discrepancyNo());
        Integer discrepancyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy", Integer.class);
        assertThat(discrepancyCount).isEqualTo(1);

        // 同键异参：409，状态不变
        assertThatThrownBy(() -> discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("121.00"), "EV-0001")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        // 处置幂等：同键同参重放首次结果，不产生第二张红字信息表
        ResolveDiscrepancyResponse resolved = discrepancyService.resolve(first.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-1", "CONFIRM"));
        ResolveDiscrepancyResponse resolvedReplay = discrepancyService.resolve(first.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-1", "CONFIRM"));
        assertThat(resolvedReplay).isEqualTo(resolved);
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_request", Integer.class);
        assertThat(requestCount).isEqualTo(1);

        // 处置同键异参：409
        assertThatThrownBy(() -> discrepancyService.resolve(first.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-1", "REJECT")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        // 已处置的差异单用新键重复处置：409
        assertThatThrownBy(() -> discrepancyService.resolve(first.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-2", "CONFIRM")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DISCREPANCY_ALREADY_RESOLVED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");
    }

    @Test
    void 全额红冲_购方未入账且无未结差异沿用原路径() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        // 差异被驳回后已结：不构成未结差异，全额红冲不新增挂起门槛
        SubmitDiscrepancyResponse submitted = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-0001"));
        discrepancyService.resolve(submitted.discrepancyNo(),
                new ResolveDiscrepancyRequest("RS-1", "REJECT"));

        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "FULL", null));
        assertThat(created.result()).isEqualTo("CREATED");
        ConfirmResponse confirmed = confirmBoth(created.requestNo());
        assertThat(confirmed.status()).isEqualTo("ISSUED");
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).status()).isEqualTo("RED_FLUSHED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");
    }

    private IssueResponse issue(String sellerId, String requestId, List<LineRequest> lines) {
        return invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                requestId, sellerId, "BUYER-1", false, lines));
    }

    private ConfirmResponse confirmBoth(String requestNo) {
        redFlushService.confirm(requestNo, new ConfirmRequest("SELLER", "AGREE"));
        return redFlushService.confirm(requestNo, new ConfirmRequest("BUYER", "AGREE"));
    }

    /**
     * 可控时钟：测试通过 setInstant 推进时间，验证 72 小时恰满升级边界。
     */
    static class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * 测试时钟配置：以可控时钟覆盖应用默认 UTC 时钟。
     */
    @TestConfiguration
    static class ClockTestConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }
}
