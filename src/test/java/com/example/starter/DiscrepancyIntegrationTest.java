package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import com.example.starter.discrepancy.DiscrepancyService.RespondDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.RespondDiscrepancyResponse;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyResponse;
import com.example.starter.invoice.InvoiceService;
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

/**
 * 购方对账差异单集成测试（H2 MySQL 兼容模式，真实库表/事务/行锁，可控时钟）。
 * 覆盖：行级争议隔离、驳回解除暂停、双方确认后按原红字信息表流程处理、
 * 72 小时（UTC）恰满升级边界、红冲竞态先占行、额度不提前恢复与幂等。
 */
@SpringBootTest
class DiscrepancyIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0, ZoneOffset.UTC);
        }
    }

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
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM discrepancy_audit");
        jdbc.update("DELETE FROM discrepancy_sheet");
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
    void 行级争议隔离_差异行暂停红冲_无争议行按原规则处理() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());

        SubmitDiscrepancyResponse submitted = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EVID-001"));
        assertThat(submitted.result()).isEqualTo("SUBMITTED");
        assertThat(submitted.status()).isEqualTo("PENDING");
        assertThat(submitted.flushAmount()).isEqualByComparingTo("50.00");
        // 单方提交不改蓝票金额、不改额度
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).totalAmount()).isEqualByComparingTo("300.00");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");
        assertThat(creditService.listLedger("S1")).hasSize(1);

        // 同一行已存在未结差异：拒绝重复登记
        assertThatThrownBy(() -> discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-2", issued.invoiceNo(), 1, new BigDecimal("160.00"), "EVID-002")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DISCREPANCY_EXISTS");

        // 差异行新增部分红冲被暂停
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");
        // 存在未结差异时全额红冲同样暂停
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "FULL", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");

        // 无争议行仍按原规则处理
        CreateRedFlushResponse line2 = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-3", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(2, new BigDecimal("60.00")))));
        assertThat(line2.result()).isEqualTo("CREATED");
        ConfirmResponse confirmed = confirmBoth(line2.requestNo());
        assertThat(confirmed.status()).isEqualTo("ISSUED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("760.00");
        // 差异单仍未结，蓝票未被整票锁死
        assertThat(discrepancyService.getDiscrepancy(submitted.discrepancyNo()).status()).isEqualTo("PENDING");
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).status()).isEqualTo("PARTIAL_RED");
    }

    @Test
    void 驳回_解除该行暂停且保留差异记录_额度不变() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());
        SubmitDiscrepancyResponse submitted = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EVID-001"));

        RespondDiscrepancyResponse rejected = discrepancyService.respond(submitted.discrepancyNo(),
                new RespondDiscrepancyRequest("RESP-1", "REJECT"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.redRequestNo()).isNull();
        // 驳回不产生红字信息表、不改额度
        Integer requestCount = jdbc.queryForObject("SELECT COUNT(*) FROM red_flush_request", Integer.class);
        assertThat(requestCount).isZero();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");

        // 驳回解除该行暂停：同一行可正常红冲
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00")))));
        assertThat(created.result()).isEqualTo("CREATED");

        // 差异记录保留，审计轨迹完整
        DiscrepancyView view = discrepancyService.getDiscrepancy(submitted.discrepancyNo());
        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.respondedAt()).isNotNull();
        assertThat(view.audits()).extracting(DiscrepancyService.AuditView::action)
                .containsExactly("SUBMIT", "REJECT");
    }

    @Test
    void 双方确认差异_按原红字信息表流程处理_只有红票确认才恢复额度() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());
        SubmitDiscrepancyResponse submitted = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EVID-001"));

        RespondDiscrepancyResponse confirmed = discrepancyService.respond(submitted.discrepancyNo(),
                new RespondDiscrepancyRequest("RESP-1", "CONFIRM"));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.redRequestNo()).isNotNull();
        // 差异单本身不改额度：确认后可用额度与台账不变
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");
        assertThat(creditService.listLedger("S1")).hasSize(1);

        // 已按原流程创建红字信息表：PENDING，需冲减金额 50
        RedFlushRequestView requestView = redFlushService.getRequest(confirmed.redRequestNo());
        assertThat(requestView.status()).isEqualTo("PENDING");
        assertThat(requestView.flushType()).isEqualTo("PARTIAL");
        assertThat(requestView.totalAmount()).isEqualByComparingTo("50.00");
        assertThat(requestView.lines()).hasSize(1);
        assertThat(requestView.lines().get(0).lineNo()).isEqualTo(1);
        assertThat(requestView.lines().get(0).amount()).isEqualByComparingTo("50.00");

        // 单方确认不生效，额度仍不恢复
        ConfirmResponse sellerOnly = redFlushService.confirm(confirmed.redRequestNo(),
                new ConfirmRequest("SELLER", "AGREE"));
        assertThat(sellerOnly.status()).isEqualTo("PENDING");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        // 红票确认生效才恢复额度
        ConfirmResponse both = redFlushService.confirm(confirmed.redRequestNo(),
                new ConfirmRequest("BUYER", "AGREE"));
        assertThat(both.status()).isEqualTo("ISSUED");
        assertThat(both.redInvoiceNo()).isNotNull();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("750.00");
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).redFlushedAmount())
                .isEqualByComparingTo("50.00");

        DiscrepancyView view = discrepancyService.getDiscrepancy(submitted.discrepancyNo());
        assertThat(view.status()).isEqualTo("CONFIRMED");
        assertThat(view.redRequestNo()).isEqualTo(confirmed.redRequestNo());
        assertThat(view.audits()).extracting(DiscrepancyService.AuditView::action)
                .containsExactly("SUBMIT", "CONFIRM");
    }

    @Test
    void 七十二小时边界_恰满即升级人工_不自动视为已确认() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());
        SubmitDiscrepancyResponse d1 = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EVID-001"));
        SubmitDiscrepancyResponse d2 = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-2", issued.invoiceNo(), 2, new BigDecimal("80.00"), "EVID-002"));

        // 满 72 小时前 1 秒：仍未升级，销方答复有效
        clock.setInstant(T0.plus(Duration.ofHours(72)).minusSeconds(1));
        assertThat(discrepancyService.getDiscrepancy(d1.discrepancyNo()).status()).isEqualTo("PENDING");
        RespondDiscrepancyResponse rejected = discrepancyService.respond(d1.discrepancyNo(),
                new RespondDiscrepancyRequest("RESP-1", "REJECT"));
        assertThat(rejected.status()).isEqualTo("REJECTED");

        // 恰满 72 小时（UTC）：升级人工处理，不自动视为已确认
        clock.setInstant(T0.plus(Duration.ofHours(72)));
        DiscrepancyView escalated = discrepancyService.getDiscrepancy(d2.discrepancyNo());
        assertThat(escalated.status()).isEqualTo("ESCALATED");
        assertThat(escalated.audits()).extracting(DiscrepancyService.AuditView::action)
                .containsExactly("SUBMIT", "ESCALATE");

        // 升级后销方答复被拒，状态保持 ESCALATED，不产生红字信息表、不改额度
        assertThatThrownBy(() -> discrepancyService.respond(d2.discrepancyNo(),
                new RespondDiscrepancyRequest("RESP-2", "CONFIRM")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DISCREPANCY_ESCALATED");
        assertThat(discrepancyService.getDiscrepancy(d2.discrepancyNo()).status()).isEqualTo("ESCALATED");
        Integer requestCount = jdbc.queryForObject("SELECT COUNT(*) FROM red_flush_request", Integer.class);
        assertThat(requestCount).isZero();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        // 升级人工的差异仍未结：对应行新增红冲继续暂停
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(2, new BigDecimal("20.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");
    }

    @Test
    void 红冲先完成_差异单拒绝并保留冲突记录_不重复恢复额度() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "FULL", null));
        confirmBoth(created.requestNo());
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).status()).isEqualTo("RED_FLUSHED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");

        // 红冲先完成：差异单拒绝并保留冲突记录
        SubmitDiscrepancyResponse conflict = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EVID-001"));
        assertThat(conflict.result()).isEqualTo("CONFLICT_REJECTED");
        assertThat(conflict.discrepancyNo()).isNotNull();
        DiscrepancyView view = discrepancyService.getDiscrepancy(conflict.discrepancyNo());
        assertThat(view.status()).isEqualTo("CONFLICT_REJECTED");
        assertThat(view.audits()).extracting(DiscrepancyService.AuditView::action)
                .containsExactly("CONFLICT_REJECT");

        // 不重复恢复额度：无新红票、无新台账
        Integer redCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'RED'", Integer.class);
        assertThat(redCount).isEqualTo(1);
        assertThat(creditService.listLedger("S1")).hasSize(2);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");

        // 冲突结果同样幂等：同键同参重放首次结果
        SubmitDiscrepancyResponse replay = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EVID-001"));
        assertThat(replay.discrepancyNo()).isEqualTo(conflict.discrepancyNo());
        Integer discrepancyCount = jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_sheet", Integer.class);
        assertThat(discrepancyCount).isEqualTo(1);
    }

    @Test
    void 幂等_提交与处置同键同参重放_同键异参409() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        SubmitDiscrepancyRequest submit = new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EVID-001");
        SubmitDiscrepancyResponse first = discrepancyService.submit(submit);
        SubmitDiscrepancyResponse replay = discrepancyService.submit(submit);
        assertThat(replay.discrepancyNo()).isEqualTo(first.discrepancyNo());
        Integer discrepancyCount = jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_sheet", Integer.class);
        assertThat(discrepancyCount).isEqualTo(1);

        // 同键异参：409 且不产生新记录
        assertThatThrownBy(() -> discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DSP-1", issued.invoiceNo(), 1, new BigDecimal("160.00"), "EVID-001")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        discrepancyCount = jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_sheet", Integer.class);
        assertThat(discrepancyCount).isEqualTo(1);

        // 处置幂等：同键同参重放首次结果
        RespondDiscrepancyRequest respond = new RespondDiscrepancyRequest("RESP-1", "REJECT");
        RespondDiscrepancyResponse respondFirst = discrepancyService.respond(first.discrepancyNo(), respond);
        RespondDiscrepancyResponse respondReplay = discrepancyService.respond(first.discrepancyNo(), respond);
        assertThat(respondReplay.status()).isEqualTo(respondFirst.status()).isEqualTo("REJECTED");
        Integer auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_audit", Integer.class);
        assertThat(auditCount).isEqualTo(2);

        // 处置同键异参：409，状态不被改写
        assertThatThrownBy(() -> discrepancyService.respond(first.discrepancyNo(),
                new RespondDiscrepancyRequest("RESP-1", "CONFIRM")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(discrepancyService.getDiscrepancy(first.discrepancyNo()).status()).isEqualTo("REJECTED");
    }

    @Test
    void 并发_差异登记与同行红冲受理只能一个先占该行() throws Exception {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<SubmitDiscrepancyResponse> submitResult = new AtomicReference<>();
        AtomicReference<CreateRedFlushResponse> flushResult = new AtomicReference<>();
        AtomicReference<ApiException> submitError = new AtomicReference<>();
        AtomicReference<ApiException> flushError = new AtomicReference<>();
        try {
            Future<?> submitter = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    submitResult.set(discrepancyService.submit(new SubmitDiscrepancyRequest(
                            "DSP-C", issued.invoiceNo(), 1, new BigDecimal("100.00"), "EVID-C")));
                } catch (ApiException e) {
                    submitError.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> flusher = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    flushResult.set(redFlushService.createRequest(new CreateRedFlushRequest(
                            "RF-C", issued.invoiceNo(), "PARTIAL",
                            List.of(new RedLineRequest(1, new BigDecimal("200.00"))))));
                } catch (ApiException e) {
                    flushError.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.countDown();
            submitter.get(30, TimeUnit.SECONDS);
            flusher.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Integer discrepancyCount = jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_sheet", Integer.class);
        Integer requestCount = jdbc.queryForObject("SELECT COUNT(*) FROM red_flush_request", Integer.class);
        assertThat(discrepancyCount).isEqualTo(1);
        if (submitResult.get() != null && "SUBMITTED".equals(submitResult.get().result())) {
            // 差异先登记：红冲被暂停
            assertThat(flushResult.get()).isNull();
            assertThat(flushError.get()).isNotNull();
            assertThat(flushError.get().getCode()).isEqualTo("LINE_DISPUTED");
            assertThat(requestCount).isZero();
            assertThat(discrepancyService.getDiscrepancy(submitResult.get().discrepancyNo()).status())
                    .isEqualTo("PENDING");
        } else {
            // 红冲先占该行：差异单拒绝并保留冲突记录
            assertThat(submitResult.get()).isNotNull();
            assertThat(submitResult.get().result()).isEqualTo("CONFLICT_REJECTED");
            assertThat(flushResult.get()).isNotNull();
            assertThat(flushResult.get().result()).isEqualTo("CREATED");
            assertThat(requestCount).isEqualTo(1);
            assertThat(discrepancyService.getDiscrepancy(submitResult.get().discrepancyNo()).status())
                    .isEqualTo("CONFLICT_REJECTED");
        }
        // 无论谁先占行：红冲未确认，额度不变、台账仅开票一笔，不得重复恢复
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");
        assertThat(creditService.listLedger("S1")).hasSize(1);
    }

    @Test
    void 全额红冲_购方未入账且无差异_沿用原路径不一律挂起() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                "REQ-1", "S1", "B1", false,
                List.of(new LineRequest("货物A", new BigDecimal("200.00")))));
        invoiceService.deliver(issued.invoiceNo());
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "FULL", null));
        assertThat(created.result()).isEqualTo("CREATED");
        ConfirmResponse confirmed = confirmBoth(created.requestNo());
        assertThat(confirmed.status()).isEqualTo("ISSUED");
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
}
