package com.example.starter.invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.invoice.service.CreditAccountService;
import com.example.starter.invoice.service.InvoiceService;
import com.example.starter.invoice.service.RedFlushService;
import com.example.starter.invoice.web.CreateCreditAccountRequest;
import com.example.starter.invoice.web.InvoiceLineRequest;
import com.example.starter.invoice.web.InvoiceResponse;
import com.example.starter.invoice.web.IssueInvoiceRequest;

/**
 * 集成测试基类：使用独立 H2 内存库，每个用例前清空全部业务表，避免顺序依赖。
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    protected static final String SELLER = "91310000TEST000001";
    protected static final String BUYER = "91310000TEST000002";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected InvoiceService invoiceService;

    @Autowired
    protected RedFlushService redFlushService;

    @Autowired
    protected CreditAccountService creditAccountService;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM red_confirmation");
        jdbc.update("DELETE FROM red_info_line");
        jdbc.update("DELETE FROM red_info_table");
        jdbc.update("DELETE FROM invoice_line");
        jdbc.update("DELETE FROM invoice");
        jdbc.update("DELETE FROM credit_ledger");
        jdbc.update("DELETE FROM credit_account");
    }

    /**
     * 创建授信账户。
     */
    protected void createAccount(String sellerTaxNo, String totalLimit) {
        creditAccountService.create(new CreateCreditAccountRequest(sellerTaxNo, new BigDecimal(totalLimit)));
    }

    /**
     * 构造开票请求：单行，金额＝数量×单价。
     */
    protected IssueInvoiceRequest issueRequest(String requestId, String sellerTaxNo, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new IssueInvoiceRequest(requestId, sellerTaxNo, BUYER, null, false,
                List.of(new InvoiceLineRequest("测试商品", BigDecimal.ONE, value, value)));
    }

    /**
     * 开票并交付，返回发票号码。
     */
    protected InvoiceResponse issueAndDeliver(String requestId, String sellerTaxNo, String amount) {
        InvoiceResponse invoice = invoiceService.issue(issueRequest(requestId, sellerTaxNo, amount));
        return invoiceService.deliver(invoice.invoiceNo());
    }

    /**
     * 唯一请求号。
     */
    protected String uniqueRequestId() {
        return "REQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
