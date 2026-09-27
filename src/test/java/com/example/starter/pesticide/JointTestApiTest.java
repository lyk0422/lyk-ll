package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointTestCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeItem;
import com.example.starter.pesticide.dto.ApiDtos.JointTestJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitItem;
import com.example.starter.pesticide.dto.ApiDtos.JointTestSplitRequest;
import com.example.starter.pesticide.dto.ApiDtos.RecheckRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.SampleRequest;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 定量联检 API 测试：版本门控主流程、恰等限值合格、超限处置留样、待定、
 * 复测版本递增、判定后补充信息、分样覆盖性、幂等重放与异参 409、结构化指纹。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointTestApiTest {

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM joint_test_judgment");
        jdbc.update("DELETE FROM joint_test_result");
        jdbc.update("DELETE FROM joint_test_split");
        jdbc.update("DELETE FROM joint_test_item");
        jdbc.update("DELETE FROM joint_test_order");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM origin_alert");
        jdbc.update("DELETE FROM retention");
        jdbc.update("DELETE FROM disposal");
        jdbc.update("DELETE FROM admission");
        jdbc.update("DELETE FROM recheck");
        jdbc.update("DELETE FROM test_record");
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM batch");
        when(clock.instant()).thenReturn(T0);
    }

    @Test
    void 主流程_联检创建分样结果综合合格放行() throws Exception {
        long batchId = frozenBatch("J-1", "JT-1");
        long orderId = andReturnOrderId(createOrder("JO-1", batchId, "LAB-A",
                        List.of("有机磷", "克百威"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].limitValue").value(0.05))
                .andExpect(jsonPath("$.items[0].capabilityVersion").value(3)));
        split(orderId, new JointTestSplitRequest("JS-1", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-001"),
                        new JointTestSplitItem("克百威", "SN-002")), T0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("SPLIT"));
        // 恰等限值判合格
        result(orderId, new JointTestResultRequest("JR-1", 2, "有机磷",
                new BigDecimal("0.05"), T0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemQualified").value(true))
                .andExpect(jsonPath("$.itemVersion").value(1))
                .andExpect(jsonPath("$.orderVersion").value(3));
        result(orderId, new JointTestResultRequest("JR-2", 3, "克百威",
                new BigDecimal("0.01"), T0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemQualified").value(true))
                .andExpect(jsonPath("$.orderVersion").value(4));
        judge(orderId, new JointTestJudgeRequest("JJ-1", 4,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("QUALIFIED"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"))
                .andExpect(jsonPath("$.itemVersions", hasSize(2)));
        // 综合合格同事务更新原批准入状态
        Integer admissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission WHERE batch_id = ?", Integer.class, batchId);
        assertThat(admissions).isEqualTo(1);
        mvc.perform(get("/api/joint-tests/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JUDGED"))
                .andExpect(jsonPath("$.overall").value("QUALIFIED"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.judgmentItemVersions", hasSize(2)));
    }

    @Test
    void 超限综合不合格转处置并留样48小时() throws Exception {
        long batchId = frozenBatch("J-2", "JT-2");
        long orderId = createReadyOrder(batchId, "JO-2", "JS-2", "JR-2A", "JR-2B",
                "0.03", "0.0201");
        judge(orderId, new JointTestJudgeRequest("JJ-2", 4,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("UNQUALIFIED"))
                .andExpect(jsonPath("$.batchStatus").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));
        Integer disposals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposal WHERE batch_id = ?", Integer.class, batchId);
        assertThat(disposals).isEqualTo(1);
    }

    @Test
    void 尚缺一项只能待定且不落判定() throws Exception {
        long batchId = frozenBatch("J-3", "JT-3");
        long orderId = andReturnOrderId(createOrder("JO-3", batchId, "LAB-A",
                List.of("有机磷", "克百威")));
        split(orderId, new JointTestSplitRequest("JS-3", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-301"),
                        new JointTestSplitItem("克百威", "SN-302")), T0))
                .andExpect(status().isCreated());
        result(orderId, new JointTestResultRequest("JR-3", 2, "有机磷",
                new BigDecimal("0.01"), T0)).andExpect(status().isCreated());
        // 克百威结果缺失：只能待定，不推进版本、不落判定、不占键
        judge(orderId, new JointTestJudgeRequest("JJ-3", 3,
                List.of(new JointTestJudgeItem("有机磷", 1)), T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("PENDING"))
                .andExpect(jsonPath("$.judgmentId").doesNotExist())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.batchStatus").value("FROZEN"));
        Integer judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_judgment WHERE order_id = ?", Integer.class, orderId);
        assertThat(judgments).isEqualTo(0);
        // 同一 requestId 在结果补齐后可正常判定（待定未占键）
        result(orderId, new JointTestResultRequest("JR-3B", 3, "克百威",
                new BigDecimal("0.01"), T0)).andExpect(status().isCreated());
        judge(orderId, new JointTestJudgeRequest("JJ-3", 4,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("QUALIFIED"))
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
    }

    @Test
    void 复测替换当前结果保留旧结果并递增版本() throws Exception {
        long batchId = frozenBatch("J-4", "JT-4");
        long orderId = createReadyOrder(batchId, "JO-4", "JS-4", "JR-4A", "JR-4B",
                "0.09", "0.01");
        // 复测有机磷：0.09 超限 → 0.04 合格，项目版本 1 → 2，联检版本 4 → 5
        result(orderId, new JointTestResultRequest("JR-4C", 4, "有机磷",
                new BigDecimal("0.04"), T0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemQualified").value(true))
                .andExpect(jsonPath("$.itemVersion").value(2))
                .andExpect(jsonPath("$.orderVersion").value(5));
        // 旧结果保留且不再当前
        mvc.perform(get("/api/joint-tests/" + orderId))
                .andExpect(jsonPath("$.results", hasSize(3)))
                .andExpect(jsonPath("$.results[0].itemVersion").value(1))
                .andExpect(jsonPath("$.results[0].current").value(false))
                .andExpect(jsonPath("$.results[2].itemVersion").value(2))
                .andExpect(jsonPath("$.results[2].current").value(true));
        // 综合判定携带全部项版本
        judge(orderId, new JointTestJudgeRequest("JJ-4", 5,
                List.of(new JointTestJudgeItem("有机磷", 2),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("QUALIFIED"))
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
    }

    @Test
    void 综合判定后结果只记补充信息不重开() throws Exception {
        long batchId = frozenBatch("J-5", "JT-5");
        long orderId = createReadyOrder(batchId, "JO-5", "JS-5", "JR-5A", "JR-5B",
                "0.01", "0.01");
        judge(orderId, new JointTestJudgeRequest("JJ-5", 4,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("QUALIFIED"));
        // 判定后补充结果：只记录，不重开、不推进版本、批次状态不变
        result(orderId, new JointTestResultRequest("JR-5C", 5, "有机磷",
                new BigDecimal("0.09"), T0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplementary").value(true))
                .andExpect(jsonPath("$.orderVersion").value(5));
        mvc.perform(get("/api/joint-tests/" + orderId))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.overall").value("QUALIFIED"));
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        Integer judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_judgment WHERE order_id = ?", Integer.class, orderId);
        assertThat(judgments).isEqualTo(1);
        // 重复综合判定拒绝
        judge(orderId, new JointTestJudgeRequest("JJ-5B", 5,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 分样须覆盖全部项目任一项失败整单不分样() throws Exception {
        long batchId = frozenBatch("J-6", "JT-6");
        long orderId = andReturnOrderId(createOrder("JO-6", batchId, "LAB-A",
                List.of("有机磷", "克百威")));
        // 缺克百威：整单不分样
        split(orderId, new JointTestSplitRequest("JS-6", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-601")), T0))
                .andExpect(status().isUnprocessableEntity());
        Integer splits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_split WHERE order_id = ?", Integer.class, orderId);
        assertThat(splits).isEqualTo(0);
        // 失败不占键：同 requestId 修正参数后可成功
        split(orderId, new JointTestSplitRequest("JS-6", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-601"),
                        new JointTestSplitItem("克百威", "SN-602")), T0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        // 重复分样拒绝
        split(orderId, new JointTestSplitRequest("JS-6B", 2,
                List.of(new JointTestSplitItem("有机磷", "SN-603"),
                        new JointTestSplitItem("克百威", "SN-604")), T0))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 样本号全局唯一且请求内重复非法() throws Exception {
        long batchId1 = frozenBatch("J-7", "JT-7");
        long orderId1 = andReturnOrderId(createOrder("JO-7", batchId1, "LAB-A",
                List.of("有机磷", "克百威")));
        split(orderId1, new JointTestSplitRequest("JS-7", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-700"),
                        new JointTestSplitItem("克百威", "SN-701")), T0))
                .andExpect(status().isCreated());
        // 另一联检单占用相同样本号：422 且整单不分样
        long batchId2 = frozenBatch("J-7B", "JT-7B");
        long orderId2 = andReturnOrderId(createOrder("JO-7B", batchId2, "LAB-A",
                List.of("有机磷", "克百威")));
        split(orderId2, new JointTestSplitRequest("JS-7B", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-700"),
                        new JointTestSplitItem("克百威", "SN-702")), T0))
                .andExpect(status().isUnprocessableEntity());
        Integer splits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_split WHERE order_id = ?", Integer.class, orderId2);
        assertThat(splits).isEqualTo(0);
        // 请求内样本号重复：400
        split(orderId2, new JointTestSplitRequest("JS-7C", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-703"),
                        new JointTestSplitItem("克百威", "SN-703")), T0))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 创建校验_非冻结批次_目录缺失_能力缺失_重复项目_项目数不足() throws Exception {
        // 非疑似冻结批次：422
        long normalBatch = report("J-8A");
        createOrder("JO-8A", normalBatch, "LAB-A", List.of("有机磷", "克百威"))
                .andExpect(status().isUnprocessableEntity());
        long batchId = frozenBatch("J-8", "JT-8");
        // 合成目录无该项目：422
        createOrder("JO-8B", batchId, "LAB-A", List.of("有机磷", "未知项目"))
                .andExpect(status().isUnprocessableEntity());
        // 机构不具备项目能力：422（LAB-B 无毒死蜱能力）
        createOrder("JO-8C", batchId, "LAB-B", List.of("有机磷", "毒死蜱"))
                .andExpect(status().isUnprocessableEntity());
        // 项目重复：400
        createOrder("JO-8D", batchId, "LAB-A", List.of("有机磷", "有机磷"))
                .andExpect(status().isBadRequest());
        // 项目不足两种：400
        createOrder("JO-8E", batchId, "LAB-A", List.of("有机磷"))
                .andExpect(status().isBadRequest());
        // 失败不占键：修正参数后同键可成功
        createOrder("JO-8C", batchId, "LAB-A", List.of("有机磷", "克百威"))
                .andExpect(status().isCreated());
        // 同批次重复创建：422
        createOrder("JO-8F", batchId, "LAB-A", List.of("有机磷", "克百威"))
                .andExpect(status().isUnprocessableEntity());
        // 批次不存在：404
        createOrder("JO-8G", 999999L, "LAB-A", List.of("有机磷", "克百威"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 联检启用后旧单项目复检被拒绝_未启用不变() throws Exception {
        long batchId = frozenBatch("J-9", "JT-9");
        createOrder("JO-9", batchId, "LAB-A", List.of("有机磷", "克百威"))
                .andExpect(status().isCreated());
        postJson("/api/rechecks", new RecheckRequest("RC-9", batchId, "有机磷", true))
                .andExpect(status().isConflict());
        // 未启用联检的批次旧复检行为不变
        long oldBatch = frozenBatch("J-9B", "JT-9B");
        postJson("/api/rechecks", new RecheckRequest("RC-9B", oldBatch, "有机磷", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
    }

    @Test
    void 幂等_同键同参重放_异参409() throws Exception {
        long batchId = frozenBatch("J-10", "JT-10");
        var createReq = new JointTestCreateRequest("JO-10", batchId, "LAB-A",
                List.of("有机磷", "克百威"), T0);
        long orderId = andReturnOrderId(createOrder(createReq).andExpect(status().isCreated()));
        createOrder(createReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.replayed").value(true));
        createOrder(new JointTestCreateRequest("JO-10", batchId, "LAB-A",
                List.of("有机磷", "毒死蜱"), T0))
                .andExpect(status().isConflict());
        // 分样幂等
        var splitReq = new JointTestSplitRequest("JS-10", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-1001"),
                        new JointTestSplitItem("克百威", "SN-1002")), T0);
        split(orderId, splitReq).andExpect(status().isCreated());
        split(orderId, splitReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.version").value(2));
        split(orderId, new JointTestSplitRequest("JS-10", 1,
                List.of(new JointTestSplitItem("有机磷", "SN-1001"),
                        new JointTestSplitItem("克百威", "SN-1099")), T0))
                .andExpect(status().isConflict());
        // 结果幂等
        var resultReq = new JointTestResultRequest("JR-10", 2, "有机磷",
                new BigDecimal("0.01"), T0);
        result(orderId, resultReq).andExpect(status().isCreated());
        result(orderId, resultReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.orderVersion").value(3));
        result(orderId, new JointTestResultRequest("JR-10", 2, "有机磷",
                new BigDecimal("0.02"), T0))
                .andExpect(status().isConflict());
        // 判定幂等（含精确时刻入指纹）
        result(orderId, new JointTestResultRequest("JR-10B", 3, "克百威",
                new BigDecimal("0.01"), T0)).andExpect(status().isCreated());
        var judgeReq = new JointTestJudgeRequest("JJ-10", 4,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)), T0);
        judge(orderId, judgeReq).andExpect(status().isOk());
        judge(orderId, judgeReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.overall").value("QUALIFIED"));
        judge(orderId, new JointTestJudgeRequest("JJ-10", 4,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)),
                Instant.parse("2026-09-26T09:00:00Z")))
                .andExpect(status().isConflict());
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM joint_test_order", Integer.class);
        assertThat(orders).isEqualTo(1);
        Integer judgments = jdbc.queryForObject("SELECT COUNT(*) FROM joint_test_judgment",
                Integer.class);
        assertThat(judgments).isEqualTo(1);
    }

    @Test
    void 结构化指纹_合法文本含分隔符仍可区分() throws Exception {
        long batchId = frozenBatch("J-11", "JT-11");
        long orderId = andReturnOrderId(createOrder("JO-11", batchId, "LAB-A",
                List.of("有机磷", "克百威")));
        // 样本号含分隔符：朴素拼接会与另一组参数混淆，结构化指纹须判为异参 409
        split(orderId, new JointTestSplitRequest("JS-11", 1,
                List.of(new JointTestSplitItem("有机磷", "A|B"),
                        new JointTestSplitItem("克百威", "C")), T0))
                .andExpect(status().isCreated());
        split(orderId, new JointTestSplitRequest("JS-11", 1,
                List.of(new JointTestSplitItem("有机磷", "A"),
                        new JointTestSplitItem("克百威", "B|C")), T0))
                .andExpect(status().isConflict());
    }

    @Test
    void 版本门控_期望版本失配409_判定项目版本失配零写入() throws Exception {
        long batchId = frozenBatch("J-12", "JT-12");
        long orderId = createReadyOrder(batchId, "JO-12", "JS-12", "JR-12A", "JR-12B",
                "0.01", "0.01");
        // 联检单版本失配：409
        result(orderId, new JointTestResultRequest("JR-12C", 2, "有机磷",
                new BigDecimal("0.02"), T0))
                .andExpect(status().isConflict());
        // 复测推进版本与项目版本
        result(orderId, new JointTestResultRequest("JR-12D", 4, "有机磷",
                new BigDecimal("0.02"), T0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemVersion").value(2));
        // 判定携带过期项目版本：409 零写入
        judge(orderId, new JointTestJudgeRequest("JJ-12", 5,
                List.of(new JointTestJudgeItem("有机磷", 1),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isConflict());
        Integer judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_test_judgment WHERE order_id = ?", Integer.class, orderId);
        assertThat(judgments).isEqualTo(0);
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("FROZEN"));
        // 携带当前版本判定成功
        judge(orderId, new JointTestJudgeRequest("JJ-12B", 5,
                List.of(new JointTestJudgeItem("有机磷", 2),
                        new JointTestJudgeItem("克百威", 1)), T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("QUALIFIED"));
    }

    @Test
    void 结果前置校验_未分样_浓度非法_对象不存在() throws Exception {
        long batchId = frozenBatch("J-13", "JT-13");
        long orderId = andReturnOrderId(createOrder("JO-13", batchId, "LAB-A",
                List.of("有机磷", "克百威")));
        // 未分样不得录入结果：422
        result(orderId, new JointTestResultRequest("JR-13", 1, "有机磷",
                new BigDecimal("0.01"), T0))
                .andExpect(status().isUnprocessableEntity());
        // 浓度为负：400
        result(orderId, new JointTestResultRequest("JR-13B", 1, "有机磷",
                new BigDecimal("-0.01"), T0))
                .andExpect(status().isBadRequest());
        // 联检单不存在：404
        result(999999L, new JointTestResultRequest("JR-13C", 1, "有机磷",
                new BigDecimal("0.01"), T0))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/joint-tests/999999"))
                .andExpect(status().isNotFound());
    }

    private long frozenBatch(String reportReqId, String testReqId) throws Exception {
        long batchId = report(reportReqId);
        postJson("/api/batches/" + batchId + "/samples", new SampleRequest("有机磷"))
                .andExpect(status().isCreated());
        postJson("/api/tests", new TestRequest(testReqId, batchId, "有机磷", new BigDecimal("60")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("FROZEN"));
        return batchId;
    }

    /**
     * 创建并完成分样、两项结果的联检单（当前版本 4，两项结果版本均为 1）。
     */
    private long createReadyOrder(long batchId, String createReqId, String splitReqId,
                                  String resultReqId1, String resultReqId2,
                                  String concentration1, String concentration2) throws Exception {
        long orderId = andReturnOrderId(createOrder(createReqId, batchId, "LAB-A",
                        List.of("有机磷", "克百威"))
                .andExpect(status().isCreated()));
        split(orderId, new JointTestSplitRequest(splitReqId, 1,
                List.of(new JointTestSplitItem("有机磷", "SN-" + splitReqId + "-1"),
                        new JointTestSplitItem("克百威", "SN-" + splitReqId + "-2")), T0))
                .andExpect(status().isCreated());
        result(orderId, new JointTestResultRequest(resultReqId1, 2, "有机磷",
                new BigDecimal(concentration1), T0)).andExpect(status().isCreated());
        result(orderId, new JointTestResultRequest(resultReqId2, 3, "克百威",
                new BigDecimal(concentration2), T0)).andExpect(status().isCreated());
        return orderId;
    }

    private long report(String requestId) throws Exception {
        var result = postJson("/api/reports",
                new ReportRequest(requestId, "山东寿光", "番茄", "B-" + requestId, new BigDecimal("3")))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("batchId").asLong();
    }

    private ResultActions createOrder(String requestId, long batchId, String agency,
                                      List<String> items) throws Exception {
        return createOrder(new JointTestCreateRequest(requestId, batchId, agency, items, T0));
    }

    private ResultActions createOrder(JointTestCreateRequest request) throws Exception {
        return postJson("/api/joint-tests", request);
    }

    private ResultActions split(long orderId, JointTestSplitRequest request) throws Exception {
        return postJson("/api/joint-tests/" + orderId + "/splits", request);
    }

    private ResultActions result(long orderId, JointTestResultRequest request) throws Exception {
        return postJson("/api/joint-tests/" + orderId + "/results", request);
    }

    private ResultActions judge(long orderId, JointTestJudgeRequest request) throws Exception {
        return postJson("/api/joint-tests/" + orderId + "/judgment", request);
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(om.writeValueAsString(body));
        } else {
            request.content("{}");
        }
        return mvc.perform(request);
    }

    /**
     * 从创建响应中提取联检单 ID。
     */
    private long extractOrderId(ResultActions actions) throws Exception {
        return om.readTree(actions.andReturn().getResponse().getContentAsString())
                .get("orderId").asLong();
    }

    /**
     * ResultActions 后置提取联检单 ID 的辅助。
     */
    private long andReturnOrderId(ResultActions actions) throws Exception {
        return extractOrderId(actions);
    }
}
