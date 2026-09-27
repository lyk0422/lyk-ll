package com.example.starter.pesticide;

import com.example.starter.pesticide.dto.ApiDtos.JointAllocEntry;
import com.example.starter.pesticide.dto.ApiDtos.JointAllocateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointCreateRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointDecisionRequest;
import com.example.starter.pesticide.dto.ApiDtos.JointItemVersionEntry;
import com.example.starter.pesticide.dto.ApiDtos.JointResultRequest;
import com.example.starter.pesticide.dto.ApiDtos.RecheckRequest;
import com.example.starter.pesticide.dto.ApiDtos.ReportRequest;
import com.example.starter.pesticide.dto.ApiDtos.SampleRequest;
import com.example.starter.pesticide.dto.ApiDtos.TestRequest;
import com.fasterxml.jackson.databind.JsonNode;
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
 * 定量联检 API 测试：创建/分样/结果/综合判定主流程，恰等限值合格、超限转处置、
 * 缺项待定、复测版本递增、判定后补充信息、幂等与版本门控、旧复检门控。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointInspectionApiTest {

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
        jdbc.update("DELETE FROM joint_decision");
        jdbc.update("DELETE FROM joint_result");
        jdbc.update("DELETE FROM joint_allocation");
        jdbc.update("DELETE FROM joint_item");
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
    void 主流程_联检创建分样结果综合合格放行() throws Exception {
        FrozenCtx ctx = frozenBatch("J1");
        long jointId = jointIdOf(createJoint("JC-1", ctx, "LAB-A", List.of("毒死蜱", "克百威"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items", hasSize(2))));
        // 逐项限值与机构能力版本已冻结
        mvc.perform(get("/api/joint-inspections/" + jointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.item == '毒死蜱')].limitValue").value(0.05))
                .andExpect(jsonPath("$.items[?(@.item == '毒死蜱')].capabilityVersion").value(1))
                .andExpect(jsonPath("$.items[?(@.item == '克百威')].limitValue").value(0.02));
        // 分样：覆盖全部项目
        allocate("JA-1", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J1-1"), new JointAllocEntry("克百威", "SN-J1-2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.version").value(2));
        // 毒死蜱恰等限值合格；克百威低于限值
        addResult("JR-1", jointId, 2, "毒死蜱", "SN-J1-1", "0.05")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultVersion").value(1))
                .andExpect(jsonPath("$.jointVersion").value(3));
        addResult("JR-2", jointId, 3, "克百威", "SN-J1-2", "0.01")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultVersion").value(1))
                .andExpect(jsonPath("$.jointVersion").value(4));
        // 综合判定：携带全部项版本，合格同事务放行
        decide("JD-1", jointId, 4,
                new JointItemVersionEntry("毒死蜱", 1), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"))
                .andExpect(jsonPath("$.jointVersion").value(5))
                .andExpect(jsonPath("$.retentionUntil").doesNotExist());
        mvc.perform(get("/api/batches/" + ctx.batchId()))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        Integer admissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admission WHERE batch_id = ?", Integer.class, ctx.batchId());
        assertThat(admissions).isEqualTo(1);
        // 判定证据冻结全部项版本
        mvc.perform(get("/api/joint-inspections/" + jointId))
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.decision.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.decision.evidence").value("克百威=v1;毒死蜱=v1"));
    }

    @Test
    void 任一超限综合不合格转处置并留样48小时() throws Exception {
        FrozenCtx ctx = frozenBatch("J2");
        long jointId = jointIdOf(createJoint("JC-2", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        allocate("JA-2", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J2-1"), new JointAllocEntry("克百威", "SN-J2-2"))
                .andExpect(status().isCreated());
        addResult("JR-3", jointId, 2, "毒死蜱", "SN-J2-1", "0.06").andExpect(status().isCreated());
        addResult("JR-4", jointId, 3, "克百威", "SN-J2-2", "0.01").andExpect(status().isCreated());
        decide("JD-2", jointId, 4,
                new JointItemVersionEntry("毒死蜱", 1), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("UNQUALIFIED"))
                .andExpect(jsonPath("$.batchStatus").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));
        mvc.perform(get("/api/batches/" + ctx.batchId()))
                .andExpect(jsonPath("$.status").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));
        Integer disposals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposal WHERE batch_id = ?", Integer.class, ctx.batchId());
        assertThat(disposals).isEqualTo(1);
    }

    @Test
    void 尚缺一项结果只能待定不得判定() throws Exception {
        FrozenCtx ctx = frozenBatch("J3");
        long jointId = jointIdOf(createJoint("JC-3", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        allocate("JA-3", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J3-1"), new JointAllocEntry("克百威", "SN-J3-2"))
                .andExpect(status().isCreated());
        addResult("JR-5", jointId, 2, "毒死蜱", "SN-J3-1", "0.01").andExpect(status().isCreated());
        // 克百威尚缺结果：判定 422，联检单保持待定，批次仍冻结
        decide("JD-3", jointId, 3,
                new JointItemVersionEntry("毒死蜱", 1), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isUnprocessableEntity());
        Integer decisions = jdbc.queryForObject("SELECT COUNT(*) FROM joint_decision", Integer.class);
        assertThat(decisions).isZero();
        mvc.perform(get("/api/batches/" + ctx.batchId()))
                .andExpect(jsonPath("$.status").value("FROZEN"));
        // 缺项时旧单项目复检也被门控，不能放行
        postJson("/api/rechecks", new RecheckRequest("RC-J3", ctx.batchId(), "有机磷", true))
                .andExpect(status().isConflict());
    }

    @Test
    void 复测替换当前结果保留旧版本并递增() throws Exception {
        FrozenCtx ctx = frozenBatch("J4");
        long jointId = jointIdOf(createJoint("JC-4", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        allocate("JA-4", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J4-1"), new JointAllocEntry("克百威", "SN-J4-2"))
                .andExpect(status().isCreated());
        // 首次结果超限
        addResult("JR-6", jointId, 2, "毒死蜱", "SN-J4-1", "0.08")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultVersion").value(1));
        addResult("JR-7", jointId, 3, "克百威", "SN-J4-2", "0.01").andExpect(status().isCreated());
        // 复测：替换毒死蜱当前结果，版本递增
        addResult("JR-8", jointId, 4, "毒死蜱", "SN-J4-1", "0.03")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultVersion").value(2))
                .andExpect(jsonPath("$.jointVersion").value(5));
        // 旧结果保留
        mvc.perform(get("/api/joint-inspections/" + jointId))
                .andExpect(jsonPath("$.results", hasSize(3)));
        // 判定须携带复测后的新版本
        decide("JD-4", jointId, 5,
                new JointItemVersionEntry("毒死蜱", 2), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
    }

    @Test
    void 判定携带旧项版本失配则判定零写入() throws Exception {
        FrozenCtx ctx = frozenBatch("J5");
        long jointId = jointIdOf(createJoint("JC-5", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        allocate("JA-5", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J5-1"), new JointAllocEntry("克百威", "SN-J5-2"))
                .andExpect(status().isCreated());
        addResult("JR-9", jointId, 2, "毒死蜱", "SN-J5-1", "0.08").andExpect(status().isCreated());
        addResult("JR-10", jointId, 3, "克百威", "SN-J5-2", "0.01").andExpect(status().isCreated());
        // 复测使毒死蜱结果版本升至 2
        addResult("JR-11", jointId, 4, "毒死蜱", "SN-J5-1", "0.03").andExpect(status().isCreated());
        // 判定携带旧版本 v1：409 且零写入
        decide("JD-5", jointId, 5,
                new JointItemVersionEntry("毒死蜱", 1), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isConflict());
        Integer decisions = jdbc.queryForObject("SELECT COUNT(*) FROM joint_decision", Integer.class);
        assertThat(decisions).isZero();
        mvc.perform(get("/api/batches/" + ctx.batchId()))
                .andExpect(jsonPath("$.status").value("FROZEN"));
        // 携带正确版本可判定
        decide("JD-5B", jointId, 5,
                new JointItemVersionEntry("毒死蜱", 2), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"));
    }

    @Test
    void 综合判定后结果只记补充信息不重开() throws Exception {
        FrozenCtx ctx = frozenBatch("J6");
        long jointId = jointIdOf(createJoint("JC-6", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        allocate("JA-6", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J6-1"), new JointAllocEntry("克百威", "SN-J6-2"))
                .andExpect(status().isCreated());
        addResult("JR-12", jointId, 2, "毒死蜱", "SN-J6-1", "0.01").andExpect(status().isCreated());
        addResult("JR-13", jointId, 3, "克百威", "SN-J6-2", "0.01").andExpect(status().isCreated());
        decide("JD-6", jointId, 4,
                new JointItemVersionEntry("毒死蜱", 1), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.verdict").value("QUALIFIED"));
        // 判定后迟到结果：只记补充信息，判定与批次状态不变
        addResult("JR-14", jointId, 5, "毒死蜱", "SN-J6-1", "0.09")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplemental").value(true))
                .andExpect(jsonPath("$.resultVersion").doesNotExist());
        mvc.perform(get("/api/joint-inspections/" + jointId))
                .andExpect(jsonPath("$.decision.verdict").value("QUALIFIED"))
                .andExpect(jsonPath("$.results", hasSize(2)));
        mvc.perform(get("/api/batches/" + ctx.batchId()))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.lateEvents", hasSize(1)));
        // 已判定后不得重复判定
        decide("JD-6B", jointId, 5,
                new JointItemVersionEntry("毒死蜱", 1), new JointItemVersionEntry("克百威", 1))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 联检创建幂等与失败不占键() throws Exception {
        FrozenCtx ctx = frozenBatch("J7");
        JointCreateRequest req = new JointCreateRequest("JC-7", ctx.batchId(), ctx.testId(),
                "LAB-A", List.of("毒死蜱", "克百威"));
        long jointId = jointIdOf(postJson("/api/joint-inspections", req)
                .andExpect(status().isCreated()));
        // 同键同参重放首次结果
        postJson("/api/joint-inspections", req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointId").value(jointId))
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参 409
        postJson("/api/joint-inspections", new JointCreateRequest("JC-7", ctx.batchId(), ctx.testId(),
                "LAB-A", List.of("毒死蜱", "多菌灵")))
                .andExpect(status().isConflict());
        Integer joints = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(joints).isEqualTo(1);
        // 失败不占键：分样先以未覆盖全部项目失败（422），同 requestId 修正参数后成功
        postJson("/api/joint-inspections/" + jointId + "/allocations",
                new JointAllocateRequest("JA-7", 1, List.of(
                        new JointAllocEntry("毒死蜱", "SN-J7-1"),
                        new JointAllocEntry("毒死蜱", "SN-J7-2"))))
                .andExpect(status().isBadRequest());
        postJson("/api/joint-inspections/" + jointId + "/allocations",
                new JointAllocateRequest("JA-7", 1, List.of(
                        new JointAllocEntry("毒死蜱", "SN-J7-1"),
                        new JointAllocEntry("克百威", "SN-J7-2"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        // 分样同键重放
        postJson("/api/joint-inspections/" + jointId + "/allocations",
                new JointAllocateRequest("JA-7", 1, List.of(
                        new JointAllocEntry("毒死蜱", "SN-J7-1"),
                        new JointAllocEntry("克百威", "SN-J7-2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        // 结果同键重放与异参 409
        var resultReq = new JointResultRequest("JR-15", 2, "毒死蜱", "SN-J7-1",
                new BigDecimal("0.01"), T0);
        postJson("/api/joint-inspections/" + jointId + "/results", resultReq)
                .andExpect(status().isCreated());
        postJson("/api/joint-inspections/" + jointId + "/results", resultReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.resultVersion").value(1));
        postJson("/api/joint-inspections/" + jointId + "/results",
                new JointResultRequest("JR-15", 2, "毒死蜱", "SN-J7-1",
                        new BigDecimal("0.02"), T0))
                .andExpect(status().isConflict());
        Integer results = jdbc.queryForObject("SELECT COUNT(*) FROM joint_result", Integer.class);
        assertThat(results).isEqualTo(1);
    }

    @Test
    void 创建参数校验与目录门控() throws Exception {
        FrozenCtx ctx = frozenBatch("J8");
        // 项目少于两种：400
        postJson("/api/joint-inspections", new JointCreateRequest("JC-8A", ctx.batchId(),
                ctx.testId(), "LAB-A", List.of("毒死蜱")))
                .andExpect(status().isBadRequest());
        // 项目重复：400
        postJson("/api/joint-inspections", new JointCreateRequest("JC-8B", ctx.batchId(),
                ctx.testId(), "LAB-A", List.of("毒死蜱", "毒死蜱")))
                .andExpect(status().isBadRequest());
        // 项目不在合成目录：422
        postJson("/api/joint-inspections", new JointCreateRequest("JC-8C", ctx.batchId(),
                ctx.testId(), "LAB-A", List.of("毒死蜱", "未知项目")))
                .andExpect(status().isUnprocessableEntity());
        // 机构不具备项目能力：422（LAB-B 无克百威能力）
        postJson("/api/joint-inspections", new JointCreateRequest("JC-8D", ctx.batchId(),
                ctx.testId(), "LAB-B", List.of("毒死蜱", "克百威")))
                .andExpect(status().isUnprocessableEntity());
        // 快检记录不存在：404
        postJson("/api/joint-inspections", new JointCreateRequest("JC-8E", ctx.batchId(),
                999999L, "LAB-A", List.of("毒死蜱", "克百威")))
                .andExpect(status().isNotFound());
        // 批次不存在：404
        postJson("/api/joint-inspections", new JointCreateRequest("JC-8F", 999999L,
                ctx.testId(), "LAB-A", List.of("毒死蜱", "克百威")))
                .andExpect(status().isNotFound());
        Integer joints = jdbc.queryForObject("SELECT COUNT(*) FROM joint_order", Integer.class);
        assertThat(joints).isZero();
    }

    @Test
    void 非冻结批次与引用他批次快检不得建联检() throws Exception {
        // 批次未冻结（快检正常）
        long normalBatch = report("RJ-9A", "山东寿光", "番茄", "BJ-9A", "3");
        sample(normalBatch, "有机磷");
        long normalTestId = testIdOf(postJson("/api/tests",
                new TestRequest("TJ-9A", normalBatch, "有机磷", new BigDecimal("10")))
                .andExpect(status().isCreated()));
        postJson("/api/joint-inspections", new JointCreateRequest("JC-9A", normalBatch,
                normalTestId, "LAB-A", List.of("毒死蜱", "克百威")))
                .andExpect(status().isUnprocessableEntity());
        // 冻结批次引用他批次快检：422
        FrozenCtx ctx = frozenBatch("J9B");
        postJson("/api/joint-inspections", new JointCreateRequest("JC-9B", ctx.batchId(),
                normalTestId, "LAB-A", List.of("毒死蜱", "克百威")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 分样边界_未覆盖整单不分样_样本号全局唯一() throws Exception {
        FrozenCtx ctx = frozenBatch("J10");
        long jointId = jointIdOf(createJoint("JC-10", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        // 未覆盖全部项目：422 且整单不分样
        postJson("/api/joint-inspections/" + jointId + "/allocations",
                new JointAllocateRequest("JA-10A", 1, List.of(
                        new JointAllocEntry("毒死蜱", "SN-J10-1"),
                        new JointAllocEntry("多菌灵", "SN-J10-2"))))
                .andExpect(status().isUnprocessableEntity());
        Integer allocated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_item WHERE joint_id = ? AND sample_no IS NOT NULL",
                Integer.class, jointId);
        assertThat(allocated).isZero();
        mvc.perform(get("/api/joint-inspections/" + jointId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
        // expectedVersion 失配：409
        postJson("/api/joint-inspections/" + jointId + "/allocations",
                new JointAllocateRequest("JA-10B", 3, List.of(
                        new JointAllocEntry("毒死蜱", "SN-J10-1"),
                        new JointAllocEntry("克百威", "SN-J10-2"))))
                .andExpect(status().isConflict());
        // 正常分样
        allocate("JA-10C", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J10-1"), new JointAllocEntry("克百威", "SN-J10-2"))
                .andExpect(status().isCreated());
        // 另一联检单使用相同样本号：409（样本号全局唯一）
        FrozenCtx ctx2 = frozenBatch("J10B");
        long jointId2 = jointIdOf(createJoint("JC-10B", ctx2, "LAB-A", List.of("毒死蜱", "克百威")));
        postJson("/api/joint-inspections/" + jointId2 + "/allocations",
                new JointAllocateRequest("JA-10D", 1, List.of(
                        new JointAllocEntry("毒死蜱", "SN-J10-1"),
                        new JointAllocEntry("克百威", "SN-J10-9"))))
                .andExpect(status().isConflict());
        Integer allocated2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_item WHERE joint_id = ? AND sample_no IS NOT NULL",
                Integer.class, jointId2);
        assertThat(allocated2).isZero();
    }

    @Test
    void 结果录入边界与版本门控() throws Exception {
        FrozenCtx ctx = frozenBatch("J11");
        long jointId = jointIdOf(createJoint("JC-11", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        // 未分样录结果：422
        addResult("JR-16", jointId, 1, "毒死蜱", "SN-J11-1", "0.01")
                .andExpect(status().isUnprocessableEntity());
        allocate("JA-11", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN-J11-1"), new JointAllocEntry("克百威", "SN-J11-2"))
                .andExpect(status().isCreated());
        // 联检版本失配：409
        addResult("JR-17", jointId, 9, "毒死蜱", "SN-J11-1", "0.01")
                .andExpect(status().isConflict());
        // 样本号与分样不一致：422
        addResult("JR-18", jointId, 2, "毒死蜱", "SN-OTHER", "0.01")
                .andExpect(status().isUnprocessableEntity());
        // 项目不属于联检单：422
        addResult("JR-19", jointId, 2, "多菌灵", "SN-J11-9", "0.01")
                .andExpect(status().isUnprocessableEntity());
        // 浓度为负：400
        postJson("/api/joint-inspections/" + jointId + "/results",
                new JointResultRequest("JR-20", 2, "毒死蜱", "SN-J11-1",
                        new BigDecimal("-0.01"), T0))
                .andExpect(status().isBadRequest());
        // 联检单不存在：404
        addResult("JR-21", 999999L, 1, "毒死蜱", "SN-X", "0.01")
                .andExpect(status().isNotFound());
        Integer results = jdbc.queryForObject("SELECT COUNT(*) FROM joint_result", Integer.class);
        assertThat(results).isZero();
    }

    @Test
    void 启用联检后旧复检被门控_未启用联检旧行为不变() throws Exception {
        // 未启用联检：旧复检合格放行不变
        FrozenCtx legacy = frozenBatch("J12A");
        postJson("/api/rechecks", new RecheckRequest("RC-J12A", legacy.batchId(), "有机磷", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"));
        // 启用联检：旧复检被门控
        FrozenCtx gated = frozenBatch("J12B");
        createJoint("JC-12", gated, "LAB-A", List.of("毒死蜱", "克百威"))
                .andExpect(status().isCreated());
        postJson("/api/rechecks", new RecheckRequest("RC-J12B", gated.batchId(), "有机磷", true))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/batches/" + gated.batchId()))
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void 样本号含分隔符可正常分样且指纹无歧义() throws Exception {
        FrozenCtx ctx = frozenBatch("J13");
        long jointId = jointIdOf(createJoint("JC-13", ctx, "LAB-A", List.of("毒死蜱", "克百威")));
        // 样本号含分隔符：合法文本正常分样
        allocate("JA-13", jointId, 1,
                new JointAllocEntry("毒死蜱", "SN|J13:1"), new JointAllocEntry("克百威", "SN|J13:2"))
                .andExpect(status().isCreated());
        // 结构化指纹：字段边界无歧义，含分隔符的不同组合指纹不同
        assertThat(Fingerprint.structured("ab", "c|d"))
                .isNotEqualTo(Fingerprint.structured("ab|c", "d"));
        assertThat(Fingerprint.structured("a", "b"))
                .isNotEqualTo(Fingerprint.structured("a:b", ""));
    }

    private FrozenCtx frozenBatch(String suffix) throws Exception {
        long batchId = report("R-" + suffix, "山东寿光", "番茄", "B-" + suffix, "3");
        sample(batchId, "有机磷");
        long testId = testIdOf(postJson("/api/tests",
                new TestRequest("T-" + suffix, batchId, "有机磷", new BigDecimal("60")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUSPECT"))
                .andExpect(jsonPath("$.batchStatus").value("FROZEN")));
        return new FrozenCtx(batchId, testId);
    }

    private record FrozenCtx(long batchId, long testId) {
    }

    private ResultActions createJoint(String requestId, FrozenCtx ctx, String agency,
                                      List<String> items) throws Exception {
        return postJson("/api/joint-inspections",
                new JointCreateRequest(requestId, ctx.batchId(), ctx.testId(), agency, items));
    }

    private ResultActions allocate(String requestId, long jointId, int expectedVersion,
                                   JointAllocEntry... entries) throws Exception {
        return postJson("/api/joint-inspections/" + jointId + "/allocations",
                new JointAllocateRequest(requestId, expectedVersion, List.of(entries)));
    }

    private ResultActions addResult(String requestId, long jointId, int expectedVersion,
                                    String item, String sampleNo, String concentration) throws Exception {
        return postJson("/api/joint-inspections/" + jointId + "/results",
                new JointResultRequest(requestId, expectedVersion, item, sampleNo,
                        new BigDecimal(concentration), T0));
    }

    private ResultActions decide(String requestId, long jointId, int expectedVersion,
                                 JointItemVersionEntry... entries) throws Exception {
        return postJson("/api/joint-inspections/" + jointId + "/decision",
                new JointDecisionRequest(requestId, expectedVersion, List.of(entries)));
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

    private ResultActions postJson(String url, Object body) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(om.writeValueAsString(body));
        } else {
            request.content("{}");
        }
        return mvc.perform(request);
    }

    private long jointIdOf(ResultActions actions) throws Exception {
        JsonNode json = om.readTree(actions.andReturn().getResponse().getContentAsString());
        return json.get("jointId").asLong();
    }

    private long testIdOf(ResultActions actions) throws Exception {
        JsonNode json = om.readTree(actions.andReturn().getResponse().getContentAsString());
        return json.get("testId").asLong();
    }
}
