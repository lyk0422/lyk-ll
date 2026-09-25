package com.example.starter.slaughter;

import com.example.starter.slaughter.dto.CheckoutRequest;
import com.example.starter.slaughter.dto.DeclareRequest;
import com.example.starter.slaughter.dto.InspectionCompleteRequest;
import com.example.starter.slaughter.dto.InspectionItem;
import com.example.starter.slaughter.dto.InspectionRegisterRequest;
import com.example.starter.slaughter.dto.InspectionResponse;
import com.example.starter.slaughter.dto.JudgmentItem;
import com.example.starter.slaughter.dto.JudgmentRequest;
import com.example.starter.slaughter.dto.PreQuarantineRequest;
import com.example.starter.slaughter.dto.ReceiptRequest;
import com.example.starter.slaughter.domain.InspectionResult;
import com.example.starter.slaughter.domain.JudgmentCategory;
import com.example.starter.slaughter.domain.PreQuarantineResult;
import com.example.starter.slaughter.service.SlaughterService;
import com.example.starter.slaughter.testsupport.MutableClock;
import com.example.starter.slaughter.testsupport.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 屠宰检疫测试基类：每个场景前清空 H2 表并重置可控时钟，避免顺序依赖。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfiguration.class})
public abstract class AbstractSlaughterTest {

    protected static final List<String> TABLES = List.of(
            "idempotent_request",
            "late_receipt_event",
            "inventory_mark",
            "receipt_head",
            "disposal_receipt",
            "batch_head",
            "inspection_chain",
            "slaughter_batch");

    @Autowired
    protected SlaughterService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.setInstant(TestClockConfiguration.START);
    }

    /** 申报 + 宰前检疫合格。 */
    protected void declareAndPass(String requestIdPrefix, String batchNo, List<String> heads) {
        service.declare(new DeclareRequest(requestIdPrefix + "-declare", batchNo, heads));
        service.preQuarantine(batchNo,
                new PreQuarantineRequest(requestIdPrefix + "-pq", PreQuarantineResult.PASS));
    }

    /** 全头只 NORMAL 同步检验并收口。 */
    protected InspectionResponse inspectAllAndComplete(String requestIdPrefix, String batchNo,
                                                       List<String> heads) {
        service.registerInspection(batchNo, new InspectionRegisterRequest(
                requestIdPrefix + "-insp",
                heads.stream().map(h -> new InspectionItem(h, InspectionResult.NORMAL)).toList()));
        return service.completeInspection(batchNo,
                new InspectionCompleteRequest(requestIdPrefix + "-complete")).data();
    }

    protected JudgmentItem judge(String headNo, JudgmentCategory category) {
        return new JudgmentItem(headNo, category, null);
    }

    protected JudgmentItem conditional(String headNo, String region) {
        return new JudgmentItem(headNo, JudgmentCategory.CONDITIONAL, region);
    }

    protected JudgmentRequest judgment(String requestId, List<JudgmentItem> items) {
        return new JudgmentRequest(requestId, items);
    }

    protected ReceiptRequest receipt(String requestId, String receiptNo, List<String> heads) {
        return new ReceiptRequest(requestId, receiptNo, heads);
    }

    protected CheckoutRequest checkout(String requestId) {
        return new CheckoutRequest(requestId);
    }
}
