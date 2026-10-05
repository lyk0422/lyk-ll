package com.example.starter.museum;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试基类：启动完整应用上下文（H2 内存库），每个用例前清理业务数据并重置时钟。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class MuseumTestSupport {

    /** 一级文物（纸质，种子数据）。 */
    protected static final long ARTIFACT_LEVEL_ONE = 1L;
    /** 二级文物（青铜，种子数据）。 */
    protected static final long ARTIFACT_LEVEL_TWO = 2L;
    /** 纸质展柜：温度 18-22℃，湿度 50-60%RH。 */
    protected static final long SHOWCASE_PAPER = 1L;
    /** 青铜展柜：温度 16-24℃，湿度 40-55%RH。 */
    protected static final long SHOWCASE_BRONZE = 2L;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected LoanService loanService;

    @Autowired
    protected ClimateService climateService;

    @Autowired
    protected ImageHandoverService imageHandoverService;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM late_reading_event");
        jdbc.update("DELETE FROM protection_order");
        jdbc.update("DELETE FROM climate_reading");
        jdbc.update("DELETE FROM resume_review");
        jdbc.update("DELETE FROM discrepancy_item");
        jdbc.update("DELETE FROM discrepancy_order");
        jdbc.update("DELETE FROM image_feature");
        jdbc.update("DELETE FROM handover");
        jdbc.update("DELETE FROM artifact_active_loan");
        jdbc.update("DELETE FROM loan_contract");
        jdbc.update("UPDATE showcase SET frozen = FALSE");
        clock.setInstant(TestClockConfig.BASE);
    }

    /** 生成唯一 requestId。 */
    protected String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** 申请并直接进入在展状态（双方交付点交确认）。 */
    protected long applyAndDisplay(long artifactId, long showcaseId,
            LocalDate start, LocalDate end) {
        Map<String, Object> contract = loanService.apply(newRequestId(), artifactId, showcaseId,
                "某省博物馆", start, end);
        long contractId = ((Number) contract.get("contractId")).longValue();
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_BORROWER);
        return contractId;
    }

    /** 构造合成影像特征条目。 */
    protected ImageHandoverService.ImageFeature feature(String code, String value) {
        return new ImageHandoverService.ImageFeature(code, value);
    }

    /** 登记出借点交影像清单（合同须在 APPLIED，登记后再完成交付点交）。 */
    protected long applyWithDeliveryImages(long artifactId, long showcaseId,
            LocalDate start, LocalDate end,
            java.util.List<ImageHandoverService.ImageFeature> deliveryFeatures) {
        Map<String, Object> contract = loanService.apply(newRequestId(), artifactId, showcaseId,
                "某省博物馆", start, end);
        long contractId = ((Number) contract.get("contractId")).longValue();
        imageHandoverService.registerFeatures(contractId, "DELIVERY", deliveryFeatures);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_MUSEUM);
        loanService.confirmHandover(contractId, "DELIVERY", LoanService.PARTY_BORROWER);
        return contractId;
    }

    /** 登记归还影像清单并双方确认归还点交，返回最后一次确认（生效）响应。 */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> registerReturnAndConfirm(long contractId,
            java.util.List<ImageHandoverService.ImageFeature> returnFeatures) {
        imageHandoverService.registerFeatures(contractId, "RETURN", returnFeatures);
        loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_MUSEUM);
        return loanService.confirmHandover(contractId, "RETURN", LoanService.PARTY_BORROWER);
    }

    /** 标准三件套合成影像基线（出借点交特征清单）。 */
    protected java.util.List<ImageHandoverService.ImageFeature> baselineFeatures() {
        return java.util.List.of(
                feature("FEATURE_SEAL", "seal-hash-A1"),
                feature("FEATURE_EDGE", "edge-intact-B2"),
                feature("FEATURE_PATCH", "patch-37-C3"));
    }

    /** 查询合同当前状态。 */
    protected String contractStatus(long contractId) {
        return jdbc.queryForObject("SELECT status FROM loan_contract WHERE id = ?", String.class, contractId);
    }

    /** 统计表行数。 */
    protected long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
