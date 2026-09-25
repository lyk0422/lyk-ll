package com.example.starter.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.invoice.web.ApiException;
import com.example.starter.invoice.web.ConfirmRequest;
import com.example.starter.invoice.web.CreateRedInfoRequest;
import com.example.starter.invoice.web.CreditAccountResponse;
import com.example.starter.invoice.web.InvoiceLineRequest;
import com.example.starter.invoice.web.InvoiceResponse;
import com.example.starter.invoice.web.IssueInvoiceRequest;
import com.example.starter.invoice.web.RedInfoLineRequest;
import com.example.starter.invoice.web.RedInfoResponse;

/**
 * 红冲流程测试：部分红冲累计上限、恰红至零、终态幂等、双方同事务确认、额度恢复原子性、挂起。
 */
class RedFlushServiceTest extends BaseIntegrationTest {

    /**
     * 开具两行蓝票并交付：行1＝600，行2＝400，合计 1000。
     */
    private InvoiceResponse issueTwoLineInvoice(String requestId) {
        IssueInvoiceRequest request = new IssueInvoiceRequest(requestId, SELLER, BUYER, null, false,
                List.of(new InvoiceLineRequest("商品A", BigDecimal.ONE, new BigDecimal("600.00"),
                                new BigDecimal("600.00")),
                        new InvoiceLineRequest("商品B", BigDecimal.ONE, new BigDecimal("400.00"),
                                new BigDecimal("400.00"))));
        InvoiceResponse invoice = invoiceService.issue(request);
        return invoiceService.deliver(invoice.invoiceNo());
    }

    private RedInfoResponse confirmBoth(String infoNo) {
        redFlushService.confirm(infoNo, new ConfirmRequest("SELLER", "CONFIRM", null));
        return redFlushService.confirm(infoNo, new ConfirmRequest("BUYER", "CONFIRM", null));
    }

    @Test
    void 单方确认不生效不开红票不恢复额度() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-RF-1", SELLER, "500.00");
        RedInfoResponse info = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-1", invoice.invoiceNo(), "FULL", null));

        RedInfoResponse afterSeller = redFlushService.confirm(info.infoNo(),
                new ConfirmRequest("SELLER", "CONFIRM", null));
        assertEquals("PENDING", afterSeller.status());
        assertTrue(afterSeller.sellerConfirmed());
        assertFalse(afterSeller.buyerConfirmed());
        assertNull(afterSeller.redInvoiceNo());

        // 单方确认不产生红票、不恢复额度
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE direction = 'RED'",
                Integer.class).intValue());
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, BigDecimal.ZERO.compareTo(account.redConfirmedAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo(account.availableAmount()));
    }

    @Test
    void 双方确认后同一事务开具红票并恢复额度() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-RF-2", SELLER, "500.00");
        RedInfoResponse info = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-2", invoice.invoiceNo(), "FULL", null));

        RedInfoResponse confirmed = confirmBoth(info.infoNo());
        assertEquals("CONFIRMED", confirmed.status());
        assertNotNull(confirmed.redInvoiceNo());

        // 红票引用原发票号码，红冲链完整
        InvoiceResponse red = invoiceService.getByInvoiceNo(confirmed.redInvoiceNo());
        assertEquals("RED", red.direction());
        assertEquals(invoice.invoiceNo(), red.originalInvoiceNo());
        InvoiceResponse blue = invoiceService.getByInvoiceNo(invoice.invoiceNo());
        assertEquals(List.of(confirmed.redInvoiceNo()), blue.redChain());
        assertEquals("RED_FLUSHED", blue.status());

        // 额度在同一事务内恢复：可用额度＝1000−500＋500
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, new BigDecimal("500.00").compareTo(account.redConfirmedAmount()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(account.availableAmount()));

        // 台账含 RED_CONFIRM 记录，可复算
        Integer redLedger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_ledger WHERE biz_type = 'RED_CONFIRM' AND biz_no = ?",
                Integer.class, confirmed.redInvoiceNo());
        assertEquals(1, redLedger.intValue());
    }

    @Test
    void 部分红冲按行累计且不得超过行余额() {
        createAccount(SELLER, "2000.00");
        InvoiceResponse invoice = issueTwoLineInvoice("REQ-RF-3");

        // 行1 可红冲余额 600，申请 600.01 拒绝
        ApiException ex = assertThrows(ApiException.class, () -> redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-3A", invoice.invoiceNo(), "PARTIAL",
                        List.of(new RedInfoLineRequest(1, new BigDecimal("600.01"))))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());

        // 第一次红冲行1的 500，合法
        RedInfoResponse first = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-3B", invoice.invoiceNo(), "PARTIAL",
                        List.of(new RedInfoLineRequest(1, new BigDecimal("500.00")))));
        confirmBoth(first.infoNo());

        // 行1 剩余 100，再红冲 200 拒绝（累计超限）
        ApiException ex2 = assertThrows(ApiException.class, () -> redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-3C", invoice.invoiceNo(), "PARTIAL",
                        List.of(new RedInfoLineRequest(1, new BigDecimal("200.00"))))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex2.status());

        // 行1 恰红剩余 100，累计 600＝行金额，合法
        RedInfoResponse second = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-3D", invoice.invoiceNo(), "PARTIAL",
                        List.of(new RedInfoLineRequest(1, new BigDecimal("100.00")))));
        confirmBoth(second.infoNo());

        InvoiceResponse after = invoiceService.getByInvoiceNo(invoice.invoiceNo());
        assertEquals(0, new BigDecimal("600.00").compareTo(after.redFlushedAmount()));
        assertEquals(0, new BigDecimal("400.00").compareTo(after.redRemainAmount()));
        // 未全额红冲，状态仍为 DELIVERED
        assertEquals("DELIVERED", after.status());
    }

    @Test
    void 恰红至余额为零合法且蓝票进入终态() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueTwoLineInvoice("REQ-RF-4");

        RedInfoResponse first = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-4A", invoice.invoiceNo(), "PARTIAL",
                        List.of(new RedInfoLineRequest(1, new BigDecimal("600.00")))));
        confirmBoth(first.infoNo());
        RedInfoResponse second = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-4B", invoice.invoiceNo(), "PARTIAL",
                        List.of(new RedInfoLineRequest(2, new BigDecimal("400.00")))));
        confirmBoth(second.infoNo());

        InvoiceResponse after = invoiceService.getByInvoiceNo(invoice.invoiceNo());
        assertEquals("RED_FLUSHED", after.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.redRemainAmount()));

        // 额度恢复：可用＝2000... 总额 1000 授信，蓝票 1000 全红 → 可用回到 1000
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, new BigDecimal("1000.00").compareTo(account.availableAmount()));
    }

    @Test
    void 已全额红冲的蓝票进一步红冲返回幂等结果不产生新红票() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-RF-5", SELLER, "500.00");
        RedInfoResponse info = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-5", invoice.invoiceNo(), "FULL", null));
        confirmBoth(info.infoNo());
        assertEquals("RED_FLUSHED", invoiceService.getByInvoiceNo(invoice.invoiceNo()).status());

        Integer redCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE direction = 'RED'", Integer.class);
        Integer infoCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM red_info_table", Integer.class);

        // 用新的 requestId 再次发起红冲：返回幂等结果，不产生新信息表与红票
        RedInfoResponse replay = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-5B", invoice.invoiceNo(), "FULL", null));
        assertTrue(replay.idempotentReplay());
        assertEquals(info.infoNo(), replay.infoNo());

        assertEquals(redCountBefore.intValue(), jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice WHERE direction = 'RED'", Integer.class).intValue());
        assertEquals(infoCountBefore.intValue(),
                jdbc.queryForObject("SELECT COUNT(*) FROM red_info_table", Integer.class).intValue());
    }

    @Test
    void 意见不一致挂起且不产生红票() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-RF-6", SELLER, "500.00");
        RedInfoResponse info = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-6", invoice.invoiceNo(), "FULL", null));

        redFlushService.confirm(info.infoNo(), new ConfirmRequest("SELLER", "CONFIRM", null));
        RedInfoResponse suspended = redFlushService.confirm(info.infoNo(),
                new ConfirmRequest("BUYER", "REJECT", "购方不同意"));
        assertEquals("SUSPENDED", suspended.status());
        assertNull(suspended.redInvoiceNo());

        // 挂起后不允许再确认
        ApiException ex = assertThrows(ApiException.class, () -> redFlushService.confirm(info.infoNo(),
                new ConfirmRequest("BUYER", "CONFIRM", null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE direction = 'RED'",
                Integer.class).intValue());
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, BigDecimal.ZERO.compareTo(account.redConfirmedAmount()));
    }

    @Test
    void 归档后允许红冲且红冲链保留() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-RF-7", SELLER, "500.00");
        invoiceService.archive(invoice.invoiceNo());

        RedInfoResponse info = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-7", invoice.invoiceNo(), "FULL", null));
        RedInfoResponse confirmed = confirmBoth(info.infoNo());
        assertEquals("CONFIRMED", confirmed.status());

        InvoiceResponse blue = invoiceService.getByInvoiceNo(invoice.invoiceNo());
        assertEquals("RED_FLUSHED", blue.status());
        assertEquals(List.of(confirmed.redInvoiceNo()), blue.redChain());

        // 归档蓝票已释放占用，红票确认后可用＝总额＋已确认红票
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, new BigDecimal("1500.00").compareTo(account.availableAmount()));
    }

    @Test
    void 红字信息表幂等同键同参与同键异参() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-RF-8", SELLER, "500.00");
        CreateRedInfoRequest request = new CreateRedInfoRequest("REQ-RI-8", invoice.invoiceNo(),
                "PARTIAL", List.of(new RedInfoLineRequest(1, new BigDecimal("100.00"))));

        RedInfoResponse first = redFlushService.createInfoTable(request);
        RedInfoResponse replay = redFlushService.createInfoTable(request);
        assertTrue(replay.idempotentReplay());
        assertEquals(first.infoNo(), replay.infoNo());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM red_info_table", Integer.class)
                .intValue());

        ApiException ex = assertThrows(ApiException.class, () -> redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-8", invoice.invoiceNo(), "PARTIAL",
                        List.of(new RedInfoLineRequest(1, new BigDecimal("200.00"))))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void 未交付的蓝票不允许红冲() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = invoiceService.issue(issueRequest("REQ-RF-9", SELLER, "500.00"));
        ApiException ex = assertThrows(ApiException.class, () -> redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-9", invoice.invoiceNo(), "FULL", null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    @Test
    void 已确认信息表重复确认返回幂等结果() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-RF-10", SELLER, "500.00");
        RedInfoResponse info = redFlushService.createInfoTable(
                new CreateRedInfoRequest("REQ-RI-10", invoice.invoiceNo(), "FULL", null));
        confirmBoth(info.infoNo());

        RedInfoResponse again = redFlushService.confirm(info.infoNo(),
                new ConfirmRequest("SELLER", "CONFIRM", null));
        assertTrue(again.idempotentReplay());
        assertEquals("CONFIRMED", again.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM invoice WHERE direction = 'RED'",
                Integer.class).intValue());
    }
}
