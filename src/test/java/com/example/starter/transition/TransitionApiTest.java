package com.example.starter.transition;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 改品种主流程 API 测试：申请→执行→过渡卷登记→检验→复议→处置→完成→归档→迟到事件；
 * 覆盖 10% 恰达直切、灰分恰 2 级、工艺窗口边界、强制降级、复议一次、幂等重放与归档终态。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paper_test_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class TransitionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM late_inspection_event");
        jdbc.update("DELETE FROM roll_judgement");
        jdbc.update("DELETE FROM transition_roll");
        jdbc.update("DELETE FROM cleaning_window");
        jdbc.update("DELETE FROM machine_execution_lock");
        jdbc.update("DELETE FROM transition_order");
        jdbc.update("DELETE FROM request_registry");
        jdbc.update("DELETE FROM grade");
    }

    /** 测试上下文结束后释放命名内存库（DB_CLOSE_DELAY=-1 的配套清理）。 */
    @AfterAll
    void releaseDatabase() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void fullFlowWithBoundariesAndArchiveTerminal() throws Exception {
        // 品种：G-A 100g/灰3；G-B 110g（恰 +10%）/灰5（恰差 2 级）
        upsertGrade("g-a", "G-A", "100.00", 3, "800", "1200", "3.0", "4.5", "30.00");
        upsertGrade("g-b", "G-B", "110.00", 5, "900", "1100", "3.2", "4.0", "32.00");

        // 申请：恰 10% → 直切；灰分恰 2 级 → 不冲洗网部
        mvc.perform(post("/api/transitions/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a1","machineId":"PM-1","orderNo":"O-1",
                                 "fromGradeCode":"G-A","toGradeCode":"G-B"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.switchMode").value("DIRECT"))
                .andExpect(jsonPath("$.wireFlush").value(false));

        // 同键同参重放：返回首次结果，标记重放，不产生新记录
        mvc.perform(post("/api/transitions/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a1","machineId":"PM-1","orderNo":"O-1",
                                 "fromGradeCode":"G-A","toGradeCode":"G-B"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.orderNo").value("O-1"));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Integer.class));

        // 同键异参 → 409，且不产生新记录
        mvc.perform(post("/api/transitions/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a1","machineId":"PM-1","orderNo":"O-1",
                                 "fromGradeCode":"G-B","toGradeCode":"G-A"}
                                """))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Integer.class));

        // 执行：车速恰下限 900、浓度恰上限 4.0 → 边界合格；过渡料长度=900×90/60=1350m
        mvc.perform(post("/api/transitions/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"e1","orderNo":"O-1","switchSeconds":90,
                                 "machineSpeed":900,"consistency":4.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.transitionLengthM").value(1350.0));

        // 登记过渡卷：强制降级
        mvc.perform(post("/api/rolls/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r1","rollNo":"R-1","orderNo":"O-1","lengthM":1350}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalGrade").value("DOWNGRADED"))
                .andExpect(jsonPath("$.inspectionStatus").value("PENDING"));

        // 检验：恰达正品门槛（抗张=32、克重恰+5%、灰分恰差1）→ 门槛通过但仍强制降级
        mvc.perform(post("/api/rolls/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"i1","rollNo":"R-1","measuredBasisWeight":115.50,
                                 "measuredAshLevel":6,"measuredTensile":32.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(false))
                .andExpect(jsonPath("$.gatePassed").value(true))
                .andExpect(jsonPath("$.finalGrade").value("DOWNGRADED"))
                .andExpect(jsonPath("$.firstJudgement.result").value("DOWNGRADED"));

        // 复议改判正品一次
        mvc.perform(post("/api/rolls/appeal").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ap1","rollNo":"R-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalGrade").value("QUALIFIED"))
                .andExpect(jsonPath("$.appealCount").value(1));

        // 再次复议 → 409
        mvc.perform(post("/api/rolls/appeal").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ap2","rollNo":"R-1"}
                                """))
                .andExpect(status().isConflict());

        // 改判链：首次降级 + 复议改判，原降级记录保留
        mvc.perform(get("/api/rolls/R-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgementChain", hasSize(2)))
                .andExpect(jsonPath("$.judgementChain[0].result").value("DOWNGRADED"))
                .andExpect(jsonPath("$.judgementChain[1].result").value("QUALIFIED"));

        // 处置 → 完成 → 归档
        mvc.perform(post("/api/transitions/O-1/dispose"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPOSED"));
        mvc.perform(post("/api/transitions/O-1/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/transitions/archive").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ar1","orderNo":"O-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 重复归档（新 requestId）→ 幂等成功
        mvc.perform(post("/api/transitions/archive").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ar2","orderNo":"O-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        // 归档后迟到检验：只记迟到事件，不产生新判定
        mvc.perform(post("/api/rolls/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"i2","rollNo":"R-1","measuredBasisWeight":110.00,
                                 "measuredAshLevel":5,"measuredTensile":35.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));
        mvc.perform(get("/api/rolls/R-1"))
                .andExpect(jsonPath("$.judgementChain", hasSize(2)));

        // 详情复算：过渡料长度、方式判定、降级卷明细、迟到事件数
        mvc.perform(get("/api/transitions/O-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recomputedTransitionLengthM").value(1350.0))
                .andExpect(jsonPath("$.recomputedSwitchMode").value("DIRECT"))
                .andExpect(jsonPath("$.recomputedWireFlush").value(false))
                .andExpect(jsonPath("$.rolls", hasSize(1)))
                .andExpect(jsonPath("$.rolls[0].finalGrade").value("QUALIFIED"))
                .andExpect(jsonPath("$.lateEventCount").value(1));
    }

    @Test
    void executeOutsideProcessWindowRejected() throws Exception {
        upsertGrade("g-a", "G-A", "100.00", 3, "800", "1200", "3.0", "4.5", "30.00");
        upsertGrade("g-b", "G-B", "110.00", 5, "900", "1100", "3.2", "4.0", "32.00");
        mvc.perform(post("/api/transitions/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a1","machineId":"PM-2","orderNo":"O-2",
                                 "fromGradeCode":"G-A","toGradeCode":"G-B"}
                                """))
                .andExpect(status().isOk());

        // 车速低于窗口下限 → 422
        mvc.perform(post("/api/transitions/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"e1","orderNo":"O-2","switchSeconds":60,
                                 "machineSpeed":899.9,"consistency":3.5}
                                """))
                .andExpect(status().isUnprocessableEntity());
        // 浓度高于窗口上限 → 422
        mvc.perform(post("/api/transitions/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"e2","orderNo":"O-2","switchSeconds":60,
                                 "machineSpeed":1000,"consistency":4.01}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 失败不产生任何资源占用与状态推进
        org.junit.jupiter.api.Assertions.assertEquals("APPLIED",
                jdbc.queryForObject("SELECT status FROM transition_order WHERE order_no='O-2'",
                        String.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(1) FROM cleaning_window", Integer.class));
    }

    @Test
    void ashDiffAboveTwoRequiresWireFlushAndDuplicateOrderConflicts() throws Exception {
        upsertGrade("g-a", "G-A", "100.00", 3, "800", "1200", "3.0", "4.5", "30.00");
        // G-D 110g（恰 +10% 可直切）、灰分 6（与 G-A 差 3 级，超 2 级 → 须冲洗网部）
        upsertGrade("g-d", "G-D", "110.00", 6, "900", "1100", "3.2", "4.0", "32.00");

        mvc.perform(post("/api/transitions/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a1","machineId":"PM-7","orderNo":"O-7",
                                 "fromGradeCode":"G-A","toGradeCode":"G-D"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switchMode").value("DIRECT"))
                .andExpect(jsonPath("$.wireFlush").value(true));

        // 不同 requestId 复用相同单号、另一台纸机 → 唯一键冲突 409，且不新增执行锁
        mvc.perform(post("/api/transitions/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a2","machineId":"PM-8","orderNo":"O-7",
                                 "fromGradeCode":"G-A","toGradeCode":"G-D"}
                                """))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM transition_order", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(1) FROM machine_execution_lock", Integer.class));
    }

    @Test
    void inspectionBelowGateCannotAppeal() throws Exception {
        upsertGrade("g-a", "G-A", "100.00", 3, "800", "1200", "3.0", "4.5", "30.00");
        upsertGrade("g-b", "G-B", "110.00", 5, "900", "1100", "3.2", "4.0", "32.00");
        mvc.perform(post("/api/transitions/apply").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"a1","machineId":"PM-3","orderNo":"O-3",
                         "fromGradeCode":"G-A","toGradeCode":"G-B"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/transitions/execute").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"e1","orderNo":"O-3","switchSeconds":60,
                         "machineSpeed":1000,"consistency":3.5}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/rolls/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"r1","rollNo":"R-9","orderNo":"O-3","lengthM":1000}
                        """)).andExpect(status().isOk());

        // 抗张低于门槛 → 门槛不通过，仍强制降级
        mvc.perform(post("/api/rolls/inspect").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"i1","rollNo":"R-9","measuredBasisWeight":110.00,
                                 "measuredAshLevel":5,"measuredTensile":31.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatePassed").value(false))
                .andExpect(jsonPath("$.finalGrade").value("DOWNGRADED"));

        // 未达门槛 → 复议 422
        mvc.perform(post("/api/rolls/appeal").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ap1","rollNo":"R-9"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    private void upsertGrade(String requestId, String code, String weight, int ash,
                             String speedMin, String speedMax,
                             String consMin, String consMax, String tensile) throws Exception {
        mvc.perform(post("/api/grades").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","gradeCode":"%s","basisWeight":%s,"ashLevel":%d,
                                 "speedMin":%s,"speedMax":%s,"consistencyMin":%s,"consistencyMax":%s,
                                 "tensileMin":%s}
                                """.formatted(requestId, code, weight, ash,
                                speedMin, speedMax, consMin, consMax, tensile)))
                .andExpect(status().isOk());
    }
}
