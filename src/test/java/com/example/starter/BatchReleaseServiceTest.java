package com.example.starter;

import com.example.starter.repository.BatchReleaseRepository;
import com.example.starter.service.BatchReleaseService;
import com.example.starter.service.ServiceResponse;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.ApplicationRequest;
import com.example.starter.web.dto.CorrectionRequest;
import com.example.starter.web.dto.ObjectionRequest;
import com.example.starter.web.dto.SimpleRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批签发业务主流程与失败分支测试（H2 内存库，可控时钟）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BatchReleaseServiceTest {

    private static final String ENTERPRISE = "合成企业A";
    private static final String PRODUCT = "合成疫苗X";

    @Autowired
    private BatchReleaseService service;
    @Autowired
    private BatchReleaseRepository batchRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM late_document_event");
        jdbc.update("DELETE FROM enterprise_suspension");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM publicity_record");
        jdbc.update("DELETE FROM inspection_item");
        jdbc.update("DELETE FROM document_item");
        jdbc.update("DELETE FROM review_process");
        jdbc.update("DELETE FROM vaccine_batch");
        clock.set(TestClockConfig.BASE_TIME);
    }

    @Test
    void applyWithMissingDocumentsNotifiesAllMissingAtOnce() throws Exception {
        ApplicationRequest request = new ApplicationRequest("req-app-1", "B2026001", ENTERPRISE, PRODUCT,
                List.of(doc("LICENSE"), doc("QUALITY_STD"), doc("SELF_TEST_REPORT")),
                passingInspections());
        ServiceResponse response = service.apply(request);

        assertEquals(201, response.httpStatus());
        Map<String, Object> body = parse(response);
        assertEquals("PENDING_CORRECTION", body.get("status"));
        // 缺项一次性告知：两项缺失一次列全
        @SuppressWarnings("unchecked")
        List<Map<String, String>> missing = (List<Map<String, String>>) body.get("missing");
        assertEquals(2, missing.size());
        assertTrue(missing.stream().anyMatch(item -> item.get("itemCode").equals("PROCESS_RECORD")));
        assertTrue(missing.stream().anyMatch(item -> item.get("itemCode").equals("SUMMARY")));
        // 补正时限5个工作日：2026-09-26（周六）申报，第5个工作日为2026-10-02（周五）
        assertEquals("2026-10-02", body.get("correctionDeadline"));

        Map<String, Object> checklist = parse(service.checklist("B2026001"));
        @SuppressWarnings("unchecked")
        List<String> missingCodes = (List<String>) checklist.get("missing");
        assertEquals(2, missingCodes.size());
    }

    @Test
    void correctionOnExactFifthWorkingDayIsAccepted() throws Exception {
        service.apply(new ApplicationRequest("req-app-2", "B2026002", ENTERPRISE, PRODUCT,
                List.of(doc("LICENSE"), doc("QUALITY_STD"), doc("SELF_TEST_REPORT"), doc("PROCESS_RECORD")),
                passingInspections()));
        // 恰第5个工作日（2026-10-02）当日提交：受理
        clock.set(Instant.parse("2026-10-02T23:30:00Z"));
        ServiceResponse response = service.correct("B2026002",
                new CorrectionRequest("req-cor-2", List.of(doc("SUMMARY"))));

        assertEquals(200, response.httpStatus());
        assertEquals("UNDER_REVIEW", parse(response).get("status"));
        Map<String, Object> checklist = parse(service.checklist("B2026002"));
        assertEquals(List.of(), checklist.get("missing"));
    }

    @Test
    void correctionAfterDeadlineIsWithdrawn() throws Exception {
        service.apply(new ApplicationRequest("req-app-3", "B2026003", ENTERPRISE, PRODUCT,
                List.of(doc("LICENSE"), doc("QUALITY_STD"), doc("SELF_TEST_REPORT"), doc("PROCESS_RECORD")),
                passingInspections()));
        // 超过第5个工作日（2026-10-03 起）：逾期按撤回处理
        clock.set(Instant.parse("2026-10-03T00:00:01Z"));
        ServiceResponse response = service.correct("B2026003",
                new CorrectionRequest("req-cor-3", List.of(doc("SUMMARY"))));

        assertEquals(409, response.httpStatus());
        assertEquals("WITHDRAWN", parse(response).get("status"));
        assertEquals("WITHDRAWN",
                batchRepository.findProcessByBatchNo("B2026003").orElseThrow().status().name());
    }

    @Test
    void evaluationPassesWhenCriticalItemExactlyAtLimit() throws Exception {
        // 无菌项目测定值恰等限值：判合格
        service.apply(new ApplicationRequest("req-app-4", "B2026004", ENTERPRISE, PRODUCT, allDocs(),
                List.of(insp("POTENCY", "10", "10"), insp("STERILITY", "5", "5"),
                        insp("ABNORMAL_TOXICITY", "2", "2"))));
        ServiceResponse response = service.evaluate("B2026004", new SimpleRequest("req-eval-4"));

        assertEquals(200, response.httpStatus());
        Map<String, Object> body = parse(response);
        assertEquals("PASS", body.get("evaluationResult"));
        assertEquals("IN_PUBLICITY", body.get("status"));

        Map<String, Object> inspection = parse(service.inspection("B2026004"));
        assertEquals(Boolean.TRUE, inspection.get("allCriticalQualified"));
    }

    @Test
    void evaluationFailsWhenCriticalItemAboveLimit() throws Exception {
        service.apply(new ApplicationRequest("req-app-5", "B2026005", ENTERPRISE, PRODUCT, allDocs(),
                List.of(insp("POTENCY", "10", "11"), insp("STERILITY", "5", "5"),
                        insp("ABNORMAL_TOXICITY", "2", "1"))));
        ServiceResponse response = service.evaluate("B2026005", new SimpleRequest("req-eval-5"));

        Map<String, Object> body = parse(response);
        assertEquals("FAIL", body.get("evaluationResult"));
        assertEquals("EVALUATED_FAIL", body.get("status"));
        assertTrue(body.get("failReason").toString().contains("POTENCY"));
    }

    @Test
    void suspensionTriggeredByExactlyThreeConsecutiveFailures() {
        // 前两批不合格：不触发
        evaluateFailingBatch("B2026010", "req-a10");
        evaluateFailingBatch("B2026011", "req-a11");
        assertFalse(batchRepository.suspensionExists(ENTERPRISE, PRODUCT));

        // 恰连续第3批不合格：触发暂停受理并转核查
        evaluateFailingBatch("B2026012", "req-a12");
        assertTrue(batchRepository.suspensionExists(ENTERPRISE, PRODUCT));

        // 暂停后新申报被拒绝
        ApiException ex = assertThrows(ApiException.class, () -> service.apply(
                new ApplicationRequest("req-app-13", "B2026013", ENTERPRISE, PRODUCT, allDocs(), passingInspections())));
        assertEquals(422, ex.getStatus());
    }

    @Test
    void nonConsecutiveFailuresDoNotSuspend() {
        evaluateFailingBatch("B2026020", "req-a20");
        // 中间夹一批合格，连续性中断
        service.apply(new ApplicationRequest("req-app-21", "B2026021", ENTERPRISE, PRODUCT, allDocs(),
                passingInspections()));
        service.evaluate("B2026021", new SimpleRequest("req-eval-21"));
        evaluateFailingBatch("B2026022", "req-a22");
        evaluateFailingBatch("B2026023", "req-a23");

        assertFalse(batchRepository.suspensionExists(ENTERPRISE, PRODUCT));
    }

    @Test
    void issuanceEffectiveExactlyAtSeventhPublicityDay() throws Exception {
        applyAndEvaluatePass("B2026030", "req-a30");
        // 公示期内（未满7个自然日）：不得签发
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(7)).minusSeconds(1));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.issue("B2026030", new SimpleRequest("req-iss-30a")));
        assertEquals(409, ex.getStatus());

        // 恰第7日期满：生效可签发
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(7)));
        ServiceResponse response = service.issue("B2026030", new SimpleRequest("req-iss-30b"));
        assertEquals(200, response.httpStatus());
        assertEquals("ISSUED", parse(response).get("status"));

        Map<String, Object> publicity = parse(service.publicity("B2026030"));
        assertEquals("EFFECTIVE", publicity.get("publicityStatus"));
        assertEquals(Boolean.TRUE, publicity.get("expired"));
    }

    @Test
    void objectionDuringPublicityTransfersToReview() throws Exception {
        applyAndEvaluatePass("B2026040", "req-a40");
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(3)));
        ServiceResponse response = service.object("B2026040",
                new ObjectionRequest("req-obj-40", "合成异议：检验数据存疑"));
        assertEquals(200, response.httpStatus());
        assertEquals("OBJECTION_REVIEW", parse(response).get("status"));

        // 异议受理后即使公示期满也不得签发
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(7)));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.issue("B2026040", new SimpleRequest("req-iss-40")));
        assertEquals(409, ex.getStatus());

        Map<String, Object> publicity = parse(service.publicity("B2026040"));
        assertEquals("OBJECTION", publicity.get("publicityStatus"));
        assertEquals("合成异议：检验数据存疑", publicity.get("objectionReason"));
    }

    @Test
    void sameKeySameParamsReplaysFirstResult() throws Exception {
        ApplicationRequest request = new ApplicationRequest("req-app-50", "B2026050", ENTERPRISE, PRODUCT,
                allDocs(), passingInspections());
        ServiceResponse first = service.apply(request);
        ServiceResponse replay = service.apply(request);

        assertEquals(201, first.httpStatus());
        assertEquals(201, replay.httpStatus());
        assertEquals(first.jsonBody(), replay.jsonBody());
        assertEquals(1, countRows("review_process"));
    }

    @Test
    void sameKeyDifferentParamsReturns409AndKeepsState() {
        service.apply(new ApplicationRequest("req-app-51", "B2026051", ENTERPRISE, PRODUCT,
                allDocs(), passingInspections()));
        // 同键异参：409，且不改变审查状态
        ApiException ex = assertThrows(ApiException.class, () -> service.apply(
                new ApplicationRequest("req-app-51", "B2026099", ENTERPRISE, PRODUCT,
                        allDocs(), passingInspections())));
        assertEquals(409, ex.getStatus());
        assertEquals(1, countRows("review_process"));
        assertEquals("UNDER_REVIEW",
                batchRepository.findProcessByBatchNo("B2026051").orElseThrow().status().name());
    }

    @Test
    void duplicateBatchApplicationIsUniquelyAccepted() {
        service.apply(new ApplicationRequest("req-app-60", "B2026060", ENTERPRISE, PRODUCT,
                allDocs(), passingInspections()));
        ApiException ex = assertThrows(ApiException.class, () -> service.apply(
                new ApplicationRequest("req-app-61", "B2026060", ENTERPRISE, PRODUCT,
                        allDocs(), passingInspections())));
        assertEquals(409, ex.getStatus());
        assertEquals(1, countRows("review_process"));
    }

    @Test
    void archiveIsTerminalAndLateDocumentsOnlyRecorded() throws Exception {
        applyAndEvaluatePass("B2026070", "req-a70");
        clock.set(TestClockConfig.BASE_TIME.plus(Duration.ofDays(7)));
        service.issue("B2026070", new SimpleRequest("req-iss-70"));

        ServiceResponse archived = service.archive("B2026070", new SimpleRequest("req-arc-70a"));
        assertEquals(200, archived.httpStatus());
        assertEquals("ARCHIVED", parse(archived).get("status"));

        // 重复归档：幂等成功
        ServiceResponse again = service.archive("B2026070", new SimpleRequest("req-arc-70b"));
        assertEquals(200, again.httpStatus());
        assertEquals(Boolean.TRUE, parse(again).get("idempotent"));

        // 归档后迟到资料：只记迟到事件，状态不变
        ServiceResponse late = service.correct("B2026070",
                new CorrectionRequest("req-cor-70", List.of(doc("STABILITY"))));
        assertEquals(200, late.httpStatus());
        assertEquals(Boolean.TRUE, parse(late).get("lateEventRecorded"));
        assertEquals("ARCHIVED",
                batchRepository.findProcessByBatchNo("B2026070").orElseThrow().status().name());
        long batchId = batchRepository.findProcessByBatchNo("B2026070").orElseThrow().batchId();
        assertEquals(1, batchRepository.countLateEvents(batchId));
    }

    private void evaluateFailingBatch(String batchNo, String requestPrefix) {
        service.apply(new ApplicationRequest(requestPrefix + "-app", batchNo, ENTERPRISE, PRODUCT, allDocs(),
                List.of(insp("POTENCY", "10", "99"), insp("STERILITY", "5", "5"),
                        insp("ABNORMAL_TOXICITY", "2", "1"))));
        service.evaluate(batchNo, new SimpleRequest(requestPrefix + "-eval"));
    }

    private void applyAndEvaluatePass(String batchNo, String requestPrefix) {
        service.apply(new ApplicationRequest(requestPrefix + "-app", batchNo, ENTERPRISE, PRODUCT,
                allDocs(), passingInspections()));
        service.evaluate(batchNo, new SimpleRequest(requestPrefix + "-eval"));
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> parse(ServiceResponse response) throws Exception {
        return objectMapper.readValue(response.jsonBody(), new TypeReference<>() {
        });
    }

    private static ApplicationRequest.DocumentItem doc(String code) {
        return new ApplicationRequest.DocumentItem(code);
    }

    private static ApplicationRequest.InspectionItem insp(String code, String limit, String measured) {
        return new ApplicationRequest.InspectionItem(code, null, new BigDecimal(limit), new BigDecimal(measured));
    }

    private static List<ApplicationRequest.DocumentItem> allDocs() {
        return List.of(doc("LICENSE"), doc("QUALITY_STD"), doc("SELF_TEST_REPORT"),
                doc("PROCESS_RECORD"), doc("SUMMARY"));
    }

    private static List<ApplicationRequest.InspectionItem> passingInspections() {
        return List.of(insp("POTENCY", "10", "8"), insp("STERILITY", "5", "5"),
                insp("ABNORMAL_TOXICITY", "2", "1"));
    }
}
