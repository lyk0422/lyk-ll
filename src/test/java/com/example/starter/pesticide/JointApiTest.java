package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.RecheckRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.SampleRequest;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
import com.example.starter.pesticide.dto.JointDtos.AssignmentItem;
import com.example.starter.pesticide.dto.JointDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.JointDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.JointDtos.JointDetermineRequest;
import com.example.starter.pesticide.dto.JointDtos.JointResultRequest;
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
 * 联检 API 主流程与边界测试：建单冻结目录与能力版本、分样全覆盖、恰等限值合格、
 * 任一超限整体不合格、缺项只能待定、复测保留旧版本、判定后只记补充、
 * 幂等重放与版本失配、旧复检行为不变。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointApiTest {

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-09-26T09:00:00Z");

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
        jdbc.update("DELETE FROM joint_determination_item");
        jdbc.update("DELETE FROM joint_determination");
        jdbc.update("DELETE FROM joint_result");
        jdbc.update("DELETE FROM joint_assignment");
        jdbc.update("DELETE FROM joint_order_item");
        jdbc.update("DELETE FROM joint_order");
        jdbc.update("DELETE FROM joint_request_log");
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
    void 主流程_联检恰等限值合格_综合判定放行() throws Exception {
        // 合成目录可查
        mvc.perform(get("/api/joint/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityDirectoryVersion").value("CAPDIR-2026-01"))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.agencies", hasSize(2)));

        long[] frozen = frozenSuspectBatch("01");
        long batchId = frozen[0];
        long testId = frozen[1];

        // 建单：冻结逐项限值、目录版本与能力目录版本
        long orderId = om.readTree(postJson("/api/joint/orders",
                        new JointCreateRequest("JC-01", batchId, testId, List.of("毒死蜱", "克百威")))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("CREATED"))
                        .andExpect(jsonPath("$.version").value(1))
                        .andExpect(jsonPath("$.capabilityVersion").value("CAPDIR-2026-01"))
                        .andExpect(jsonPath("$.sourceTestId").value(testId))
                        .andExpect(jsonPath("$.items", hasSize(2)))
                        .andExpect(jsonPath("$.items[0].limitValue").value(0.05))
                        .andExpect(jsonPath("$.items[0].catalogVersion").value("CAT-2026-01"))
                        .andReturn().getResponse().getContentAsString())
                .get("orderId").asLong();

        // 分样：覆盖全部项目
        postJson("/api/joint/assignments", new JointAssignRequest("JA-01", orderId, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-001", "AG-A"),
                                new AssignmentItem("克百威", "S-002", "AG-A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.assignments[0].agencyCapabilityVersion").value("CAP-A-2026-01"));

        // 毒死蜱浓度恰等限值 0.05 → 合格
        postJson("/api/joint/results", new JointResultRequest("JR-01", orderId, 2L,
                        "毒死蜱", "S-001", new BigDecimal("0.05"), OCCURRED, false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualified").value(true))
                .andExpect(jsonPath("$.resultVersion").value(1))
                .andExpect(jsonPath("$.orderVersion").value(2));
        // 克百威低于限值 → 合格
        postJson("/api/joint/results", new JointResultRequest("JR-02", orderId, 3L,
                        "克百威", "S-002", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualified").value(true));

        // 综合判定：合格，同事务放行
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-01", orderId, 4L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallResult").value("PASS"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].resultVersion").value(1))
                .andExpect(jsonPath("$.retentionUntil").doesNotExist());

        // 批次已放行且准入记录唯一
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        Integer admissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission WHERE batch_id = ?", Integer.class, batchId);
        assertThat(admissions).isEqualTo(1);

        // 联检单详情：判定证据冻结
        mvc.perform(get("/api/joint/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DETERMINED"))
                .andExpect(jsonPath("$.overallResult").value("PASS"))
                .andExpect(jsonPath("$.currentResults", hasSize(2)))
                .andExpect(jsonPath("$.determination", hasSize(2)));
    }

    @Test
    void 任一超限整体不合格_转处置并留样48小时() throws Exception {
        long[] frozen = frozenSuspectBatch("02");
        long batchId = frozen[0];
        long orderId = createOrder("JC-02", batchId, frozen[1], "毒死蜱", "克百威");
        assign(orderId, "JA-02", 1L, "毒死蜱", "S-101", "AG-A", "克百威", "S-102", "AG-A");
        // 毒死蜱超限
        postJson("/api/joint/results", new JointResultRequest("JR-11", orderId, 2L,
                        "毒死蜱", "S-101", new BigDecimal("0.06"), OCCURRED, false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualified").value(false));
        postJson("/api/joint/results", new JointResultRequest("JR-12", orderId, 3L,
                        "克百威", "S-102", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated());

        postJson("/api/joint/determinations", new JointDetermineRequest("JD-02", orderId, 4L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallResult").value("FAIL"))
                .andExpect(jsonPath("$.batchStatus").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));

        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));
        // 不合格未产生放行记录
        Integer admissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission WHERE batch_id = ?", Integer.class, batchId);
        assertThat(admissions).isZero();
        Integer disposals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposal WHERE batch_id = ?", Integer.class, batchId);
        assertThat(disposals).isEqualTo(1);
    }

    @Test
    void 尚缺项目结果只能待定_不得判定() throws Exception {
        long[] frozen = frozenSuspectBatch("03");
        long orderId = createOrder("JC-03", frozen[0], frozen[1], "毒死蜱", "克百威");
        assign(orderId, "JA-03", 1L, "毒死蜱", "S-201", "AG-A", "克百威", "S-202", "AG-A");
        postJson("/api/joint/results", new JointResultRequest("JR-21", orderId, 2L,
                        "毒死蜱", "S-201", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated());
        // 克百威结果缺失：只能待定，判定 422 且零写入
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-03", orderId, 3L))
                .andExpect(status().isUnprocessableEntity());
        Integer determinations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_determination", Integer.class);
        assertThat(determinations).isZero();
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, orderId);
        assertThat(version).isEqualTo(3);
    }

    @Test
    void 复测替换当前结果_保留旧版本_判定携带全部项版本() throws Exception {
        long[] frozen = frozenSuspectBatch("04");
        long orderId = createOrder("JC-04", frozen[0], frozen[1], "毒死蜱", "克百威");
        assign(orderId, "JA-04", 1L, "毒死蜱", "S-301", "AG-A", "克百威", "S-302", "AG-A");
        // 首次超限
        postJson("/api/joint/results", new JointResultRequest("JR-31", orderId, 2L,
                        "毒死蜱", "S-301", new BigDecimal("0.09"), OCCURRED, false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualified").value(false))
                .andExpect(jsonPath("$.resultVersion").value(1));
        // 复测合格：版本递增
        postJson("/api/joint/results", new JointResultRequest("JR-32", orderId, 3L,
                        "毒死蜱", "S-301", new BigDecimal("0.03"), OCCURRED, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qualified").value(true))
                .andExpect(jsonPath("$.resultVersion").value(2));
        postJson("/api/joint/results", new JointResultRequest("JR-33", orderId, 4L,
                        "克百威", "S-302", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated());
        // 旧结果保留
        Integer resultRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE order_id = ? AND item = '毒死蜱'",
                Integer.class, orderId);
        assertThat(resultRows).isEqualTo(2);
        // 判定证据携带复测后的版本 2（证据按建单项目顺序冻结，首项为毒死蜱）
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-04", orderId, 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallResult").value("PASS"))
                .andExpect(jsonPath("$.items[0].item").value("毒死蜱"))
                .andExpect(jsonPath("$.items[0].resultVersion").value(2));
    }

    @Test
    void 综合判定后结果只记补充信息不重开() throws Exception {
        long[] frozen = frozenSuspectBatch("05");
        long batchId = frozen[0];
        long orderId = createOrder("JC-05", batchId, frozen[1], "毒死蜱", "克百威");
        assign(orderId, "JA-05", 1L, "毒死蜱", "S-401", "AG-A", "克百威", "S-402", "AG-A");
        postJson("/api/joint/results", new JointResultRequest("JR-41", orderId, 2L,
                        "毒死蜱", "S-401", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated());
        postJson("/api/joint/results", new JointResultRequest("JR-42", orderId, 3L,
                        "克百威", "S-402", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated());
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-05", orderId, 4L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallResult").value("PASS"));
        // 判定后补录结果：只记补充信息，不重开、不改版本
        postJson("/api/joint/results", new JointResultRequest("JR-43", orderId, 5L,
                        "毒死蜱", "S-401", new BigDecimal("0.09"), OCCURRED, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplementary").value(true));
        Integer resultRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE order_id = ?", Integer.class, orderId);
        assertThat(resultRows).isEqualTo(2);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, orderId);
        assertThat(version).isEqualTo(5);
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE batch_id = ? AND event_type = 'LATE_JOINT_RESULT'",
                Integer.class, batchId);
        assertThat(lateEvents).isEqualTo(1);
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void 已建联检单批次单项目复检拒绝_未启用联检旧复检不变() throws Exception {
        // 启用联检的批次：旧单项目复检不得放行
        long[] frozen = frozenSuspectBatch("06");
        createOrder("JC-06", frozen[0], frozen[1], "毒死蜱", "克百威");
        postJson("/api/rechecks", new RecheckRequest("RC-06", frozen[0], "有机磷", true))
                .andExpect(status().isConflict());
        // 未启用联检的批次：旧复检行为不变
        long[] plain = frozenSuspectBatch("06B");
        postJson("/api/rechecks", new RecheckRequest("RC-06B", plain[0], "有机磷", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
    }

    @Test
    void 建单幂等_同键重放异参409_失败不占键() throws Exception {
        long[] frozen = frozenSuspectBatch("07");
        long batchId = frozen[0];
        long testId = frozen[1];
        JointCreateRequest req = new JointCreateRequest("JC-07", batchId, testId,
                List.of("毒死蜱", "克百威"));
        long orderId = om.readTree(postJson("/api/joint/orders", req)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("orderId").asLong();
        // 同键同参：重放首次冻结结果（版本仍为 1）
        postJson("/api/joint/orders", req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参：409
        postJson("/api/joint/orders", new JointCreateRequest("JC-07", batchId, testId,
                        List.of("毒死蜱", "多菌灵")))
                .andExpect(status().isConflict());
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(orders).isEqualTo(1);

        // 失败不占键：非冻结批次建单 422 后，同键可用于合法请求
        long reportedBatch = om.readTree(postJson("/api/reports",
                        new ReportRequest("JR-07B", "山东寿光", "黄瓜", "JB-07B", new BigDecimal("1")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("batchId").asLong();
        postJson("/api/joint/orders", new JointCreateRequest("JC-07F", reportedBatch, testId,
                        List.of("毒死蜱", "克百威")))
                .andExpect(status().isUnprocessableEntity());
        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_request_log WHERE request_id = 'JC-07F'", Integer.class);
        assertThat(logs).isZero();
        // 同键修正参数后成功（批次冻结后）
        sample(reportedBatch, "有机磷");
        long newTestId = om.readTree(postJson("/api/tests",
                        new TestRequest("JT-07B", reportedBatch, "有机磷", new BigDecimal("70")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("testId").asLong();
        postJson("/api/joint/orders", new JointCreateRequest("JC-07F", reportedBatch, newTestId,
                        List.of("毒死蜱", "克百威")))
                .andExpect(status().isCreated());
    }

    @Test
    void 结果与判定幂等重放() throws Exception {
        long[] frozen = frozenSuspectBatch("08");
        long orderId = createOrder("JC-08", frozen[0], frozen[1], "毒死蜱", "克百威");
        JointAssignRequest assignReq = new JointAssignRequest("JA-08", orderId, 1L,
                List.of(new AssignmentItem("毒死蜱", "S-801", "AG-A"),
                        new AssignmentItem("克百威", "S-802", "AG-A")));
        postJson("/api/joint/assignments", assignReq).andExpect(status().isOk());
        // 分样同键重放 / 异参 409
        postJson("/api/joint/assignments", assignReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/joint/assignments", new JointAssignRequest("JA-08", orderId, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-803", "AG-A"),
                                new AssignmentItem("克百威", "S-804", "AG-A"))))
                .andExpect(status().isConflict());
        Integer assignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_assignment WHERE order_id = ?", Integer.class, orderId);
        assertThat(assignments).isEqualTo(2);

        JointResultRequest resultReq = new JointResultRequest("JR-81", orderId, 2L,
                "毒死蜱", "S-801", new BigDecimal("0.01"), OCCURRED, false);
        postJson("/api/joint/results", resultReq).andExpect(status().isCreated());
        postJson("/api/joint/results", resultReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        postJson("/api/joint/results", new JointResultRequest("JR-81", orderId, 2L,
                        "毒死蜱", "S-801", new BigDecimal("0.02"), OCCURRED, false))
                .andExpect(status().isConflict());
        Integer results = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE order_id = ?", Integer.class, orderId);
        assertThat(results).isEqualTo(1);

        postJson("/api/joint/results", new JointResultRequest("JR-82", orderId, 3L,
                        "克百威", "S-802", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated());
        JointDetermineRequest detReq = new JointDetermineRequest("JD-08", orderId, 4L);
        postJson("/api/joint/determinations", detReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallResult").value("PASS"));
        // 判定同键重放：返回首次冻结结果
        postJson("/api/joint/determinations", detReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.overallResult").value("PASS"));
        // 判定异参 409；重复判定（新键）422
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-08", orderId, 5L))
                .andExpect(status().isConflict());
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-08B", orderId, 5L))
                .andExpect(status().isUnprocessableEntity());
        Integer determinations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_determination WHERE order_id = ?", Integer.class, orderId);
        assertThat(determinations).isEqualTo(1);
    }

    @Test
    void 分样任一项失败整单不分样() throws Exception {
        long[] frozen = frozenSuspectBatch("09");
        long orderId = createOrder("JC-09", frozen[0], frozen[1], "毒死蜱", "多菌灵");
        // 未覆盖全部项目
        postJson("/api/joint/assignments", new JointAssignRequest("JA-91", orderId, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-901", "AG-A"))))
                .andExpect(status().isUnprocessableEntity());
        // 机构不具备项目能力（AG-A 无多菌灵能力）
        postJson("/api/joint/assignments", new JointAssignRequest("JA-92", orderId, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-901", "AG-A"),
                                new AssignmentItem("多菌灵", "S-902", "AG-A"))))
                .andExpect(status().isUnprocessableEntity());
        // 样本号请求内重复
        postJson("/api/joint/assignments", new JointAssignRequest("JA-93", orderId, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-901", "AG-A"),
                                new AssignmentItem("多菌灵", "S-901", "AG-B"))))
                .andExpect(status().isUnprocessableEntity());
        // 整单不分样：无分样记录、版本不变、状态不变
        Integer assignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_assignment WHERE order_id = ?", Integer.class, orderId);
        assertThat(assignments).isZero();
        mvc.perform(get("/api/joint/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(1));
        // 失败不占键：同键修正后成功
        postJson("/api/joint/assignments", new JointAssignRequest("JA-92", orderId, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-901", "AG-A"),
                                new AssignmentItem("多菌灵", "S-902", "AG-B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void 样本号全局唯一_跨联检单冲突() throws Exception {
        long[] frozen1 = frozenSuspectBatch("10");
        long order1 = createOrder("JC-10", frozen1[0], frozen1[1], "毒死蜱", "克百威");
        assign(order1, "JA-10", 1L, "毒死蜱", "S-G01", "AG-A", "克百威", "S-G02", "AG-A");
        long[] frozen2 = frozenSuspectBatch("10B");
        long order2 = createOrder("JC-10B", frozen2[0], frozen2[1], "毒死蜱", "克百威");
        postJson("/api/joint/assignments", new JointAssignRequest("JA-10B", order2, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-G01", "AG-A"),
                                new AssignmentItem("克百威", "S-G03", "AG-A"))))
                .andExpect(status().isUnprocessableEntity());
        Integer assignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_assignment WHERE order_id = ?", Integer.class, order2);
        assertThat(assignments).isZero();
    }

    @Test
    void 版本失配409() throws Exception {
        long[] frozen = frozenSuspectBatch("11");
        long orderId = createOrder("JC-11", frozen[0], frozen[1], "毒死蜱", "克百威");
        // 分样版本失配
        postJson("/api/joint/assignments", new JointAssignRequest("JA-11", orderId, 9L,
                        List.of(new AssignmentItem("毒死蜱", "S-B01", "AG-A"),
                                new AssignmentItem("克百威", "S-B02", "AG-A"))))
                .andExpect(status().isConflict());
        assign(orderId, "JA-11B", 1L, "毒死蜱", "S-B01", "AG-A", "克百威", "S-B02", "AG-A");
        // 结果版本失配
        postJson("/api/joint/results", new JointResultRequest("JR-B1", orderId, 1L,
                        "毒死蜱", "S-B01", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isConflict());
        // 判定版本失配
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-11", orderId, 3L))
                .andExpect(status().isConflict());
        Integer version = jdbc.queryForObject(
                "SELECT version FROM joint_order WHERE id = ?", Integer.class, orderId);
        assertThat(version).isEqualTo(2);
    }

    @Test
    void 参数非法400() throws Exception {
        long[] frozen = frozenSuspectBatch("12");
        // 项目少于两种
        postJson("/api/joint/orders", new JointCreateRequest("JC-12A", frozen[0], frozen[1],
                        List.of("毒死蜱")))
                .andExpect(status().isBadRequest());
        // 项目重复
        postJson("/api/joint/orders", new JointCreateRequest("JC-12B", frozen[0], frozen[1],
                        List.of("毒死蜱", "毒死蜱")))
                .andExpect(status().isBadRequest());
        // 缺失必填字段
        postJson("/api/joint/orders", new JointCreateRequest(null, frozen[0], frozen[1],
                        List.of("毒死蜱", "克百威")))
                .andExpect(status().isBadRequest());
        // 浓度为负
        long orderId = createOrder("JC-12", frozen[0], frozen[1], "毒死蜱", "克百威");
        assign(orderId, "JA-12", 1L, "毒死蜱", "S-C01", "AG-A", "克百威", "S-C02", "AG-A");
        postJson("/api/joint/results", new JointResultRequest("JR-C1", orderId, 2L,
                        "毒死蜱", "S-C01", new BigDecimal("-0.01"), OCCURRED, false))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 对象不存在404() throws Exception {
        long[] frozen = frozenSuspectBatch("13");
        postJson("/api/joint/orders", new JointCreateRequest("JC-13A", 999999L, frozen[1],
                        List.of("毒死蜱", "克百威")))
                .andExpect(status().isNotFound());
        postJson("/api/joint/orders", new JointCreateRequest("JC-13B", frozen[0], 999999L,
                        List.of("毒死蜱", "克百威")))
                .andExpect(status().isNotFound());
        postJson("/api/joint/assignments", new JointAssignRequest("JA-13", 999999L, 1L,
                        List.of(new AssignmentItem("毒死蜱", "S-D01", "AG-A"))))
                .andExpect(status().isNotFound());
        postJson("/api/joint/results", new JointResultRequest("JR-D1", 999999L, 1L,
                        "毒死蜱", "S-D01", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isNotFound());
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-13", 999999L, 1L))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/joint/orders/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 业务拒绝422() throws Exception {
        long[] frozen = frozenSuspectBatch("14");
        long batchId = frozen[0];
        long testId = frozen[1];
        // 目录外项目
        postJson("/api/joint/orders", new JointCreateRequest("JC-14A", batchId, testId,
                        List.of("毒死蜱", "不存在项目")))
                .andExpect(status().isUnprocessableEntity());
        // 引用非疑似快检记录：另建正常批次快检
        long normalBatch = om.readTree(postJson("/api/reports",
                        new ReportRequest("JR-14N", "山东寿光", "黄瓜", "JB-14N", new BigDecimal("1")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("batchId").asLong();
        sample(normalBatch, "有机磷");
        long normalTestId = om.readTree(postJson("/api/tests",
                        new TestRequest("JT-14N", normalBatch, "有机磷", new BigDecimal("10")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("testId").asLong();
        postJson("/api/joint/orders", new JointCreateRequest("JC-14B", batchId, normalTestId,
                        List.of("毒死蜱", "克百威")))
                .andExpect(status().isUnprocessableEntity());
        // 每批次至多一张联检单
        long orderId = createOrder("JC-14", batchId, testId, "毒死蜱", "克百威");
        postJson("/api/joint/orders", new JointCreateRequest("JC-14C", batchId, testId,
                        List.of("毒死蜱", "克百威")))
                .andExpect(status().isUnprocessableEntity());
        // 未分样不得录入结果
        postJson("/api/joint/results", new JointResultRequest("JR-E1", orderId, 1L,
                        "毒死蜱", "S-E01", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isUnprocessableEntity());
        // 未分样不得判定
        postJson("/api/joint/determinations", new JointDetermineRequest("JD-14", orderId, 1L))
                .andExpect(status().isUnprocessableEntity());

        assign(orderId, "JA-14", 1L, "毒死蜱", "S-E01", "AG-A", "克百威", "S-E02", "AG-A");
        // 样本号与分样不一致
        postJson("/api/joint/results", new JointResultRequest("JR-E2", orderId, 2L,
                        "毒死蜱", "S-E99", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isUnprocessableEntity());
        // 复测须先有当前结果
        postJson("/api/joint/results", new JointResultRequest("JR-E3", orderId, 2L,
                        "毒死蜱", "S-E01", new BigDecimal("0.01"), OCCURRED, true))
                .andExpect(status().isUnprocessableEntity());
        // 重复首测须走复测
        postJson("/api/joint/results", new JointResultRequest("JR-E4", orderId, 2L,
                        "毒死蜱", "S-E01", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isCreated());
        postJson("/api/joint/results", new JointResultRequest("JR-E5", orderId, 3L,
                        "毒死蜱", "S-E01", new BigDecimal("0.02"), OCCURRED, false))
                .andExpect(status().isUnprocessableEntity());
        // 项目不属于联检单
        postJson("/api/joint/results", new JointResultRequest("JR-E6", orderId, 3L,
                        "多菌灵", "S-E01", new BigDecimal("0.01"), OCCURRED, false))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 辅助 ----------

    private long[] frozenSuspectBatch(String suffix) throws Exception {
        long batchId = om.readTree(postJson("/api/reports",
                        new ReportRequest("JR-" + suffix, "山东寿光", "番茄", "JB-" + suffix,
                                new BigDecimal("5")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("batchId").asLong();
        sample(batchId, "有机磷");
        long testId = om.readTree(postJson("/api/tests",
                        new TestRequest("JT-" + suffix, batchId, "有机磷", new BigDecimal("60")))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.result").value("SUSPECT"))
                        .andExpect(jsonPath("$.batchStatus").value("FROZEN"))
                        .andReturn().getResponse().getContentAsString())
                .get("testId").asLong();
        return new long[]{batchId, testId};
    }

    private long createOrder(String requestId, long batchId, long testId, String... items)
            throws Exception {
        return om.readTree(postJson("/api/joint/orders",
                        new JointCreateRequest(requestId, batchId, testId, List.of(items)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("orderId").asLong();
    }

    private void assign(long orderId, String requestId, long expectedVersion,
                        String item1, String sample1, String agency1,
                        String item2, String sample2, String agency2) throws Exception {
        postJson("/api/joint/assignments", new JointAssignRequest(requestId, orderId,
                        expectedVersion,
                        List.of(new AssignmentItem(item1, sample1, agency1),
                                new AssignmentItem(item2, sample2, agency2))))
                .andExpect(status().isOk());
    }

    private void sample(long batchId, String item) throws Exception {
        postJson("/api/batches/" + batchId + "/samples", new SampleRequest(item))
                .andExpect(status().isCreated());
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
}
