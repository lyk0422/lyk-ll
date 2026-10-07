package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyResponse;
import com.example.starter.invoice.InvoiceService;
import com.example.starter.invoice.InvoiceService.InvoiceView;
import com.example.starter.invoice.InvoiceService.IssueBlueInvoiceRequest;
import com.example.starter.invoice.InvoiceService.IssueResponse;
import com.example.starter.invoice.InvoiceService.LineRequest;
import com.example.starter.redflush.RedFlushBatchService;
import com.example.starter.redflush.RedFlushBatchService.BatchConfirmRequest;
import com.example.starter.redflush.RedFlushBatchService.BatchConfirmResponse;
import com.example.starter.redflush.RedFlushBatchService.BatchItemRequest;
import com.example.starter.redflush.RedFlushBatchService.BatchItemView;
import com.example.starter.redflush.RedFlushBatchService.BatchView;
import com.example.starter.redflush.RedFlushBatchService.BatchWithdrawRequest;
import com.example.starter.redflush.RedFlushBatchService.BatchWithdrawResponse;
import com.example.starter.redflush.RedFlushBatchService.CreateBatchRequest;
import com.example.starter.redflush.RedFlushBatchService.CreateBatchResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 整批红冲集成测试（H2 MySQL 兼容模式，真实库表/事务/行锁）。
 * 覆盖：两票整批主流程、任一行失败整批 422、在途争议隔离、结构校验、
 * 批预占与普通红冲/差异登记互斥、并发受理不超余额、单方拒绝终止、
 * 撤回与生效批保护、受控子单拒绝普通确认、确认与撤回竞争、版本过期与幂等。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test_batch;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class RedFlushBatchIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private RedFlushBatchService batchService;
    @Autowired
    private DiscrepancyService discrepancyService;
    @Autowired
    private CreditService creditService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM red_flush_batch_confirmation");
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
    }

    @AfterAll
    static void shutdownDatabase(@Autowired JdbcTemplate jdbc) {
        // DB_CLOSE_DELAY=-1 下显式释放测试库，避免上下文结束后悬挂
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void 主流程_两票整批_双方确认后一个事务生成全部红票并恢复额度() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物C", new BigDecimal("150.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("550.00");

        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("80.00")),
                                        new RedLineRequest(2, new BigDecimal("50.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.totalAmount()).isEqualByComparingTo("190.00");
        assertThat(created.items()).hasSize(2);
        String child1 = created.items().get(0).requestNo();
        String child2 = created.items().get(1).requestNo();
        assertThat(child1).isNotEqualTo(child2);

        // 预占不恢复授信；批视图可见预占合计
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("550.00");
        BatchView pending = batchService.getBatch(created.batchNo());
        assertThat(pending.credit().reservedAmount()).isEqualByComparingTo("190.00");
        assertThat(pending.credit().availableLimit()).isEqualByComparingTo("550.00");
        assertThat(pending.items()).extracting(BatchItemView::status).containsOnly("PENDING");

        // 单方同意只留意见：无红票、额度不变
        BatchConfirmResponse sellerOnly = batchService.confirm(created.batchNo(),
                new BatchConfirmRequest("CF-S", "SELLER", "AGREE", 1));
        assertThat(sellerOnly.status()).isEqualTo("PENDING");
        assertThat(sellerOnly.version()).isEqualTo(2);
        assertThat(countRedInvoices()).isEqualTo(0);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("550.00");

        // 双方均同意：一个事务生成全部红票、消费预占并恢复对应额度
        BatchConfirmResponse both = batchService.confirm(created.batchNo(),
                new BatchConfirmRequest("CF-B", "BUYER", "AGREE", 2));
        assertThat(both.status()).isEqualTo("ISSUED");
        assertThat(both.version()).isEqualTo(3);
        assertThat(both.availableLimit()).isEqualByComparingTo("740.00");
        assertThat(countRedInvoices()).isEqualTo(2);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("740.00");

        // 子单已消费预占并开具红票；蓝票累计红冲
        BatchView issued = batchService.getBatch(created.batchNo());
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.items()).extracting(BatchItemView::status).containsOnly("ISSUED");
        assertThat(issued.items()).allSatisfy(item -> assertThat(item.redInvoiceNo()).isNotNull());
        assertThat(issued.credit().reservedAmount()).isEqualByComparingTo("0.00");
        assertThat(issued.confirmations()).hasSize(2);
        assertThat(issued.confirmations().get(0).side()).isEqualTo("SELLER");
        assertThat(issued.confirmations().get(1).side()).isEqualTo("BUYER");

        InvoiceView view1 = invoiceService.getInvoice(inv1.invoiceNo());
        assertThat(view1.status()).isEqualTo("PARTIAL_RED");
        assertThat(view1.redFlushedAmount()).isEqualByComparingTo("130.00");
        assertThat(view1.redChain()).hasSize(1);
        InvoiceView view2 = invoiceService.getInvoice(inv2.invoiceNo());
        assertThat(view2.redFlushedAmount()).isEqualByComparingTo("60.00");

        // 额度勾稽：总额度 + Σ台账 = 当前可用额度
        var ledger = creditService.listLedger("S1");
        assertThat(ledger).hasSize(4);
        assertThat(ledger.get(2).reason()).isEqualTo("RED_CONFIRM");
        assertThat(ledger.get(2).changeAmount()).isEqualByComparingTo("130.00");
        assertThat(ledger.get(3).reason()).isEqualTo("RED_CONFIRM");
        assertThat(ledger.get(3).changeAmount()).isEqualByComparingTo("60.00");
        BigDecimal recomputed = new BigDecimal("1000.00")
                .add(ledger.stream().map(CreditService.LedgerEntry::changeAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(recomputed).isEqualByComparingTo(
                creditService.getCredit("S1").availableLimit());

        // 生效后重复查询稳定
        assertThat(batchService.getBatch(created.batchNo())).isEqualTo(issued);
    }

    @Test
    void 任一行不合格_整批422且不留下子单或占用() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("100.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 第二票行金额超可红冲余额：整批 422
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("50.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("150.00"))))))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("RED_FLUSH_EXCEEDS_LINE");
                    assertThat(((ApiException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        // 不留下子单、批记录或占用；失败不占幂等键（开票的两条幂等记录不受影响）
        assertThat(countRows("red_flush_request")).isEqualTo(0);
        assertThat(countRows("red_flush_batch")).isEqualTo(0);
        assertThat(countIdempotency("RB-1")).isEqualTo(0);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("800.00");

        // 修正金额后同一 requestId 可重新受理（失败未占键）
        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("50.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");
    }

    @Test
    void 在途争议行_整批422且不留下占用() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00")),
                        new LineRequest("货物B", new BigDecimal("100.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物C", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 第一票行 1 存在未结差异
        SubmitDiscrepancyResponse submitted = discrepancyService.submit(
                new SubmitDiscrepancyRequest("DIF-1", inv1.invoiceNo(), 1,
                        new BigDecimal("150.00"), "EV-1"));
        assertThat(submitted.result()).isEqualTo("SUBMITTED");

        // 批内包含争议行：整批 422；无争议行也不能随批受理
        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("50.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00"))))))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("LINE_DISPUTED");
                    assertThat(((ApiException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThat(countRows("red_flush_request")).isEqualTo(0);
        assertThat(countRows("red_flush_batch")).isEqualTo(0);

        // 无争议行单独走整批仍合法（争议只暂停对应行）
        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-2",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(2, new BigDecimal("50.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");
    }

    @Test
    void 结构校验_票数量行号与金额不合法返回400() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("100.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 少于 2 张票
        assertValidationFailed(() -> batchService.createBatch(new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                        List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))));
        // 批内票重复
        assertValidationFailed(() -> batchService.createBatch(new CreateBatchRequest("RB-2",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("10.00")))),
                        new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("20.00"))))))));
        // 金额非正数
        assertValidationFailed(() -> batchService.createBatch(new CreateBatchRequest("RB-3",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("0.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))));
        // 金额超过两位小数
        assertValidationFailed(() -> batchService.createBatch(new CreateBatchRequest("RB-4",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("1.001")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))));
        // 同票内行号重复
        assertValidationFailed(() -> batchService.createBatch(new CreateBatchRequest("RB-5",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("10.00")),
                                        new RedLineRequest(1, new BigDecimal("20.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))));
        assertThat(countRows("red_flush_batch")).isEqualTo(0);
    }

    @Test
    void 销购双方不一致_整批422() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("100.00"))));
        IssueResponse inv2 = invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                "REQ-2", "S1", "BUYER-2", false,
                List.of(new LineRequest("货物B", new BigDecimal("100.00")))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        assertThatThrownBy(() -> batchService.createBatch(new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("10.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("10.00"))))))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("BATCH_PARTY_MISMATCH");
                    assertThat(((ApiException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThat(countRows("red_flush_batch")).isEqualTo(0);
    }

    @Test
    void 批预占与普通红冲及差异登记共享行互斥() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 批预占第一票行 1 的 180，剩余可申请 20
        CreateBatchResponse created = batchService.createBatch(new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("180.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        assertThat(created.status()).isEqualTo("PENDING");

        // 普通红冲与批预占共享行余额：30 > 20，拒绝
        assertThatThrownBy(() -> redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", inv1.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("30.00"))))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("RED_FLUSH_EXCEEDS_LINE");

        // 差异登记与批预占互斥：该行红冲在途，差异拒绝并保留冲突记录
        SubmitDiscrepancyResponse conflicted = discrepancyService.submit(
                new SubmitDiscrepancyRequest("DIF-1", inv1.invoiceNo(), 1,
                        new BigDecimal("150.00"), "EV-1"));
        assertThat(conflicted.result()).isEqualTo("CONFLICT_REJECTED");
        assertThat(countRows("discrepancy_conflict")).isEqualTo(1);
        assertThat(countRows("discrepancy")).isEqualTo(0);
    }

    @Test
    void 并发受理_两批抢同一行余额合计不得超额() throws Exception {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("100.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 两批各自申请第一票行 1 的 80（余额 100），只有一批能成功
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Object> result1 = new AtomicReference<>();
        AtomicReference<Object> result2 = new AtomicReference<>();
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    result1.set(batchService.createBatch(new CreateBatchRequest("RB-A",
                            batchItems(inv1.invoiceNo(), inv2.invoiceNo(), "80.00"))));
                } catch (ApiException e) {
                    result1.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    result2.set(batchService.createBatch(new CreateBatchRequest("RB-B",
                            batchItems(inv1.invoiceNo(), inv2.invoiceNo(), "80.00"))));
                } catch (ApiException e) {
                    result2.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long successes = List.of(result1.get(), result2.get()).stream()
                .filter(r -> r instanceof CreateBatchResponse).count();
        long rejected = List.of(result1.get(), result2.get()).stream()
                .filter(r -> r instanceof ApiException)
                .peek(r -> {
                    assertThat(((ApiException) r).getCode()).isEqualTo("RED_FLUSH_EXCEEDS_LINE");
                    assertThat(((ApiException) r).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                })
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        // 合计预占不超余额：只有一批在途（80 + 60 = 140）
        BigDecimal pending = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_amount), 0) FROM red_flush_request"
                        + " WHERE status = 'PENDING'",
                BigDecimal.class);
        assertThat(pending).isEqualByComparingTo("140.00");
        assertThat(countRows("red_flush_batch")).isEqualTo(1);
    }

    @Test
    void 单方拒绝_整批终止并释放全部预占() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());

        // 购方拒绝：整批终止，释放全部预占
        BatchConfirmResponse rejected = batchService.confirm(created.batchNo(),
                new BatchConfirmRequest("CF-B", "BUYER", "REJECT", 1));
        assertThat(rejected.status()).isEqualTo("TERMINATED");
        assertThat(rejected.version()).isEqualTo(2);

        BatchView view = batchService.getBatch(created.batchNo());
        assertThat(view.items()).extracting(BatchItemView::status).containsOnly("CANCELLED");
        assertThat(view.credit().reservedAmount()).isEqualByComparingTo("0.00");
        // 终止不产生红票、不恢复额度
        assertThat(countRedInvoices()).isEqualTo(0);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        // 预占已释放：同一行可重新申请普通红冲
        CreateRedFlushResponse again = redFlushService.createRequest(new CreateRedFlushRequest(
                "RF-1", inv1.invoiceNo(), "PARTIAL",
                List.of(new RedLineRequest(1, new BigDecimal("80.00")))));
        assertThat(again.result()).isEqualTo("CREATED");

        // 终止后不再接受确认
        assertThatThrownBy(() -> batchService.confirm(created.batchNo(),
                new BatchConfirmRequest("CF-S", "SELLER", "AGREE", 2)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("BATCH_CLOSED");
    }

    @Test
    void 撤回_未生效可撤回且生效批不可撤回() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 未生效批可撤回：释放全部预占
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());
        BatchWithdrawResponse withdrawn = batchService.withdraw(created.batchNo(),
                new BatchWithdrawRequest("WD-1", 1));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.version()).isEqualTo(2);
        BatchView view = batchService.getBatch(created.batchNo());
        assertThat(view.items()).extracting(BatchItemView::status).containsOnly("CANCELLED");
        assertThat(view.credit().reservedAmount()).isEqualByComparingTo("0.00");
        assertThat(countRedInvoices()).isEqualTo(0);
        assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

        // 撤回后不可再撤回
        assertThatThrownBy(() -> batchService.withdraw(created.batchNo(),
                new BatchWithdrawRequest("WD-2", 2)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("BATCH_CLOSED");

        // 生效批不可撤回
        CreateBatchResponse created2 = batchService.createBatch(new CreateBatchRequest("RB-2",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("80.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00")))))));
        batchService.confirm(created2.batchNo(),
                new BatchConfirmRequest("CF-S2", "SELLER", "AGREE", 1));
        BatchConfirmResponse issued = batchService.confirm(created2.batchNo(),
                new BatchConfirmRequest("CF-B2", "BUYER", "AGREE", 2));
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThatThrownBy(() -> batchService.withdraw(created2.batchNo(),
                new BatchWithdrawRequest("WD-3", 3)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("BATCH_ALREADY_EFFECTIVE");
        assertThat(countRedInvoices()).isEqualTo(2);
    }

    @Test
    void 受控子单_普通确认入口拒绝() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());
        String childNo = created.items().get(0).requestNo();

        // 普通子单确认入口必须拒绝受控子单，不能绕过整批原子性
        assertThatThrownBy(() -> redFlushService.confirm(childNo,
                new ConfirmRequest("SELLER", "AGREE")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("BATCH_CONTROLLED");
        assertThatThrownBy(() -> redFlushService.confirm(childNo,
                new ConfirmRequest("BUYER", "AGREE")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("BATCH_CONTROLLED");
        // 子单未被单方确认推进：仍 PENDING，无红票
        assertThat(redFlushService.getRequest(childNo).status()).isEqualTo("PENDING");
        assertThat(countRedInvoices()).isEqualTo(0);
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
        // 销方先同意（版本 1 → 2）
        batchService.confirm(created.batchNo(),
                new BatchConfirmRequest("CF-S", "SELLER", "AGREE", 1));

        // 购方同意（生效）与撤回竞争：同一版本，只能一个成功
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<Object> confirmResult = new AtomicReference<>();
        AtomicReference<Object> withdrawResult = new AtomicReference<>();
        try {
            Future<?> f1 = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    confirmResult.set(batchService.confirm(created.batchNo(),
                            new BatchConfirmRequest("CF-B", "BUYER", "AGREE", 2)));
                } catch (ApiException e) {
                    confirmResult.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<?> f2 = pool.submit(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                    withdrawResult.set(batchService.withdraw(created.batchNo(),
                            new BatchWithdrawRequest("WD-1", 2)));
                } catch (ApiException e) {
                    withdrawResult.set(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            gate.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Object confirm = confirmResult.get();
        Object withdraw = withdrawResult.get();
        BatchView view = batchService.getBatch(created.batchNo());
        if (confirm instanceof BatchConfirmResponse confirmOk) {
            // 确认胜出：整批生效，撤回失败；重试确认重放原响应，不多开红票、不多回补额度
            assertThat(confirmOk.status()).isEqualTo("ISSUED");
            assertThat(withdraw).isInstanceOf(ApiException.class);
            assertThat(view.status()).isEqualTo("ISSUED");
            assertThat(countRedInvoices()).isEqualTo(2);
            assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("840.00");

            BatchConfirmResponse replay = batchService.confirm(created.batchNo(),
                    new BatchConfirmRequest("CF-B", "BUYER", "AGREE", 2));
            assertThat(replay.status()).isEqualTo("ISSUED");
            assertThat(countRedInvoices()).isEqualTo(2);
            assertThat(countLedgerReason("RED_CONFIRM")).isEqualTo(2);
            assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("840.00");
        } else {
            // 撤回胜出：整批撤回，确认失败；重试撤回重放原响应，确认重试仍失败且不开红票
            assertThat(confirm).isInstanceOf(ApiException.class);
            assertThat(withdraw).isInstanceOf(BatchWithdrawResponse.class);
            assertThat(view.status()).isEqualTo("WITHDRAWN");
            assertThat(view.items()).extracting(BatchItemView::status).containsOnly("CANCELLED");
            assertThat(countRedInvoices()).isEqualTo(0);
            assertThat(creditService.getCredit("S1").availableLimit()).isEqualByComparingTo("700.00");

            BatchWithdrawResponse replay = batchService.withdraw(created.batchNo(),
                    new BatchWithdrawRequest("WD-1", 2));
            assertThat(replay.status()).isEqualTo("WITHDRAWN");
            assertThatThrownBy(() -> batchService.confirm(created.batchNo(),
                    new BatchConfirmRequest("CF-B", "BUYER", "AGREE", 2)))
                    .isInstanceOf(ApiException.class);
            assertThat(countRedInvoices()).isEqualTo(0);
            assertThat(countLedgerReason("RED_CONFIRM")).isEqualTo(0);
        }
    }

    @Test
    void 版本过期409且失败不占键() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());
        CreateBatchResponse created = createTwoInvoiceBatch(inv1.invoiceNo(), inv2.invoiceNo());

        // 版本过期：409，且失败不占幂等键
        assertThatThrownBy(() -> batchService.confirm(created.batchNo(),
                new BatchConfirmRequest("CF-S", "SELLER", "AGREE", 5)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VERSION_CONFLICT");
        assertThat(countIdempotency("CF-S")).isEqualTo(0);
        assertThat(batchService.getBatch(created.batchNo()).version()).isEqualTo(1);

        // 同一 requestId 携带正确版本可重新执行
        BatchConfirmResponse ok = batchService.confirm(created.batchNo(),
                new BatchConfirmRequest("CF-S", "SELLER", "AGREE", 1));
        assertThat(ok.status()).isEqualTo("PENDING");
        assertThat(ok.version()).isEqualTo(2);

        // 撤回同样校验版本
        assertThatThrownBy(() -> batchService.withdraw(created.batchNo(),
                new BatchWithdrawRequest("WD-1", 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VERSION_CONFLICT");
        BatchWithdrawResponse withdrawn = batchService.withdraw(created.batchNo(),
                new BatchWithdrawRequest("WD-1", 2));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.version()).isEqualTo(3);
    }

    @Test
    void 幂等_同键同参重放首次结果_同键异参409() {
        creditService.createAccount(new CreateCreditAccountRequest("S1", new BigDecimal("1000.00")));
        IssueResponse inv1 = issue("S1", "REQ-1",
                List.of(new LineRequest("货物A", new BigDecimal("200.00"))));
        IssueResponse inv2 = issue("S1", "REQ-2",
                List.of(new LineRequest("货物B", new BigDecimal("100.00"))));
        invoiceService.deliver(inv1.invoiceNo());
        invoiceService.deliver(inv2.invoiceNo());

        // 受理幂等：同键同参重放，不重复建批
        CreateBatchRequest createRequest = new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("80.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00"))))));
        CreateBatchResponse first = batchService.createBatch(createRequest);
        CreateBatchResponse replay = batchService.createBatch(createRequest);
        assertThat(replay.batchNo()).isEqualTo(first.batchNo());
        assertThat(countRows("red_flush_batch")).isEqualTo(1);
        assertThat(countRows("red_flush_request")).isEqualTo(2);

        // 同键异参：409
        CreateBatchRequest tampered = new CreateBatchRequest("RB-1",
                List.of(new BatchItemRequest(inv1.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("90.00")))),
                        new BatchItemRequest(inv2.invoiceNo(),
                                List.of(new RedLineRequest(1, new BigDecimal("60.00"))))));
        assertThatThrownBy(() -> batchService.createBatch(tampered))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

        // 确认幂等：同键同参重放，不重复推进版本
        BatchConfirmRequest confirmRequest = new BatchConfirmRequest("CF-S", "SELLER", "AGREE", 1);
        BatchConfirmResponse confirmFirst = batchService.confirm(first.batchNo(), confirmRequest);
        BatchConfirmResponse confirmReplay = batchService.confirm(first.batchNo(), confirmRequest);
        assertThat(confirmReplay).isEqualTo(confirmFirst);
        assertThat(batchService.getBatch(first.batchNo()).version()).isEqualTo(2);
        assertThatThrownBy(() -> batchService.confirm(first.batchNo(),
                new BatchConfirmRequest("CF-S", "SELLER", "REJECT", 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    private IssueResponse issue(String sellerId, String requestId, List<LineRequest> lines) {
        return invoiceService.issueBlue(new IssueBlueInvoiceRequest(
                requestId, sellerId, "BUYER-1", false, lines));
    }

    /**
     * 两票整批：第一票行 1 红冲 80，第二票行 1 红冲 60。
     */
    private CreateBatchResponse createTwoInvoiceBatch(String inv1No, String inv2No) {
        return batchService.createBatch(new CreateBatchRequest("RB-1",
                batchItems(inv1No, inv2No, "80.00")));
    }

    private List<BatchItemRequest> batchItems(String inv1No, String inv2No, String firstAmount) {
        return List.of(new BatchItemRequest(inv1No,
                        List.of(new RedLineRequest(1, new BigDecimal(firstAmount)))),
                new BatchItemRequest(inv2No,
                        List.of(new RedLineRequest(1, new BigDecimal("60.00")))));
    }

    private void assertValidationFailed(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private int countIdempotency(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }

    private int countRedInvoices() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE kind = 'RED'", Integer.class);
        return count == null ? 0 : count;
    }

    private int countLedgerReason(String reason) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_ledger WHERE reason = ?", Integer.class, reason);
        return count == null ? 0 : count;
    }
}
