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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.starter.batch.BatchRedFlushService;
import com.example.starter.batch.BatchRedFlushService.BatchItemRequest;
import com.example.starter.batch.BatchRedFlushService.BatchView;
import com.example.starter.batch.BatchRedFlushService.ConfirmBatchRequest;
import com.example.starter.batch.BatchRedFlushService.ConfirmBatchResponse;
import com.example.starter.batch.BatchRedFlushService.CreateBatchRequest;
import com.example.starter.batch.BatchRedFlushService.CreateBatchResponse;
import com.example.starter.batch.BatchRedFlushService.WithdrawBatchRequest;
import com.example.starter.batch.BatchRedFlushService.WithdrawBatchResponse;
import com.example.starter.common.ApiException;
import com.example.starter.credit.CreditService;
import com.example.starter.credit.CreditService.CreateCreditAccountRequest;
import com.example.starter.discrepancy.DiscrepancyService;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyResponse;
import com.example.starter.invoice.InvoiceService;
import com.example.starter.invoice.InvoiceService.InvoiceView;
import com.example.starter.invoice.InvoiceService.IssueBlueInvoiceRequest;
import com.example.starter.invoice.InvoiceService.IssueResponse;
import com.example.starter.invoice.InvoiceService.LineRequest;
import com.example.starter.redflush.RedFlushService;
import com.example.starter.redflush.RedFlushService.ConfirmRequest;
import com.example.starter.redflush.RedFlushService.CreateRedFlushRequest;
import com.example.starter.redflush.RedFlushService.CreateRedFlushResponse;
import com.example.starter.redflush.RedFlushService.RedLineRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 整批红冲集成测试（H2 MySQL 兼容模式，真实库表/事务/行锁，可控时钟）。
 * 覆盖：整批受理预占、任一行失败整批 422 不留占用、争议行隔离、预占互斥与并发抢占、
 * 双确认生效恢复额度、单方拒绝终止、撤回、确认与撤回竞争、受控子单入口隔离、
 * 版本过期 409、requestId 幂等与额度勾稽查询。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_batch;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class BatchRedFlushIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private BatchRedFlushService batchService;
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
        jdbc.update("DELETE FROM red_flush_batch_opinion");
        jdbc.update("DELETE FROM red_flush_batch");
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
    void 主流程_两票整批受理预占后双确认生效恢复额度且勾稽一致() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物C", new BigDecimal("150.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("550.00");

        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-1", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("100.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.totalAmount()).isEqualByComparingTo("160.00");
        assertThat(created.items()).hasSize(2);
        assertThat(created.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("PENDING");
            assertThat(item.requestNo()).startsWith("RFR");
        });

        // 预占本身不恢复授信，原蓝票不改
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("550.00");
        assertThat(invoiceService.getInvoice(inv1.invoiceNo()).redFlushedAmount()).isEqualByComparingTo("0.00");
        assertThat(invoiceService.getInvoice(inv1.invoiceNo()).status()).isEqualTo("DELIVERED");

        // 额度勾稽：预占 160、未恢复、可用 550；批时间按 UTC 可控时钟
        BatchView pending = batchService.getBatch(created.batchNo());
        assertThat(pending.preOccupiedAmount()).isEqualByComparingTo("160.00");
        assertThat(pending.restoredAmount()).isEqualByComparingTo("0.00");
        assertThat(pending.availableLimit()).isEqualByComparingTo("550.00");
        assertThat(pending.opinions()).isEmpty();
        assertThat(pending.createdAt()).isNotNull();
        assertThat(pending.createdAt()).isEqualTo(pending.updatedAt());

        // 单方同意只留意见：版本加 1，无红票、额度不变
        ConfirmBatchResponse sellerAgree = batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-1", "SELLER", "AGREE", 1));
        assertThat(sellerAgree.status()).isEqualTo("PENDING");
        assertThat(sellerAgree.version()).isEqualTo(2);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("550.00");
        assertThat(countRedInvoices()).isZero();
        BatchView afterSeller = batchService.getBatch(created.batchNo());
        assertThat(afterSeller.opinions()).hasSize(1);
        assertThat(afterSeller.opinions().get(0).side()).isEqualTo("SELLER");
        assertThat(afterSeller.opinions().get(0).opinion()).isEqualTo("AGREE");

        // 双方均同意：一个事务生成全部红票、消费预占并恢复对应额度
        ConfirmBatchResponse bothAgree = batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-2", "BUYER", "AGREE", 2));
        assertThat(bothAgree.status()).isEqualTo("ISSUED");
        assertThat(bothAgree.version()).isEqualTo(3);
        assertThat(bothAgree.availableLimit()).isEqualByComparingTo("710.00");
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("710.00");
        assertThat(countRedInvoices()).isEqualTo(2);

        InvoiceView view1 = invoiceService.getInvoice(inv1.invoiceNo());
        assertThat(view1.redFlushedAmount()).isEqualByComparingTo("100.00");
        assertThat(view1.status()).isEqualTo("PARTIAL_RED");
        assertThat(view1.redChain()).hasSize(1);
        InvoiceView view2 = invoiceService.getInvoice(inv2.invoiceNo());
        assertThat(view2.redFlushedAmount()).isEqualByComparingTo("60.00");

        // 勾稽：预占清零、已恢复 160、子单均 ISSUED 且带红票号
        BatchView issued = batchService.getBatch(created.batchNo());
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.version()).isEqualTo(3);
        assertThat(issued.preOccupiedAmount()).isEqualByComparingTo("0.00");
        assertThat(issued.restoredAmount()).isEqualByComparingTo("160.00");
        assertThat(issued.availableLimit()).isEqualByComparingTo("710.00");
        assertThat(issued.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("ISSUED");
            assertThat(item.redInvoiceNo()).startsWith("RED");
        });
        assertThat(issued.opinions()).hasSize(2);

        // 台账可复算：总额度 + Σ变动 = 当前可用额度
        var ledger = creditService.listLedger("S1");
        assertThat(ledger).hasSize(4);
        assertThat(ledger.get(2).reason()).isEqualTo("RED_CONFIRM");
        assertThat(ledger.get(2).changeAmount()).isEqualByComparingTo("100.00");
        assertThat(ledger.get(3).reason()).isEqualTo("RED_CONFIRM");
        assertThat(ledger.get(3).changeAmount()).isEqualByComparingTo("60.00");
        BigDecimal recomputed = new BigDecimal("1000.00")
                .add(ledger.stream().map(CreditService.LedgerEntry::changeAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(recomputed).isEqualByComparingTo("710.00");
    }

    @Test
    void 任一行超余额_整批422不留子单或占用且失败不占键() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 行1 申请 250 超余额 200：整批 422
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-F", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("250.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00"))))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("RED_FLUSH_EXCEEDS_LINE");
                });
        // 不留下子单、批或占用；失败不占幂等键（开票自身的幂等记录不在此列）
        assertThat(countRows("red_flush_request")).isZero();
        assertThat(countRows("red_flush_batch")).isZero();
        assertThat(countIdempotencyKeys("RB-F")).isZero();

        // 同一 requestId 修正参数后可重新受理
        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-F", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("150.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(countRows("red_flush_request")).isEqualTo(2);
    }

    @Test
    void 争议行_整批422且无争议行可正常受理() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物C", new BigDecimal("150.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        SubmitDiscrepancyResponse submitted = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", inv1.invoiceNo(), 1, new BigDecimal("150.00"), "EV-0001"));
        assertThat(submitted.status()).isEqualTo("OPEN");

        // 涉及争议行：整批 422
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-1", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00"))))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("LINE_DISPUTED");
                });
        assertThat(countRows("red_flush_request")).isZero();

        // 无争议行：正常受理
        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-2", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(2, new BigDecimal("50.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");
    }

    @Test
    void 预占互斥_批预占限制普通红冲与差异登记_普通在途也限制批() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-1", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("150.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");

        // 批预占与普通红冲共享行互斥：剩余 50，申请 60 拒绝
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", inv1.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("60.00"))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RED_FLUSH_EXCEEDS_LINE"));
        // 恰占剩余 50 合法（原单票请求继续可用）
        CreateRedFlushResponse normal = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-2", inv1.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("50.00")))));
        assertThat(normal.result()).isEqualTo("CREATED");

        // 批预占与差异登记共享行互斥：被批占用的行登记差异被拒并保留冲突记录
        SubmitDiscrepancyResponse conflict = discrepancyService.submit(new SubmitDiscrepancyRequest(
                "DIF-1", inv2.invoiceNo(), 1, new BigDecimal("30.00"), "EV-0002"));
        assertThat(conflict.result()).isEqualTo("CONFLICT_REJECTED");
        assertThat(countRows("discrepancy_conflict")).isEqualTo(1);

        // 反向：普通红冲在途也限制整批
        IssueResponse inv3 = issue("S1", "REQ-3",
                List.of(new LineRequest("货物D", new BigDecimal("200.00"))));
        IssueResponse inv4 = issue("S1", "REQ-4",
                List.of(new LineRequest("货物E", new BigDecimal("100.00"))));
        invoiceService.deliver(inv3.invoiceNo());
        invoiceService.deliver(inv4.invoiceNo());
        redFlushService.createRequest(new CreateRedFlushRequest("RF-3", inv3.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("120.00")))));
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-2", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv3.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("90.00")))),
                new BatchItemRequest(inv4.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00"))))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("RED_FLUSH_EXCEEDS_LINE");
                });
    }

    @Test
    void 并发受理_两批抢占同一行余额只能成功一批() throws Exception {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        IssueResponse inv3 = issue("S1", "REQ-3",
                List.of(new LineRequest("货物C", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        invoiceService.deliver(inv3.invoiceNo());

        CreateBatchRequest batch1 = new CreateBatchRequest("RB-C1", "S1", "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("150.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00"))))));
        CreateBatchRequest batch2 = new CreateBatchRequest("RB-C2", "S1", "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("150.00")))),
                new BatchItemRequest(inv3.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00"))))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> acceptOne(batch1, gate, success, rejected)),
                    pool.submit(() -> acceptOne(batch2, gate, success, rejected)));
            gate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // 合计 300 超行余额 200：只能成功一批
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(countRows("red_flush_batch")).isEqualTo(1);
        BigDecimal pendingOnLine = jdbc.queryForObject(
                "SELECT COALESCE(SUM(rl.amount), 0) FROM red_flush_line rl"
                        + " JOIN red_flush_request r ON rl.request_id = r.id"
                        + " JOIN invoice_line il ON rl.blue_line_id = il.id"
                        + " JOIN invoice i ON il.invoice_id = i.id"
                        + " WHERE i.invoice_no = ? AND r.status = 'PENDING'",
                BigDecimal.class, inv1.invoiceNo());
        assertThat(pendingOnLine).isEqualByComparingTo("150.00");
    }

    @Test
    void 单方拒绝_整批终止释放全部占用() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());

        ConfirmBatchResponse sellerAgree = batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-1", "SELLER", "AGREE", 1));
        assertThat(sellerAgree.version()).isEqualTo(2);

        ConfirmBatchResponse rejected = batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-2", "BUYER", "REJECT", 2));
        assertThat(rejected.status()).isEqualTo("TERMINATED");
        assertThat(rejected.version()).isEqualTo(3);

        BatchView view = batchService.getBatch(created.batchNo());
        assertThat(view.status()).isEqualTo("TERMINATED");
        assertThat(view.preOccupiedAmount()).isEqualByComparingTo("0.00");
        assertThat(view.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo("TERMINATED"));
        assertThat(countRedInvoices()).isZero();
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        // 占用已释放：普通红冲可占满行余额
        CreateRedFlushResponse normal = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", inv1.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("200.00")))));
        assertThat(normal.result()).isEqualTo("CREATED");

        // 终止后拒绝再确认或撤回
        assertThatThrownBy(() -> batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-3", "BUYER", "AGREE", 3)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("BATCH_TERMINATED"));
        assertThatThrownBy(() -> batchService.withdrawBatch(created.batchNo(),
                new WithdrawBatchRequest("WD-1", 3)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("BATCH_TERMINATED"));
    }

    @Test
    void 撤回_未生效可撤回释放占用_生效批不可撤回() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        CreateBatchResponse first = batchService.createBatch(new CreateBatchRequest("RB-1", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("100.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        WithdrawBatchResponse withdrawn = batchService.withdrawBatch(first.batchNo(),
                new WithdrawBatchRequest("WD-1", 1));
        assertThat(withdrawn.status()).isEqualTo("TERMINATED");
        assertThat(withdrawn.version()).isEqualTo(2);
        BatchView view = batchService.getBatch(first.batchNo());
        assertThat(view.preOccupiedAmount()).isEqualByComparingTo("0.00");
        assertThat(view.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo("TERMINATED"));
        // 占用已释放
        CreateRedFlushResponse normal = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", inv1.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("200.00")))));
        assertThat(normal.result()).isEqualTo("CREATED");
        redFlushService.confirm(normal.requestNo(), new ConfirmRequest("SELLER", "AGREE"));
        redFlushService.confirm(normal.requestNo(), new ConfirmRequest("BUYER", "AGREE"));

        // 生效批不可撤回
        IssueResponse inv3 = issue("S1", "REQ-3",
                List.of(new LineRequest("货物C", new BigDecimal("100.00"))));
        IssueResponse inv4 = issue("S1", "REQ-4",
                List.of(new LineRequest("货物D", new BigDecimal("100.00"))));
        invoiceService.deliver(inv3.invoiceNo());
        invoiceService.deliver(inv4.invoiceNo());
        CreateBatchResponse second = batchService.createBatch(new CreateBatchRequest("RB-2", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv3.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("40.00")))),
                new BatchItemRequest(inv4.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("40.00")))))));
        batchService.confirmBatch(second.batchNo(), new ConfirmBatchRequest("CF-1", "SELLER", "AGREE", 1));
        ConfirmBatchResponse issued = batchService.confirmBatch(second.batchNo(),
                new ConfirmBatchRequest("CF-2", "BUYER", "AGREE", 2));
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThatThrownBy(() -> batchService.withdrawBatch(second.batchNo(),
                new WithdrawBatchRequest("WD-2", 3)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("BATCH_ALREADY_ISSUED"));
        assertThat(batchService.getBatch(second.batchNo()).status()).isEqualTo("ISSUED");
    }

    @Test
    void 确认与撤回竞争_只能全部生效或全部撤回且重试不多开红票() throws Exception {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());
        batchService.confirmBatch(created.batchNo(), new ConfirmBatchRequest("CF-1", "SELLER", "AGREE", 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Object> confirmOutcome = new AtomicReference<>();
        AtomicReference<Object> withdrawOutcome = new AtomicReference<>();
        try {
            Future<?> confirmFuture = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    confirmOutcome.set(batchService.confirmBatch(created.batchNo(),
                            new ConfirmBatchRequest("CF-R", "BUYER", "AGREE", 2)));
                } catch (ApiException e) {
                    confirmOutcome.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> withdrawFuture = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    withdrawOutcome.set(batchService.withdrawBatch(created.batchNo(),
                            new WithdrawBatchRequest("WD-R", 2)));
                } catch (ApiException e) {
                    withdrawOutcome.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.countDown();
            confirmFuture.get(30, TimeUnit.SECONDS);
            withdrawFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        boolean confirmWon = confirmOutcome.get() instanceof ConfirmBatchResponse;
        boolean withdrawWon = withdrawOutcome.get() instanceof WithdrawBatchResponse;
        // 只能全部生效或全部撤回：恰一方成功，另一方 409
        assertThat(confirmWon).isNotEqualTo(withdrawWon);
        Object loser = confirmWon ? withdrawOutcome.get() : confirmOutcome.get();
        assertThat(loser).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        BatchView view = batchService.getBatch(created.batchNo());
        if (confirmWon) {
            assertThat(view.status()).isEqualTo("ISSUED");
            assertThat(countRedInvoices()).isEqualTo(2);
            assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("860.00");
            // 重放成功方：不多开红票、不多回补额度
            ConfirmBatchResponse replay = batchService.confirmBatch(created.batchNo(),
                    new ConfirmBatchRequest("CF-R", "BUYER", "AGREE", 2));
            assertThat(replay.status()).isEqualTo("ISSUED");
            // 失败方换新键重试：仍 409
            assertThatThrownBy(() -> batchService.withdrawBatch(created.batchNo(),
                    new WithdrawBatchRequest("WD-R2", 3)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("BATCH_ALREADY_ISSUED"));
        } else {
            assertThat(view.status()).isEqualTo("TERMINATED");
            assertThat(countRedInvoices()).isZero();
            assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");
            WithdrawBatchResponse replay = batchService.withdrawBatch(created.batchNo(),
                    new WithdrawBatchRequest("WD-R", 2));
            assertThat(replay.status()).isEqualTo("TERMINATED");
            assertThatThrownBy(() -> batchService.confirmBatch(created.batchNo(),
                    new ConfirmBatchRequest("CF-R2", "BUYER", "AGREE", 3)))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("BATCH_TERMINATED"));
        }
        assertThat(countRedInvoices()).isEqualTo(confirmWon ? 2 : 0);
        // 额度台账仅开票两笔（生效时加两笔恢复），无重复回补
        int expectedLedger = confirmWon ? 4 : 2;
        assertThat(creditService.listLedger("S1")).hasSize(expectedLedger);
    }

    @Test
    void 普通确认入口必须拒绝受控子单() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());
        String childRequestNo = created.items().get(0).requestNo();

        // 普通子单确认入口拒绝受控子单，不能绕过整批原子性
        assertThatThrownBy(() -> redFlushService.confirm(childRequestNo,
                new ConfirmRequest("SELLER", "AGREE")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("CONTROLLED_CHILD");
                });
        // 子单详情仍可查询，状态未被普通入口改变
        assertThat(redFlushService.getRequest(childRequestNo).status()).isEqualTo("PENDING");
        assertThat(countRedInvoices()).isZero();
    }

    @Test
    void 版本控制_过期409成功加1() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());
        assertThat(created.version()).isEqualTo(1);

        // 过期版本 409
        assertThatThrownBy(() -> batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-1", "SELLER", "AGREE", 2)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT");
                });
        assertThatThrownBy(() -> batchService.withdrawBatch(created.batchNo(),
                new WithdrawBatchRequest("WD-1", 5)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT"));
        assertThat(batchService.getBatch(created.batchNo()).version()).isEqualTo(1);

        // 正确版本成功加 1；随后旧版本过期
        ConfirmBatchResponse sellerAgree = batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-2", "SELLER", "AGREE", 1));
        assertThat(sellerAgree.version()).isEqualTo(2);
        assertThatThrownBy(() -> batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-3", "BUYER", "AGREE", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT"));
        ConfirmBatchResponse bothAgree = batchService.confirmBatch(created.batchNo(),
                new ConfirmBatchRequest("CF-4", "BUYER", "AGREE", 2));
        assertThat(bothAgree.status()).isEqualTo("ISSUED");
        assertThat(bothAgree.version()).isEqualTo(3);
    }

    @Test
    void 幂等_同键同参重放原响应_改参409_重试不多开红票() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        CreateBatchRequest createRequest = new CreateBatchRequest("RB-1", "S1", "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("100.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("60.00"))))));
        CreateBatchResponse first = batchService.createBatch(createRequest);
        CreateBatchResponse replay = batchService.createBatch(createRequest);
        assertThat(replay.batchNo()).isEqualTo(first.batchNo());
        assertThat(countRows("red_flush_batch")).isEqualTo(1);
        assertThat(countRows("red_flush_request")).isEqualTo(2);

        // 同键改参 409
        CreateBatchRequest tampered = new CreateBatchRequest("RB-1", "S1", "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("100.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("70.00"))))));
        assertThatThrownBy(() -> batchService.createBatch(tampered))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));

        // 确认幂等：重放不重复推进版本
        ConfirmBatchRequest sellerConfirm = new ConfirmBatchRequest("CF-1", "SELLER", "AGREE", 1);
        ConfirmBatchResponse sellerFirst = batchService.confirmBatch(first.batchNo(), sellerConfirm);
        ConfirmBatchResponse sellerReplay = batchService.confirmBatch(first.batchNo(), sellerConfirm);
        assertThat(sellerReplay).isEqualTo(sellerFirst);
        assertThat(batchService.getBatch(first.batchNo()).version()).isEqualTo(2);

        // 生效后重放购方确认：不多开红票、不多回补额度
        ConfirmBatchRequest buyerConfirm = new ConfirmBatchRequest("CF-2", "BUYER", "AGREE", 2);
        ConfirmBatchResponse buyerFirst = batchService.confirmBatch(first.batchNo(), buyerConfirm);
        assertThat(buyerFirst.status()).isEqualTo("ISSUED");
        ConfirmBatchResponse buyerReplay = batchService.confirmBatch(first.batchNo(), buyerConfirm);
        assertThat(buyerReplay).isEqualTo(buyerFirst);
        assertThat(countRedInvoices()).isEqualTo(2);
        assertThat(creditService.listLedger("S1")).hasSize(4);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("860.00");

        // 撤回幂等：重放原响应
        CreateBatchResponse second = batchService.createBatch(new CreateBatchRequest("RB-2", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("20.00")))))));
        WithdrawBatchRequest withdrawRequest = new WithdrawBatchRequest("WD-1", 1);
        WithdrawBatchResponse withdrawFirst = batchService.withdrawBatch(second.batchNo(), withdrawRequest);
        WithdrawBatchResponse withdrawReplay = batchService.withdrawBatch(second.batchNo(), withdrawRequest);
        assertThat(withdrawReplay).isEqualTo(withdrawFirst);
        assertThat(batchService.getBatch(second.batchNo()).version()).isEqualTo(2);
    }

    @Test
    void 结构校验_票数范围重复票重复行金额精度均为400() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        invoiceService.deliver(inv1.invoiceNo());

        // 少于 2 张
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-1", "S1",
                "BUYER-1", List.of(new BatchItemRequest(inv1.invoiceNo(),
                List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                });
        // 多于 10 张
        List<BatchItemRequest> eleven = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> new BatchItemRequest("INV-X-" + i,
                        List.of(new RedLineRequest(1, new BigDecimal("10.00")))))
                .toList();
        assertThatThrownBy(() -> batchService.createBatch(
                new CreateBatchRequest("RB-2", "S1", "BUYER-1", eleven)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        // 批内蓝票重复
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-3", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("10.00")))),
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("20.00"))))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        // 同票行号重复
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-4", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(), List.of(
                        new RedLineRequest(1, new BigDecimal("10.00")),
                        new RedLineRequest(1, new BigDecimal("20.00")))),
                new BatchItemRequest("INV-OTHER",
                        List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        // 金额非正数、超两位小数
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-5", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("0.00")))),
                new BatchItemRequest("INV-OTHER",
                        List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-6", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("10.001")))),
                new BatchItemRequest("INV-OTHER",
                        List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        // 结构校验失败同样不留批、不占键
        assertThat(countRows("red_flush_batch")).isZero();
        assertThat(countIdempotencyKeys("RB-1", "RB-2", "RB-3", "RB-4", "RB-5", "RB-6")).isZero();
    }

    @Test
    void 购销双方不一致_整批422() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                "REQ-2", "S1", "BUYER-2", false,
                List.of(new LineRequest("货物B", new BigDecimal("100.00")))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-1", "S1",
                "BUYER-1", List.of(
                new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00")))),
                new BatchItemRequest(inv2.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("50.00"))))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("PARTY_MISMATCH");
                });
        assertThat(countRows("red_flush_request")).isZero();
        assertThat(countRows("red_flush_batch")).isZero();
    }

    private void acceptOne(CreateBatchRequest request, CountDownLatch gate,
                           AtomicInteger success, AtomicInteger rejected) {
        try {
            gate.await(10, TimeUnit.SECONDS);
            batchService.createBatch(request);
            success.incrementAndGet();
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            rejected.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CreateBatchResponse createTwoInvoiceBatch(String inv1No, String inv2No) {
        return batchService.createBatch(new CreateBatchRequest("RB-X-" + inv1No, "S1", "BUYER-1",
                List.of(
                        new BatchItemRequest(inv1No,
                                List.of(new RedLineRequest(1, new BigDecimal("100.00")))),
                        new BatchItemRequest(inv2No,
                                List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
    }

    private IssueResponse issue(String sellerId, String requestId, List<LineRequest> lines) {
        return invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                requestId, sellerId, "BUYER-1", false, lines));
    }

    private int countRedInvoices() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'RED'", Integer.class);
        return count == null ? 0 : count;
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private int countIdempotencyKeys(String... requestIds) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(requestIds.length, "?"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id IN (" + placeholders + ")",
                Integer.class, (Object[]) requestIds);
        return count == null ? 0 : count;
    }

    /**
     * 可控时钟：整批相关时间按 UTC 记时。
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

    @TestConfiguration
    static class ClockTestConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }
}
