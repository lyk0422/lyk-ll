package com.example.starter.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.invoice.web.ApiException;
import com.example.starter.invoice.web.CreditAccountResponse;
import com.example.starter.invoice.web.InvoiceLineRequest;
import com.example.starter.invoice.web.InvoiceResponse;
import com.example.starter.invoice.web.IssueInvoiceRequest;

/**
 * 开票、交付、归档与额度占用测试。
 */
class InvoiceServiceTest extends BaseIntegrationTest {

    @Test
    void 开票成功后占用额度且状态为已开具() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = invoiceService.issue(issueRequest("REQ-ISSUE-1", SELLER, "600.00"));

        assertEquals("BLUE", invoice.direction());
        assertEquals("ISSUED", invoice.status());
        assertFalse(invoice.idempotentReplay());
        assertEquals(0, new BigDecimal("600.00").compareTo(invoice.totalAmount()));

        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, new BigDecimal("600.00").compareTo(account.blueUnarchivedAmount()));
        assertEquals(0, new BigDecimal("400.00").compareTo(account.availableAmount()));
    }

    @Test
    void 可用额度恰等于开票金额合法() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = invoiceService.issue(issueRequest("REQ-EXACT-1", SELLER, "1000.00"));
        assertEquals("ISSUED", invoice.status());

        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, BigDecimal.ZERO.compareTo(account.availableAmount()));
    }

    @Test
    void 超出可用额度拒绝且额度不变() {
        createAccount(SELLER, "1000.00");
        ApiException ex = assertThrows(ApiException.class,
                () -> invoiceService.issue(issueRequest("REQ-OVER-1", SELLER, "1000.01")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());

        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, new BigDecimal("1000.00").compareTo(account.availableAmount()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM invoice", Integer.class).intValue());
    }

    @Test
    void 同键同参返回首次结果() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse first = invoiceService.issue(issueRequest("REQ-IDEM-1", SELLER, "100.00"));
        InvoiceResponse second = invoiceService.issue(issueRequest("REQ-IDEM-1", SELLER, "100.00"));

        assertTrue(second.idempotentReplay());
        assertEquals(first.invoiceNo(), second.invoiceNo());
        // 额度只扣减一次
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, new BigDecimal("900.00").compareTo(account.availableAmount()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM invoice", Integer.class).intValue());
    }

    @Test
    void 同键异参返回409且额度不变() {
        createAccount(SELLER, "1000.00");
        invoiceService.issue(issueRequest("REQ-IDEM-2", SELLER, "100.00"));

        ApiException ex = assertThrows(ApiException.class,
                () -> invoiceService.issue(issueRequest("REQ-IDEM-2", SELLER, "200.00")));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("IDEMPOTENCY_CONFLICT", ex.code());

        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, new BigDecimal("900.00").compareTo(account.availableAmount()));
    }

    @Test
    void 交付后状态流转且重复交付幂等() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = invoiceService.issue(issueRequest("REQ-DLV-1", SELLER, "100.00"));

        InvoiceResponse delivered = invoiceService.deliver(invoice.invoiceNo());
        assertEquals("DELIVERED", delivered.status());

        InvoiceResponse again = invoiceService.deliver(invoice.invoiceNo());
        assertTrue(again.idempotentReplay());
        assertEquals("DELIVERED", again.status());
    }

    @Test
    void 归档为终态且重复归档幂等成功() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-ARC-1", SELLER, "300.00");

        InvoiceResponse archived = invoiceService.archive(invoice.invoiceNo());
        assertEquals("ARCHIVED", archived.status());
        // 归档后未归档蓝票占用释放
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, BigDecimal.ZERO.compareTo(account.blueUnarchivedAmount()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(account.availableAmount()));

        InvoiceResponse again = invoiceService.archive(invoice.invoiceNo());
        assertTrue(again.idempotentReplay());
        assertEquals("ARCHIVED", again.status());
    }

    @Test
    void 未交付不能归档() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = invoiceService.issue(issueRequest("REQ-ARC-2", SELLER, "100.00"));
        ApiException ex = assertThrows(ApiException.class,
                () -> invoiceService.archive(invoice.invoiceNo()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    @Test
    void 归档后禁止再次交付只能红冲() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-ARC-3", SELLER, "100.00");
        invoiceService.archive(invoice.invoiceNo());

        ApiException ex = assertThrows(ApiException.class,
                () -> invoiceService.deliver(invoice.invoiceNo()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    @Test
    void 行金额与数量单价不符拒绝() {
        createAccount(SELLER, "1000.00");
        IssueInvoiceRequest bad = new IssueInvoiceRequest("REQ-LINE-1", SELLER, BUYER, null, false,
                List.of(new InvoiceLineRequest("商品", new BigDecimal("2"),
                        new BigDecimal("50.00"), new BigDecimal("99.00"))));
        ApiException ex = assertThrows(ApiException.class, () -> invoiceService.issue(bad));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    @Test
    void 额度台账可复算() {
        createAccount(SELLER, "1000.00");
        InvoiceResponse invoice = issueAndDeliver("REQ-LED-1", SELLER, "300.00");
        invoiceService.archive(invoice.invoiceNo());

        List<BigDecimal> deltas = jdbc.queryForList(
                "SELECT delta_blue_unarchived FROM credit_ledger WHERE seller_tax_no = ? ORDER BY id",
                BigDecimal.class, SELLER);
        assertEquals(2, deltas.size());
        BigDecimal sum = deltas.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        CreditAccountResponse account = creditAccountService.getBySellerTaxNo(SELLER);
        assertEquals(0, sum.compareTo(account.blueUnarchivedAmount()));
    }
}
