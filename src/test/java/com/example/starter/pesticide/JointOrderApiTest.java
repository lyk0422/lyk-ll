package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAssignItem;
import com.example.starter.pesticide.dto.ApiDtos.JointAssignRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointJudgeRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointOrderCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointSupplementRequest;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 联检 API 主流程与边界测试：建单冻结、分样全覆盖、恰等限值合格、任一超限整体不合格、
 * 缺项待定、复测递增版本、综合判定同事务放行/处置、判定后补充、幂等重放与版本失配。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointOrderApiTest {

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
        jdbc.update("DELETE FROM joint_idempotency");
        jdbc.update("DELETE FROM joint_supplement");
        jdbc.update("DELETE FROM joint_judgment");
        jdbc.update("DELETE FROM joint_result");
        jdbc.update("DELETE FROM joint_assignment");
        jdbc.update("DELETE FROM joint_order_item");
        jdbc.update("DELETE FROM joint_order");
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
    void 主流程_联检全项合格综合判定放行() throws Exception {
        long batchId = frozenBatch("JR-1", "JT-1", "JB-001");
        long orderId = createOrder("JO-1", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.labCapabilityVersion").value("CAP-SYN-2026-1"))
                .andExpect(jsonPath("$.catalogVersion").value("CAT-SYN-2026-1"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));

        assign(orderId, "JA-1", 1, new JointAssignItem("SYN-A", "SN-001"),
                new JointAssignItem("SYN-B", "SN-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.assignments", hasSize(2)));

        submitResult(orderId, "JE-1", 2, "SYN-A", "0.05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualified").value(true))
                .andExpect(jsonPath("$.itemVersion").value(1))
                .andExpect(jsonPath("$.orderVersion").value(3))
                .andExpect(jsonPath("$.sampleNo").value("SN-001"));
        // 恰等限值合格
        submitResult(orderId, "JE-2", 3, "SYN-B", "0.2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualified").value(true))
                .andExpect(jsonPath("$.orderVersion").value(4));

        judge(orderId, "JJ-1", 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"))
                .andExpect(jsonPath("$.itemVersions").value("SYN-A=1;SYN-B=1"))
                .andExpect(jsonPath("$.retentionUntil").doesNotExist());

        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        Integer admissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission WHERE batch_id = ?", Integer.class, batchId);
        assertThat(admissions).isEqualTo(1);
        // 详情含冻结证据
        mvc.perform(get("/api/joint-orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JUDGED"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.judgment.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.judgment.evidence", containsString("\"itemCode\":\"SYN-A\"")))
                .andExpect(jsonPath("$.judgment.evidenceFingerprint").isNotEmpty());
    }

    @Test
    void 任一超限整体不合格_转处置并留样48小时() throws Exception {
        long batchId = frozenBatch("JR-2", "JT-2", "JB-002");
        long orderId = createOrder("JO-2", batchId, "SYN-LAB-2", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        assign(orderId, "JA-2", 1, new JointAssignItem("SYN-A", "SN-101"),
                new JointAssignItem("SYN-B", "SN-102"))
                .andExpect(status().isOk());
        submitResult(orderId, "JE-3", 2, "SYN-A", "0.05").andExpect(status().isOk());
        // SYN-B 限值 0.2，0.21 超限
        submitResult(orderId, "JE-4", 3, "SYN-B", "0.21")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualified").value(false));

        judge(orderId, "JJ-2", 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("UNQUALIFIED"))
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
    void 尚缺一项只能待定_不得单项目复检放行() throws Exception {
        long batchId = frozenBatch("JR-3", "JT-3", "JB-003");
        long orderId = createOrder("JO-3", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        assign(orderId, "JA-3", 1, new JointAssignItem("SYN-A", "SN-201"),
                new JointAssignItem("SYN-B", "SN-202"))
                .andExpect(status().isOk());
        submitResult(orderId, "JE-5", 2, "SYN-A", "0.05").andExpect(status().isOk());
        // 尚缺 SYN-B：综合判定只能待定
        judge(orderId, "JJ-3", 3).andExpect(status().isUnprocessableEntity());
        // 已启用联检：不得沿用单项目复检放行
        postJson("/api/rechecks", new RecheckRequest("RC-J3", batchId, "有机磷", true))
                .andExpect(status().isUnprocessableEntity());
        // 冻结批次不得直接放行
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isConflict());
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void 复测保留旧结果递增版本_判定携带全部项版本() throws Exception {
        long batchId = frozenBatch("JR-4", "JT-4", "JB-004");
        long orderId = createOrder("JO-4", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        assign(orderId, "JA-4", 1, new JointAssignItem("SYN-A", "SN-301"),
                new JointAssignItem("SYN-B", "SN-302"))
                .andExpect(status().isOk());
        // SYN-A 首次超限
        submitResult(orderId, "JE-6", 2, "SYN-A", "0.15")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualified").value(false))
                .andExpect(jsonPath("$.itemVersion").value(1));
        submitResult(orderId, "JE-7", 3, "SYN-B", "0.1").andExpect(status().isOk());
        // 复测 SYN-A：替换当前结果，版本递增
        submitResult(orderId, "JE-8", 4, "SYN-A", "0.05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualified").value(true))
                .andExpect(jsonPath("$.itemVersion").value(2))
                .andExpect(jsonPath("$.orderVersion").value(5));
        // 旧结果保留且标记被替换
        mvc.perform(get("/api/joint-orders/" + orderId))
                .andExpect(jsonPath("$.results", hasSize(3)))
                .andExpect(jsonPath("$.results[0].superseded").value(true))
                .andExpect(jsonPath("$.results[2].superseded").value(false));
        // 综合判定携带全部项版本
        judge(orderId, "JJ-4", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.itemVersions").value("SYN-A=2;SYN-B=1"));
    }

    @Test
    void 建单校验_非冻结批次与项目机构规则() throws Exception {
        // 批次不存在
        createOrder("JO-10", 999999L, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isNotFound());
        // 批次非疑似冻结
        long normalBatch = report("JR-10", "山东寿光", "番茄", "JB-010", "5");
        createOrder("JO-11", normalBatch, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isUnprocessableEntity());
        long batchId = frozenBatch("JR-11", "JT-11", "JB-011");
        // 项目少于两种
        createOrder("JO-12", batchId, "SYN-LAB-1", "SYN-A")
                .andExpect(status().isBadRequest());
        // 项目重复
        createOrder("JO-13", batchId, "SYN-LAB-1", "SYN-A", "SYN-A")
                .andExpect(status().isBadRequest());
        // 项目不在合成目录
        createOrder("JO-14", batchId, "SYN-LAB-1", "SYN-A", "SYN-X")
                .andExpect(status().isNotFound());
        // 机构不存在
        createOrder("JO-15", batchId, "SYN-LAB-9", "SYN-A", "SYN-B")
                .andExpect(status().isNotFound());
        // 机构不具备项目能力（SYN-LAB-1 无 SYN-C 能力）
        createOrder("JO-16", batchId, "SYN-LAB-1", "SYN-A", "SYN-C")
                .andExpect(status().isUnprocessableEntity());
        // 合法建单后重复建单
        createOrder("JO-17", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated());
        createOrder("JO-18", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 分样校验_全覆盖与样本号全局唯一_失败整单不分样() throws Exception {
        long batchId = frozenBatch("JR-20", "JT-20", "JB-020");
        long orderId = createOrder("JO-20", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        // 版本失配
        assign(orderId, "JA-20A", 3, new JointAssignItem("SYN-A", "SN-401"),
                new JointAssignItem("SYN-B", "SN-402"))
                .andExpect(status().isConflict());
        // 未覆盖全部项目
        assign(orderId, "JA-20B", 1, new JointAssignItem("SYN-A", "SN-403"))
                .andExpect(status().isUnprocessableEntity());
        // 包含单外项目
        assign(orderId, "JA-20C", 1, new JointAssignItem("SYN-A", "SN-404"),
                new JointAssignItem("SYN-C", "SN-405"))
                .andExpect(status().isUnprocessableEntity());
        // 请求内样本号重复
        assign(orderId, "JA-20D", 1, new JointAssignItem("SYN-A", "SN-406"),
                new JointAssignItem("SYN-B", "SN-406"))
                .andExpect(status().isBadRequest());
        // 失败不占键、整单未分样
        mvc.perform(get("/api/joint-orders/" + orderId))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.assignments", hasSize(0)));

        // 另一联检单占用样本号 SN-500
        long otherBatch = frozenBatch("JR-21", "JT-21", "JB-021");
        long otherOrder = createOrder("JO-21", otherBatch, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        assign(otherOrder, "JA-21", 1, new JointAssignItem("SYN-A", "SN-500"),
                new JointAssignItem("SYN-B", "SN-501"))
                .andExpect(status().isOk());
        // 本单分样含已全局使用样本号：任一项失败整单不分样
        assign(orderId, "JA-20E", 1, new JointAssignItem("SYN-A", "SN-500"),
                new JointAssignItem("SYN-B", "SN-502"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/joint-orders/" + orderId))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.assignments", hasSize(0)));
        // 修正后成功分样
        assign(orderId, "JA-20F", 1, new JointAssignItem("SYN-A", "SN-503"),
                new JointAssignItem("SYN-B", "SN-504"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 重复分样拒绝
        assign(orderId, "JA-20G", 2, new JointAssignItem("SYN-A", "SN-505"),
                new JointAssignItem("SYN-B", "SN-506"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 结果校验_未分样与负浓度与版本失配() throws Exception {
        long batchId = frozenBatch("JR-30", "JT-30", "JB-030");
        long orderId = createOrder("JO-30", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        // 未分样不得提交结果
        submitResult(orderId, "JE-30A", 1, "SYN-A", "0.05")
                .andExpect(status().isUnprocessableEntity());
        assign(orderId, "JA-30", 1, new JointAssignItem("SYN-A", "SN-601"),
                new JointAssignItem("SYN-B", "SN-602"))
                .andExpect(status().isOk());
        // 负浓度 400
        postJson("/api/joint-orders/" + orderId + "/results",
                new JointResultRequest("JE-30B", 2, "SYN-A", new BigDecimal("-0.1"), T0))
                .andExpect(status().isBadRequest());
        // 版本失配 409
        submitResult(orderId, "JE-30C", 9, "SYN-A", "0.05")
                .andExpect(status().isConflict());
        // 单外项目 422
        submitResult(orderId, "JE-30D", 2, "SYN-C", "0.05")
                .andExpect(status().isUnprocessableEntity());
        // 联检单不存在 404
        submitResult(999999L, "JE-30E", 1, "SYN-A", "0.05")
                .andExpect(status().isNotFound());
    }

    @Test
    void 判定版本失配零写入() throws Exception {
        long batchId = frozenBatch("JR-31", "JT-31", "JB-031");
        long orderId = createOrder("JO-31", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        assign(orderId, "JA-31", 1, new JointAssignItem("SYN-A", "SN-701"),
                new JointAssignItem("SYN-B", "SN-702"))
                .andExpect(status().isOk());
        submitResult(orderId, "JE-31A", 2, "SYN-A", "0.05").andExpect(status().isOk());
        submitResult(orderId, "JE-31B", 3, "SYN-B", "0.1").andExpect(status().isOk());
        // 复测使版本推进到 5
        submitResult(orderId, "JE-31C", 4, "SYN-A", "0.06").andExpect(status().isOk());
        // 携带过期版本 4 的判定：版本失配，零写入
        judge(orderId, "JJ-31", 4).andExpect(status().isConflict());
        Integer judgments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_judgment WHERE order_id = ?", Integer.class, orderId);
        assertThat(judgments).isEqualTo(0);
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("FROZEN"));
        mvc.perform(get("/api/joint-orders/" + orderId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.judgment").doesNotExist());
        // 正确版本判定成功
        judge(orderId, "JJ-32", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"));
        // 重复判定拒绝
        judge(orderId, "JJ-33", 6).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 幂等_同键同参重放首次冻结结果_异参409_失败不占键() throws Exception {
        long batchId = frozenBatch("JR-40", "JT-40", "JB-040");
        // 失败不占键：先异参失败（项目不在目录），同键修正后成功
        createOrder("JO-40", batchId, "SYN-LAB-1", "SYN-A", "SYN-X")
                .andExpect(status().isNotFound());
        long orderId = createOrder("JO-40", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        // 同键同参：重放首次冻结结果（版本仍为 1）
        createOrder("JO-40", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参：409
        createOrder("JO-40", batchId, "SYN-LAB-1", "SYN-A", "SYN-C")
                .andExpect(status().isConflict());
        Integer orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(orderCount).isEqualTo(1);

        // 分样幂等
        JointAssignRequest assignReq = new JointAssignRequest("JA-40", 1,
                List.of(new JointAssignItem("SYN-A", "SN-801"), new JointAssignItem("SYN-B", "SN-802")),
                T0);
        postJson("/api/joint-orders/" + orderId + "/assignments", assignReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/joint-orders/" + orderId + "/assignments", assignReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/joint-orders/" + orderId + "/assignments",
                new JointAssignRequest("JA-40", 1,
                        List.of(new JointAssignItem("SYN-A", "SN-801"),
                                new JointAssignItem("SYN-B", "SN-899")), T0))
                .andExpect(status().isConflict());

        // 结果幂等
        JointResultRequest resultReq = new JointResultRequest("JE-40", 2, "SYN-A",
                new BigDecimal("0.05"), T0);
        postJson("/api/joint-orders/" + orderId + "/results", resultReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemVersion").value(1));
        postJson("/api/joint-orders/" + orderId + "/results", resultReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.itemVersion").value(1));
        postJson("/api/joint-orders/" + orderId + "/results",
                new JointResultRequest("JE-40", 2, "SYN-A", new BigDecimal("0.06"), T0))
                .andExpect(status().isConflict());
        Integer resultCount = jdbc.queryForObject("SELECT COUNT(*) FROM joint_result", Integer.class);
        assertThat(resultCount).isEqualTo(1);

        // 判定幂等
        submitResult(orderId, "JE-41", 3, "SYN-B", "0.1").andExpect(status().isOk());
        JointJudgeRequest judgeReq = new JointJudgeRequest("JJ-40", 4, T0);
        postJson("/api/joint-orders/" + orderId + "/judgment", judgeReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"));
        postJson("/api/joint-orders/" + orderId + "/judgment", judgeReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"));
        postJson("/api/joint-orders/" + orderId + "/judgment",
                new JointJudgeRequest("JJ-40", 5, T0))
                .andExpect(status().isConflict());
        Integer judgmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_judgment", Integer.class);
        assertThat(judgmentCount).isEqualTo(1);
    }

    @Test
    void 判定后结果只记补充信息不重开() throws Exception {
        long batchId = frozenBatch("JR-50", "JT-50", "JB-050");
        long orderId = createOrder("JO-50", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        assign(orderId, "JA-50", 1, new JointAssignItem("SYN-A", "SN-901"),
                new JointAssignItem("SYN-B", "SN-902"))
                .andExpect(status().isOk());
        submitResult(orderId, "JE-50", 2, "SYN-A", "0.05").andExpect(status().isOk());
        submitResult(orderId, "JE-51", 3, "SYN-B", "0.1").andExpect(status().isOk());
        judge(orderId, "JJ-50", 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"));
        // 判定后提交结果：只记补充信息，不重开
        submitResult(orderId, "JE-52", 5, "SYN-A", "0.99")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplemental").value(true))
                .andExpect(jsonPath("$.orderVersion").value(5));
        // 补充信息入口
        postJson("/api/joint-orders/" + orderId + "/supplements",
                new JointSupplementRequest("JS-50", "SYN-A", "留样复核中", T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
        // 批次状态与判定结论不变
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/api/joint-orders/" + orderId))
                .andExpect(jsonPath("$.status").value("JUDGED"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.judgment.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.supplements", hasSize(2)));
        Integer resultCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_result WHERE order_id = ?", Integer.class, orderId);
        assertThat(resultCount).isEqualTo(2);
    }

    @Test
    void 补充信息需已判定_指纹结构化区分分隔符文本() throws Exception {
        long batchId = frozenBatch("JR-60", "JT-60", "JB-060");
        long orderId = createOrder("JO-60", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        // 未判定不得补充
        postJson("/api/joint-orders/" + orderId + "/supplements",
                new JointSupplementRequest("JS-60", null, "提前补充", T0))
                .andExpect(status().isUnprocessableEntity());
        assign(orderId, "JA-60", 1, new JointAssignItem("SYN-A", "SN-911"),
                new JointAssignItem("SYN-B", "SN-912"))
                .andExpect(status().isOk());
        submitResult(orderId, "JE-60", 2, "SYN-A", "0.05").andExpect(status().isOk());
        submitResult(orderId, "JE-61", 3, "SYN-B", "0.1").andExpect(status().isOk());
        judge(orderId, "JJ-60", 4).andExpect(status().isOk());
        // 含分隔符文本：朴素拼接会碰撞 ("a|b"+"c" == "a"+"b|c")，结构化指纹须区分
        postJson("/api/joint-orders/" + orderId + "/supplements",
                new JointSupplementRequest("JS-61", "a|b", "c", T0))
                .andExpect(status().isOk());
        postJson("/api/joint-orders/" + orderId + "/supplements",
                new JointSupplementRequest("JS-61", "a", "b|c", T0))
                .andExpect(status().isConflict());
        // 同键同参仍重放
        postJson("/api/joint-orders/" + orderId + "/supplements",
                new JointSupplementRequest("JS-61", "a|b", "c", T0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        Integer supplementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_supplement WHERE request_id = 'JS-61'", Integer.class);
        assertThat(supplementCount).isEqualTo(1);
    }

    @Test
    void 查询_按联检单与批次查询_不存在404() throws Exception {
        long batchId = frozenBatch("JR-70", "JT-70", "JB-070");
        long orderId = createOrder("JO-70", batchId, "SYN-LAB-1", "SYN-A", "SYN-B")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .transform(json -> readLong(json, "orderId"));
        mvc.perform(get("/api/joint-orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.frozenTests", containsString("SUSPECT")))
                .andExpect(jsonPath("$.items", hasSize(2)));
        mvc.perform(get("/api/batches/" + batchId + "/joint-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId));
        mvc.perform(get("/api/joint-orders/999999"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/batches/999999/joint-order"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 未启用联检的旧复检行为不变() throws Exception {
        long batchId = frozenBatch("JR-80", "JT-80", "JB-080");
        postJson("/api/rechecks", new RecheckRequest("RC-80", batchId, "有机磷", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
    }

    // ============ 辅助 ============

    private long frozenBatch(String reportReqId, String testReqId, String batchNo) throws Exception {
        long batchId = report(reportReqId, "山东寿光", "番茄", batchNo, "5");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest(testReqId, batchId, "有机磷", new BigDecimal("60")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("FROZEN"));
        return batchId;
    }

    private ResultActions createOrder(String requestId, long batchId, String labCode,
                                      String... itemCodes) throws Exception {
        return postJson("/api/joint-orders",
                new JointOrderCreateRequest(requestId, batchId, labCode, List.of(itemCodes), T0));
    }

    private ResultActions assign(long orderId, String requestId, int expectedVersion,
                                 JointAssignItem... items) throws Exception {
        return postJson("/api/joint-orders/" + orderId + "/assignments",
                new JointAssignRequest(requestId, expectedVersion, List.of(items), T0));
    }

    private ResultActions submitResult(long orderId, String requestId, int expectedVersion,
                                       String itemCode, String concentration) throws Exception {
        return postJson("/api/joint-orders/" + orderId + "/results",
                new JointResultRequest(requestId, expectedVersion, itemCode,
                        new BigDecimal(concentration), T0));
    }

    private ResultActions judge(long orderId, String requestId, int expectedVersion)
            throws Exception {
        return postJson("/api/joint-orders/" + orderId + "/judgment",
                new JointJudgeRequest(requestId, expectedVersion, T0));
    }

    private long report(String requestId, String origin, String variety, String batchNo,
                        String quantity) throws Exception {
        var result = postJson("/api/reports",
                new ReportRequest(requestId, origin, variety, batchNo, new BigDecimal(quantity)))
                .andExpect(status().isCreated()).andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("batchId").asLong();
    }

    private void sample(long batchId, String item) throws Exception {
        postJson("/api/batches/" + batchId + "/samples", new SampleRequest(item))
                .andExpect(status().isCreated());
    }

    private long readLong(String json, String field) {
        try {
            return om.readTree(json).get(field).asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
