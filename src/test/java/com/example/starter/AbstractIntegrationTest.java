package com.example.starter;

import com.example.starter.catalog.DocumentCatalog;
import com.example.starter.catalog.InspectionCatalog;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.InspectionInput;
import com.example.starter.dto.ReviewView;
import com.example.starter.dto.ServiceResult;
import com.example.starter.json.JsonSupport;
import com.example.starter.service.CustomsService;
import com.example.starter.service.ReviewService;
import com.example.starter.time.MutableBusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：每个用例后清表，保证场景间无顺序依赖。
 */
@SpringBootTest
@Import(H2ShutdownConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected ReviewService reviewService;
    @Autowired
    protected CustomsService customsService;
    @Autowired
    protected MutableBusinessClock clock;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JsonSupport jsonSupport;

    @AfterEach
    void cleanUp() {
        clock.reset();
        jdbc.update("DELETE FROM review_event");
        jdbc.update("DELETE FROM publicity_record");
        jdbc.update("DELETE FROM inspection_record");
        // 子批绑定外键引用审查流程与通关单，须先于两者清理。
        jdbc.update("DELETE FROM subbatch_binding");
        jdbc.update("DELETE FROM batch_review");
        jdbc.update("DELETE FROM customs_declaration");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM enterprise_product_status");
    }

    /** 合成目录全部 7 项资料。 */
    protected static List<String> allDocuments() {
        return DocumentCatalog.requiredDocuments();
    }

    /**
     * 构造全部 5 个检验项目的合成检验链。
     * 参数为关键项实测值，一般项给合格中间值。
     */
    protected static List<InspectionInput> inspections(String potency, String sterility, String toxicity) {
        return new ArrayList<>(List.of(
                new InspectionInput(InspectionCatalog.POTENCY, potency),
                new InspectionInput(InspectionCatalog.STERILITY, sterility),
                new InspectionInput(InspectionCatalog.ABNORMAL_TOXICITY, toxicity),
                new InspectionInput(InspectionCatalog.PH_VALUE, "7.5"),
                new InspectionInput(InspectionCatalog.MOISTURE, "2.0")));
    }

    /** 全项合格（关键项取恰等限值）的检验链。 */
    protected static List<InspectionInput> boundaryPassingInspections() {
        return inspections("4.0", "无菌", "0.10");
    }

    protected DeclareRequest declareRequest(String requestId, String batchNo,
                                            List<String> documents,
                                            List<InspectionInput> inspectionInputs) {
        return new DeclareRequest(requestId, "合成生物制品厂", "重组冠状病毒疫苗",
                batchNo, documents, inspectionInputs);
    }

    /** 将服务返回体（含幂等重放的 Map）统一转换为视图。 */
    protected ReviewView asView(ServiceResult result) {
        return jsonSupport.convertValue(result.body(), ReviewView.class) == null
                ? null
                : jsonSupport.convertValue(result.body(), ReviewView.class);
    }

    /** 进口合成通关场景默认企业。 */
    protected static final String IMPORT_ENTERPRISE = "合成生物制品厂";
    /** 进口合成通关场景默认品种。 */
    protected static final String IMPORT_PRODUCT = "重组冠状病毒疫苗";

    /** 构造与通关单默认企业品种一致的进口子批申报（资料齐全、检验合格）。 */
    protected DeclareRequest importDeclareRequest(String requestId, String batchNo) {
        return new DeclareRequest(requestId, IMPORT_ENTERPRISE, IMPORT_PRODUCT,
                batchNo, allDocuments(), boundaryPassingInspections());
    }

    /** 申报 → 评定 → 公示期满生效，把进口子批推进到可签发状态（签发时刻由时钟决定）。 */
    protected void prepareIssueableImportBatch(String declareRequestId, String batchNo) {
        reviewService.declare(importDeclareRequest(declareRequestId, batchNo));
        reviewService.evaluate(batchNo);
    }
}
