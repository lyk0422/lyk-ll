package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.common.ApiException;
import com.example.starter.credit.CreditService;
import com.example.starter.credit.CreditService.CreateCreditAccountRequest;
import com.example.starter.invoice.InvoiceService;
import com.example.starter.invoice.InvoiceService.ActionResponse;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数电发票红冲与授信额度恢复集成测试（H2 MySQL 兼容模式，真实库表/事务/行锁）。
 */
@SpringBootTest
class InvoiceRedFlushIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private RedFlushService redFlushService;
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
    void 主流程_开票交付部分红冲后额度同事务恢复且台账可复算() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("250.00")),
                        new LineRequest("货物B", new BigDecimal("150.00"))));
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.availableLimit()).isEqualByComparingTo("600.00");

        invoiceService.deliver(issued.invoiceNo());
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).status()).isEqualTo("DELIVERED");

        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("100.00")))));
        assertThat(created.result()).isEqualTo("CREATED");
        assertThat(created.status()).isEqualTo("PENDING");

        // 单方确认不生效：无红票、额度不变
        ConfirmResponse first = redFlushService.confirm(created.requestNo(),
                new ConfirmRequest("SELLER", "AGREE"));
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.redInvoiceNo()).isNull();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("600.00");

        // 双方在同一事务内先后确认：开具红票并同事务恢复额度
        ConfirmResponse second = redFlushService.confirm(created.requestNo(),
                new ConfirmRequest("BUYER", "AGREE"));
        assertThat(second.status()).isEqualTo("ISSUED");
        assertThat(second.redInvoiceNo()).isNotNull();
        assertThat(second.availableLimit()).isEqualByComparingTo("700.00");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        InvoiceView view = invoiceService.getInvoice(issued.invoiceNo());
        assertThat(view.status()).isEqualTo("PARTIAL_RED");
        assertThat(view.redFlushedAmount()).isEqualByComparingTo("100.00");
        assertThat(view.redChain()).hasSize(1);
        assertThat(view.redChain().get(0).invoiceNo()).isEqualTo(second.redInvoiceNo());
        assertThat(view.redChain().get(0).amount()).isEqualByComparingTo("100.00");

        // 台账可复算：总额度 + Σ变动 = 当前可用额度
        var ledger = creditService.listLedger("S1");
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(0).reason()).isEqualTo("ISSUE");
        assertThat(ledger.get(0).changeAmount()).isEqualByComparingTo("-400.00");
        assertThat(ledger.get(1).reason()).isEqualTo("RED_CONFIRM");
        assertThat(ledger.get(1).changeAmount()).isEqualByComparingTo("100.00");
        BigDecimal recomputed = new BigDecimal("1000.00")
                .add(ledger.stream().map(CreditService.LedgerEntry::changeAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(recomputed).isEqualByComparingTo(
                creditService.getCredit("S1").availableLimit());

        // 确认轨迹完整且重复查询稳定
        RedFlushRequestView requestView = redFlushService.getRequest(created.requestNo());
        assertThat(requestView.confirmations()).hasSize(2);
        assertThat(requestView.confirmations().get(0).side()).isEqualTo("SELLER");
        assertThat(requestView.confirmations().get(1).side()).isEqualTo("BUYER");
        assertThat(redFlushService.getRequest(created.requestNo())).isEqualTo(requestView);
    }

    @Test
    void 部分红冲_累计不得超行余额与可红冲余额() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());

        CreateRedFlushResponse first = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("150.00")))));
        assertThat(first.result()).isEqualTo("CREATED");

        // 在途申请计入占用：150 + 60 > 行余额 200，拒绝
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("60.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("RED_FLUSH_EXCEEDS_LINE");

        confirmBoth(first.requestNo());

        // 已确认 150 后再申请 60：累计超行余额，拒绝
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-3", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("60.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("RED_FLUSH_EXCEEDS_LINE");

        // 恰红至行余额合法：150 + 50 = 200
        CreateRedFlushResponse exact = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-4", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00")))));
        assertThat(exact.result()).isEqualTo("CREATED");
        confirmBoth(exact.requestNo());
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).redFlushedAmount())
                .isEqualByComparingTo("200.00");
    }

    @Test
    void 部分红冲_恰红至余额为零合法且蓝票终态() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());

        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("200.00")))));
        assertThat(created.result()).isEqualTo("CREATED");
        ConfirmResponse confirmed = confirmBoth(created.requestNo());
        assertThat(confirmed.status()).isEqualTo("ISSUED");

        InvoiceView view = invoiceService.getInvoice(issued.invoiceNo());
        assertThat(view.status()).isEqualTo("RED_FLUSHED");
        assertThat(view.redFlushedAmount()).isEqualByComparingTo("200.00");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void 终态幂等_已全额红冲蓝票再红冲返回幂等结果且不产生新红票() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "FULL", null));
        confirmBoth(created.requestNo());
        assertThat(invoiceService.getInvoice(issued.invoiceNo()).status()).isEqualTo("RED_FLUSHED");
        Integer redCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'RED'", Integer.class);
        assertThat(redCount).isEqualTo(1);

        CreateRedFlushResponse again = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "FULL", null));
        assertThat(again.result()).isEqualTo("ALREADY_RED_FLUSHED");
        assertThat(again.requestNo()).isNull();
        Integer redCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'RED'", Integer.class);
        assertThat(redCountAfter).isEqualTo(1);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void 双方确认_意见不一致挂起且挂起后拒绝再确认() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(issued.invoiceNo());
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "FULL", null));

        redFlushService.confirm(created.requestNo(), new ConfirmRequest("SELLER", "AGREE"));
        ConfirmResponse rejected = redFlushService.confirm(created.requestNo(),
                new ConfirmRequest("BUYER", "REJECT"));
        assertThat(rejected.status()).isEqualTo("SUSPENDED");
        assertThat(redFlushService.getRequest(created.requestNo()).status()).isEqualTo("SUSPENDED");
        assertThatThrownBy(() -> redFlushService.confirm(created.requestNo(),
                new ConfirmRequest("BUYER", "AGREE")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("REQUEST_SUSPENDED");
        // 挂起不产生红票、不恢复额度
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");
    }

    @Test
    void 开票_可用额度恰等于开票金额合法_超出拒绝且额度不变() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("500.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("500.00"))));
        assertThat(issued.availableLimit()).isEqualByComparingTo("0.00");

        assertThatThrownBy(() -> issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("0.01")))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("0.00");
        assertThat(creditService.listLedger("S1")).hasSize(1);
    }

    @Test
    void 并发开票_串行校验不得合计超额() throws Exception {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        int threads = 10;
        BigDecimal amount = new BigDecimal("150.00");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "REQ-C-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                        issue("S1", requestId,
                                List.of(new LineRequest("货物", amount)));
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.getCode()).isEqualTo("INSUFFICIENT_CREDIT");
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            gate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // 1000 / 150 = 6 张成功（900），第 7 张起拒绝
        assertThat(success.get()).isEqualTo(6);
        assertThat(rejected.get()).isEqualTo(4);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("100.00");
        Integer invoiceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'BLUE'", Integer.class);
        assertThat(invoiceCount).isEqualTo(6);
        BigDecimal occupied = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM invoice WHERE kind = 'BLUE'",
                BigDecimal.class);
        assertThat(occupied).isEqualByComparingTo("900.00");
    }

    @Test
    void 额度恢复原子性_开具红票失败则确认整体回滚且额度不变() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(issued.invoiceNo());

        CreateRedFlushResponse partial = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("150.00")))));
        CreateRedFlushResponse full = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", issued.invoiceNo(), "FULL", null));

        // 全额申请先确认：按当前余额开具 300，蓝票终态
        ConfirmResponse fullConfirmed = confirmBoth(full.requestNo());
        assertThat(fullConfirmed.status()).isEqualTo("ISSUED");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");

        // 部分申请再确认：余额不足，整个确认事务回滚
        assertThatThrownBy(() -> confirmBoth(partial.requestNo()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NO_REMAINING_BALANCE");
        RedFlushRequestView partialView = redFlushService.getRequest(partial.requestNo());
        assertThat(partialView.status()).isEqualTo("PENDING");
        // 销方确认是独立事务已提交；购方确认随开票的失败整体回滚
        assertThat(partialView.sellerConfirmed()).isTrue();
        assertThat(partialView.buyerConfirmed()).isFalse();
        assertThat(partialView.confirmations()).hasSize(1);
        assertThat(partialView.confirmations().get(0).side()).isEqualTo("SELLER");
        // 只有全额申请对应的一张红票，额度台账仅两笔，可用额度不变
        Integer redCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'RED'", Integer.class);
        assertThat(redCount).isEqualTo(1);
        assertThat(creditService.listLedger("S1")).hasSize(2);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void 归档_终态幂等且归档后只许红冲() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("300.00"))));
        invoiceService.deliver(issued.invoiceNo());

        ActionResponse archived = invoiceService.archive(issued.invoiceNo());
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.availableLimit()).isEqualByComparingTo("1000.00");

        // 重复归档幂等成功，不重复释放额度
        ActionResponse again = invoiceService.archive(issued.invoiceNo());
        assertThat(again.status()).isEqualTo("ARCHIVED");
        assertThat(creditService.listLedger("S1")).hasSize(2);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1000.00");

        // 归档后禁止交付等状态变更
        assertThatThrownBy(() -> invoiceService.deliver(issued.invoiceNo()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("INVALID_STATE");

        // 归档后发现差错只许红冲：红冲引用原发票号码并保留完整链
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("120.00")))));
        assertThat(created.result()).isEqualTo("CREATED");
        ConfirmResponse confirmed = confirmBoth(created.requestNo());
        assertThat(confirmed.status()).isEqualTo("ISSUED");

        InvoiceView view = invoiceService.getInvoice(issued.invoiceNo());
        assertThat(view.status()).isEqualTo("ARCHIVED");
        assertThat(view.redFlushedAmount()).isEqualByComparingTo("120.00");
        // 原始票面未被修改
        assertThat(view.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(view.redChain()).hasSize(1);
        InvoiceView redView = invoiceService.getInvoice(confirmed.redInvoiceNo());
        assertThat(redView.blueInvoiceNo()).isEqualTo(issued.invoiceNo());
        // 红票确认后额度按公式恢复：1000 + 120
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("1120.00");
    }

    @Test
    void 幂等_同键同参返回首次结果_同键异参返回409且额度不变() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueBlueInvoiceRequest request = new IssueBlueInvoiceRequest("REQ-1", "S1", "B1", false,
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse first = invoiceService.issueBlue(request);
        IssueResponse replay = invoiceService.issueBlue(request);
        assertThat(replay.invoiceNo()).isEqualTo(first.invoiceNo());
        Integer invoiceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'BLUE'", Integer.class);
        assertThat(invoiceCount).isEqualTo(1);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");

        // 同键异参：409 且额度不变
        IssueBlueInvoiceRequest tampered = new IssueBlueInvoiceRequest("REQ-1", "S1", "B1", false,
                List.of(new LineRequest("货物A", new BigDecimal("201.00"))));
        assertThatThrownBy(() -> invoiceService.issueBlue(tampered))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");

        // 红冲申请同样幂等
        invoiceService.deliver(first.invoiceNo());
        CreateRedFlushRequest flushRequest = new CreateRedFlushRequest("RF-1", first.invoiceNo(),
                "PARTIAL", List.of(new RedLineRequest(1, new BigDecimal("50.00"))));
        CreateRedFlushResponse flushFirst = redFlushService.createRequest(flushRequest);
        CreateRedFlushResponse flushReplay = redFlushService.createRequest(flushRequest);
        assertThat(flushReplay.requestNo()).isEqualTo(flushFirst.requestNo());
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM red_flush_request", Integer.class);
        assertThat(requestCount).isEqualTo(1);
        CreateRedFlushRequest flushTampered = new CreateRedFlushRequest("RF-1", first.invoiceNo(),
                "PARTIAL", List.of(new RedLineRequest(1, new BigDecimal("60.00"))));
        assertThatThrownBy(() -> redFlushService.createRequest(flushTampered))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void 全额红冲_购方已入账时单方确认不生效须双方确认() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse issued = invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                "REQ-1", "S1", "B1", true,
                List.of(new LineRequest("货物A", new BigDecimal("200.00")))));
        invoiceService.deliver(issued.invoiceNo());
        CreateRedFlushResponse created = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", issued.invoiceNo(), "FULL", null));

        ConfirmResponse sellerOnly = redFlushService.confirm(created.requestNo(),
                new ConfirmRequest("SELLER", "AGREE"));
        assertThat(sellerOnly.status()).isEqualTo("PENDING");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");

        ConfirmResponse both = redFlushService.confirm(created.requestNo(),
                new ConfirmRequest("BUYER", "AGREE"));
        assertThat(both.status()).isEqualTo("ISSUED");
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
