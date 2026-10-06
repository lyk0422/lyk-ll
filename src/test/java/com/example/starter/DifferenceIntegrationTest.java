package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.example.starter.difference.DifferenceService;
import com.example.starter.difference.DifferenceService.AuditView;
import com.example.starter.difference.DifferenceService.DifferenceView;
import com.example.starter.difference.DifferenceService.RespondDifferenceRequest;
import com.example.starter.difference.DifferenceService.RespondDifferenceResponse;
import com.example.starter.difference.DifferenceService.SubmitDifferenceRequest;
import com.example.starter.difference.DifferenceService.SubmitDifferenceResponse;
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
import com.example.starter.redflush.RedFlushService.RedLineRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 购方对账差异单集成测试（H2 MySQL 兼容模式，独立内存库，真实库表/事务/行锁）。
 * 覆盖：行级争议隔离、72 小时升级边界、差异与红冲竞态、幂等重放与冲突拒绝、
 * 额度只在红票确认后恢复。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_diff;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class DifferenceIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private DifferenceService differenceService;
    @Autowired
    private CreditService creditService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM difference_audit");
        jdbc.update("DELETE FROM difference_order");
        jdbc.update("DELETE FROM red_flush_confirmation");
        jdbc.update("DELETE FROM red_flush_line");
        jdbc.update("DELETE FROM red_flush_request");
        jdbc.update("DELETE FROM invoice_line");
        jdbc.update("DELETE FROM invoice");
        jdbc.update("DELETE FROM credit_ledger");
        jdbc.update("DELETE FROM credit_account");
    }

    @AfterAll
    static void shutdownDatabase(@Autowired JdbcTemplate jdbc) {
        // DB_CLOSE_DELAY=-1 下显式释放测试库，避免上下文结束后悬挂
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void 主流程_差异确认后按原红字信息表流程冲减且额度只在红票确认后恢复() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("250.00")),
                        new LineRequest("货物B", new BigDecimal("150.00"))));
        invoiceService.deliver(issued.invoiceNo());

        SubmitDifferenceResponse submitted = differenceService.submit(new SubmitDifferenceRequest(
                "D-1", issued.invoiceNo(), 1, new BigDecimal("200.00"), "EV-RECV-001"));
        assertThat(submitted.result()).isEqualTo("CREATED");
        assertThat(submitted.status()).isEqualTo("PENDING");

        // 单方提交不直接改蓝票金额与授信额度
        InvoiceView invoice = invoiceService.getInvoice(issued.invoiceNo());
        assertThat(invoice.totalAmount()).isEqualByComparingTo("400.00");
        assertThat(invoice.redFlushedAmount()).isEqualByComparingTo("0.00");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("600.00");

        // 未结差异只暂停争议行：行1新增红冲挂起，无争议行2仍按原规则受理
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-BLOCKED", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");
        CreateRedFlushResponse line2Flush = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(2, new BigDecimal("100.00")))));
        assertThat(line2Flush.result()).isEqualTo("CREATED");

        // 销方确认差异：按原红字信息表流程创建需冲减金额（250-200=50）的申请
        RespondDifferenceResponse confirmed = differenceService.respond(submitted.differenceNo(),
                new RespondDifferenceRequest("R-1", "CONFIRM"));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.flushAmount()).isEqualByComparingTo("50.00");
        assertThat(confirmed.redRequestNo()).isNotNull();

        // 差异单本身不改额度：确认后、红票生效前额度不变
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("600.00");

        // 红字信息表仍须双方确认：单方确认不生效
        ConfirmResponse sellerOnly = redFlushService.confirm(confirmed.redRequestNo(),
                new ConfirmRequest("SELLER", "AGREE"));
        assertThat(sellerOnly.status()).isEqualTo("PENDING");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("600.00");

        // 双方确认后红票生效，额度同事务恢复 50
        ConfirmResponse both = redFlushService.confirm(confirmed.redRequestNo(),
                new ConfirmRequest("BUYER", "AGREE"));
        assertThat(both.status()).isEqualTo("ISSUED");
        assertThat(both.availableLimit()).isEqualByComparingTo("650.00");

        // 无争议行2的红冲申请走完原流程，再恢复 100
        ConfirmResponse line2Confirmed = confirmBoth(line2Flush.requestNo());
        assertThat(line2Confirmed.status()).isEqualTo("ISSUED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("750.00");

        InvoiceView after = invoiceService.getInvoice(issued.invoiceNo());
        assertThat(after.status()).isEqualTo("PARTIAL_RED");
        assertThat(after.redFlushedAmount()).isEqualByComparingTo("150.00");
        assertThat(after.lines().get(0).redFlushedAmount()).isEqualByComparingTo("50.00");
        assertThat(after.lines().get(1).redFlushedAmount()).isEqualByComparingTo("100.00");

        // 差异单状态与审计轨迹同事务提交
        DifferenceView view = differenceService.getDifference(submitted.differenceNo());
        assertThat(view.status()).isEqualTo("CONFIRMED");
        assertThat(view.redRequestNo()).isEqualTo(confirmed.redRequestNo());
        assertThat(view.audits()).extracting(AuditView::action)
                .containsExactly("SUBMIT", "CONFIRM");
    }

    @Test
    void 驳回_解除行暂停但保留差异记录且不再接受处置() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());
        SubmitDifferenceResponse submitted = differenceService.submit(new SubmitDifferenceRequest(
                "D-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-RECV-002"));

        // 驳回前：争议行红冲挂起
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");

        RespondDifferenceResponse rejected = differenceService.respond(submitted.differenceNo(),
                new RespondDifferenceRequest("R-1", "REJECT"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.redRequestNo()).isNull();

        // 差异记录保留，审计轨迹完整
        DifferenceView view = differenceService.getDifference(submitted.differenceNo());
        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.claimedAmount()).isEqualByComparingTo("150.00");
        assertThat(view.audits()).extracting(AuditView::action)
                .containsExactly("SUBMIT", "REJECT");

        // 驳回后解除该行暂停：新增红冲按原规则受理
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00")))));
        assertThat(created.result()).isEqualTo("CREATED");
        confirmBoth(created.requestNo());
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("750.00");

        // 已处置差异单不再接受处置
        assertThatThrownBy(() -> differenceService.respond(submitted.differenceNo(),
                new RespondDifferenceRequest("R-2", "CONFIRM")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DIFF_ALREADY_RESPONDED");
    }

    @Test
    void 全额红冲_有未结差异挂起_购方未入账且无差异沿用首题路径() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse disputed = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(disputed.invoiceNo());
        differenceService.submit(new SubmitDifferenceRequest(
                "D-1", disputed.invoiceNo(), 1, new BigDecimal("150.00"), "EV-RECV-003"));

        // 存在未结差异：全额红冲挂起
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", disputed.invoiceNo(), "FULL", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");

        // 购方未入账且没有差异：全额红冲沿用首题路径，不新增一律挂起的门槛
        IssueResponse clean = issue("REQ-2",
                List.of(new LineRequest("货物C", new BigDecimal("300.00"))));
        invoiceService.deliver(clean.invoiceNo());
        CreateRedFlushResponse full = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", clean.invoiceNo(), "FULL", null));
        assertThat(full.result()).isEqualTo("CREATED");
        ConfirmResponse confirmed = confirmBoth(full.requestNo());
        assertThat(confirmed.status()).isEqualTo("ISSUED");
        assertThat(invoiceService.getInvoice(clean.invoiceNo()).status()).isEqualTo("RED_FLUSHED");
        // 占用 200 + 300，恢复 300
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");
    }

    @Test
    void 七十二小时边界_恰满即升级人工且不自动视为已确认() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());
        SubmitDifferenceResponse submitted = differenceService.submit(new SubmitDifferenceRequest(
                "D-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-RECV-004"));
        Instant submittedAt = differenceService.getDifference(submitted.differenceNo()).submittedAt();

        // 未满 72 小时（UTC）：不升级
        assertThat(differenceService.escalateOverdue(
                submittedAt.plus(72, ChronoUnit.HOURS).minusSeconds(1))).isZero();
        assertThat(differenceService.getDifference(submitted.differenceNo()).status())
                .isEqualTo("PENDING");

        // 恰满 72 小时：升级人工处理
        assertThat(differenceService.escalateOverdue(
                submittedAt.plus(72, ChronoUnit.HOURS))).isEqualTo(1);
        DifferenceView escalated = differenceService.getDifference(submitted.differenceNo());
        assertThat(escalated.status()).isEqualTo("ESCALATED");
        assertThat(escalated.audits()).extracting(AuditView::action)
                .containsExactly("SUBMIT", "ESCALATE");

        // 不自动视为已确认：无红冲申请、无红票、额度不变
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_request", Integer.class);
        assertThat(requestCount).isZero();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");

        // 升级后不再接受销方处置
        assertThatThrownBy(() -> differenceService.respond(submitted.differenceNo(),
                new RespondDifferenceRequest("R-1", "CONFIRM")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DIFF_ESCALATED");
    }

    @Test
    void 竞态_差异登记与同行红冲受理只有一个先占该行() throws Exception {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Object> diffOutcome = new AtomicReference<>();
        AtomicReference<Object> flushOutcome = new AtomicReference<>();
        try {
            Future<?> diffFuture = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    diffOutcome.set(differenceService.submit(new SubmitDifferenceRequest(
                            "D-RACE", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-RACE")));
                } catch (ApiException e) {
                    diffOutcome.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> flushFuture = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    flushOutcome.set(redFlushService.createRequest(new CreateRedFlushRequest(
                            "RF-RACE", issued.invoiceNo(), "PARTIAL",
                            List.of(new RedLineRequest(1, new BigDecimal("50.00"))))));
                } catch (ApiException e) {
                    flushOutcome.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.countDown();
            diffFuture.get(30, TimeUnit.SECONDS);
            flushFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Object diff = diffOutcome.get();
        Object flush = flushOutcome.get();
        assertThat(diff).isInstanceOf(SubmitDifferenceResponse.class);
        SubmitDifferenceResponse diffResponse = (SubmitDifferenceResponse) diff;
        // 先登记则红冲挂起；先受理红冲则差异单拒绝并保留冲突记录
        boolean diffFirst = "CREATED".equals(diffResponse.result())
                && flush instanceof ApiException flushError
                && "LINE_DISPUTED".equals(flushError.getCode());
        boolean flushFirst = "CONFLICT_REJECTED".equals(diffResponse.result())
                && flush instanceof CreateRedFlushResponse flushResponse
                && "CREATED".equals(flushResponse.result());
        assertThat(diffFirst || flushFirst)
                .as("差异登记与红冲受理必须恰好一个先占该行，实际：diff=%s, flush=%s", diff, flush)
                .isTrue();

        // 差异单无论胜负均落库；冲突拒绝保留冲突记录
        DifferenceView view = differenceService.getDifference(diffResponse.differenceNo());
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_request", Integer.class);
        if (diffFirst) {
            assertThat(view.status()).isEqualTo("PENDING");
            assertThat(requestCount).isZero();
        } else {
            assertThat(view.status()).isEqualTo("CONFLICT_REJECTED");
            assertThat(view.audits()).extracting(AuditView::action)
                    .containsExactly("SUBMIT", "CONFLICT_REJECT");
            assertThat(requestCount).isEqualTo(1);
        }
        // 不得重复恢复额度：台账仅有开票占用一笔，可用额度不变
        assertThat(creditService.listLedger("S1")).hasSize(1);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");
    }

    @Test
    void 竞态冲突_已完成或在途红冲占行则差异单拒绝并保留冲突记录() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        // 场景一：该行已完成红冲
        IssueResponse flushed = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(flushed.invoiceNo());
        CreateRedFlushResponse done = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", flushed.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00")))));
        confirmBoth(done.requestNo());
        assertThat(invoiceService.getInvoice(flushed.invoiceNo()).lines().get(0)
                .redFlushedAmount()).isEqualByComparingTo("50.00");

        SubmitDifferenceResponse afterFlush = differenceService.submit(new SubmitDifferenceRequest(
                "D-1", flushed.invoiceNo(), 1, new BigDecimal("100.00"), "EV-LATE-001"));
        assertThat(afterFlush.result()).isEqualTo("CONFLICT_REJECTED");
        DifferenceView afterFlushView = differenceService.getDifference(afterFlush.differenceNo());
        assertThat(afterFlushView.status()).isEqualTo("CONFLICT_REJECTED");
        assertThat(afterFlushView.audits()).extracting(AuditView::action)
                .containsExactly("SUBMIT", "CONFLICT_REJECT");

        // 场景二：该行存在在途（未确认）红冲申请
        IssueResponse pending = issue("REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("300.00"))));
        invoiceService.deliver(pending.invoiceNo());
        CreateRedFlushResponse inflight = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", pending.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("80.00")))));
        assertThat(inflight.result()).isEqualTo("CREATED");

        SubmitDifferenceResponse afterInflight = differenceService.submit(new SubmitDifferenceRequest(
                "D-2", pending.invoiceNo(), 1, new BigDecimal("250.00"), "EV-LATE-002"));
        assertThat(afterInflight.result()).isEqualTo("CONFLICT_REJECTED");
        assertThat(differenceService.getDifference(afterInflight.differenceNo()).status())
                .isEqualTo("CONFLICT_REJECTED");

        // 冲突拒绝不触碰额度：台账为开票占用两笔 + 红票确认恢复一笔
        assertThat(creditService.listLedger("S1")).hasSize(3);
        // 1000 - 200 - 300 + 50 = 550
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("550.00");
    }

    @Test
    void 幂等_提交与处置同键同参重放_同键异参409() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        SubmitDifferenceRequest submit = new SubmitDifferenceRequest(
                "D-1", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-IDEM-001");
        SubmitDifferenceResponse first = differenceService.submit(submit);
        SubmitDifferenceResponse replay = differenceService.submit(submit);
        assertThat(replay.differenceNo()).isEqualTo(first.differenceNo());
        Integer differenceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM difference_order", Integer.class);
        assertThat(differenceCount).isEqualTo(1);

        // 同键异参：409 且不产生新单
        assertThatThrownBy(() -> differenceService.submit(new SubmitDifferenceRequest(
                "D-1", issued.invoiceNo(), 1, new BigDecimal("160.00"), "EV-IDEM-001")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM difference_order", Integer.class))
                .isEqualTo(1);

        // 处置幂等：同键同参重放首次结果，不产生第二张红字信息表
        RespondDifferenceResponse confirmFirst = differenceService.respond(first.differenceNo(),
                new RespondDifferenceRequest("R-1", "CONFIRM"));
        RespondDifferenceResponse confirmReplay = differenceService.respond(first.differenceNo(),
                new RespondDifferenceRequest("R-1", "CONFIRM"));
        assertThat(confirmReplay.redRequestNo()).isEqualTo(confirmFirst.redRequestNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM red_flush_request", Integer.class))
                .isEqualTo(1);

        // 处置同键异参：409
        assertThatThrownBy(() -> differenceService.respond(first.differenceNo(),
                new RespondDifferenceRequest("R-1", "REJECT")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        // 额度不因重放或冲突重复恢复
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");
    }

    @Test
    void 校验_主张金额与行号非法拒绝且同行不允许重复未结差异() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        // 主张金额不得大于等于行金额
        assertThatThrownBy(() -> differenceService.submit(new SubmitDifferenceRequest(
                "D-1", issued.invoiceNo(), 1, new BigDecimal("200.00"), "EV-V-001")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VALIDATION_FAILED");
        // 行号不存在
        assertThatThrownBy(() -> differenceService.submit(new SubmitDifferenceRequest(
                "D-2", issued.invoiceNo(), 9, new BigDecimal("100.00"), "EV-V-002")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_NOT_FOUND");
        // 未交付蓝票不允许登记差异
        IssueResponse notDelivered = issue("REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        assertThatThrownBy(() -> differenceService.submit(new SubmitDifferenceRequest(
                "D-3", notDelivered.invoiceNo(), 1, new BigDecimal("50.00"), "EV-V-003")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("INVALID_STATE");

        // 同一行已存在未结差异：拒绝重复登记
        differenceService.submit(new SubmitDifferenceRequest(
                "D-4", issued.invoiceNo(), 1, new BigDecimal("150.00"), "EV-V-004"));
        assertThatThrownBy(() -> differenceService.submit(new SubmitDifferenceRequest(
                "D-5", issued.invoiceNo(), 1, new BigDecimal("140.00"), "EV-V-005")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("LINE_DISPUTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM difference_order", Integer.class))
                .isEqualTo(1);
    }

    private IssueResponse issue(String requestId, List<LineRequest> lines) {
        return invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                requestId, "S1", "BUYER-1", false, lines));
    }

    private ConfirmResponse confirmBoth(String requestNo) {
        redFlushService.confirm(requestNo, new ConfirmRequest("SELLER", "AGREE"));
        return redFlushService.confirm(requestNo, new ConfirmRequest("BUYER", "AGREE"));
    }
}
