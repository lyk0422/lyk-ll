package com.example.starter.ili;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导入与对齐集成测试（H2 内存库）：主流程、幂等重放、异参冲突、整体回滚与并发同键导入。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ili_test_import;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlignmentImportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SegmentService segmentService;

    @Autowired
    private AlignmentService alignmentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM match_pair");
        jdbc.update("DELETE FROM defect_reading");
        jdbc.update("DELETE FROM alignment_batch");
        jdbc.update("DELETE FROM inspection_run");
        jdbc.update("DELETE FROM pipeline_weld");
        jdbc.update("DELETE FROM pipeline_segment");
    }

    @AfterAll
    void releaseDatabase() {
        // 释放本测试类专用的命名内存库
        jdbc.execute("SHUTDOWN");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void createSegment() {
        segmentService.createSegment(TestData.segmentRequest());
    }

    private MvcResult postImport(Dtos.ImportAlignmentRequest request) throws Exception {
        return mockMvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }

    @Test
    void importHappyPathAlignsAndGrades() throws Exception {
        createSegment();
        mockMvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.sampleImport("REQ-HAPPY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("REQ-HAPPY"))
                .andExpect(jsonPath("$.segmentCode").value(TestData.SEGMENT_CODE))
                .andExpect(jsonPath("$.status").value("ALIGNED"))
                .andExpect(jsonPath("$.pairs.length()").value(3))
                .andExpect(jsonPath("$.pairs[0].grade").value("STABLE"))
                .andExpect(jsonPath("$.pairs[0].reviewStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.pairs[1].grade").value("GROWING"))
                .andExpect(jsonPath("$.pairs[1].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.pairs[2].grade").value("SEVERE"))
                .andExpect(jsonPath("$.pairs[2].growthRatePctPerYear").value(20.0))
                .andExpect(jsonPath("$.newFindings.length()").value(1))
                .andExpect(jsonPath("$.newFindings[0].defectRef").value("D"))
                .andExpect(jsonPath("$.disappeared.length()").value(1))
                .andExpect(jsonPath("$.disappeared[0].defectRef").value("E"));

        // 数据库落库核对：1 批次、2 回合、8 条读数、3 个匹配对
        assertEquals(1, count("alignment_batch"));
        assertEquals(2, count("inspection_run"));
        assertEquals(8, count("defect_reading"));
        assertEquals(3, count("match_pair"));

        // 查询接口返回同一结果
        Long batchId = jdbc.queryForObject("SELECT id FROM alignment_batch", Long.class);
        mockMvc.perform(get("/api/alignment-batches/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairs.length()").value(3))
                .andExpect(jsonPath("$.status").value("ALIGNED"));
    }

    @Test
    void sameKeySameParametersReplaysFirstResult() throws Exception {
        createSegment();
        MvcResult first = postImport(TestData.sampleImport("REQ-REPLAY"));
        assertEquals(201, first.getResponse().getStatus());
        String firstBody = first.getResponse().getContentAsString();

        MvcResult second = postImport(TestData.sampleImport("REQ-REPLAY"));
        assertEquals(200, second.getResponse().getStatus());
        // 重放返回首次结果，且不产生新数据
        assertEquals(firstBody, second.getResponse().getContentAsString());
        assertEquals(1, count("alignment_batch"));
        assertEquals(8, count("defect_reading"));
        assertEquals(3, count("match_pair"));
    }

    @Test
    void sameKeyDifferentParametersReturns409WithoutPartialWrites() throws Exception {
        createSegment();
        assertEquals(201, postImport(TestData.sampleImport("REQ-CONFLICT")).getResponse().getStatus());

        Dtos.ImportAlignmentRequest changed = TestData.sampleImport("REQ-CONFLICT");
        changed = new Dtos.ImportAlignmentRequest("REQ-CONFLICT", TestData.SEGMENT_CODE, changed.run1(),
                new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1), List.of(
                        TestData.defect("A", "100.3", "99"),
                        TestData.defect("B", "200.4", "18"),
                        TestData.defect("C", "299.7", "30"),
                        TestData.defect("D", "400.0", "12"))));
        mockMvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 冲突请求不写入任何部分结果
        assertEquals(1, count("alignment_batch"));
        assertEquals(8, count("defect_reading"));
        assertEquals(3, count("match_pair"));
    }

    @Test
    void importRollsBackEntirelyOnFailure() {
        createSegment();
        // 缺陷编号超长（>64 字符）触发数据库约束错误，且发生在 run1 读数已插入之后
        String tooLongRef = "X".repeat(100);
        Dtos.ImportAlignmentRequest bad = new Dtos.ImportAlignmentRequest("REQ-ROLLBACK",
                TestData.SEGMENT_CODE,
                new Dtos.RunPayload("ILI-2023", LocalDate.of(2023, 1, 1), List.of(
                        TestData.defect("A", "100.0", "10"))),
                new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1), List.of(
                        TestData.defect("A", "100.2", "12"),
                        TestData.defect(tooLongRef, "300.0", "15"))));

        assertThrows(DataAccessException.class, () -> alignmentService.importAndAlign(bad));

        // 整体回滚：批次、读数、匹配对、回合均无残留
        assertEquals(0, count("alignment_batch"));
        assertEquals(0, count("defect_reading"));
        assertEquals(0, count("match_pair"));
        assertEquals(0, count("inspection_run"));
    }

    @Test
    void unknownSegmentReturns404() throws Exception {
        mockMvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TestData.sampleImport("REQ-404"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRunDatesReturn422() throws Exception {
        createSegment();
        Dtos.ImportAlignmentRequest bad = TestData.sampleImport("REQ-DATE");
        bad = new Dtos.ImportAlignmentRequest("REQ-DATE", TestData.SEGMENT_CODE,
                new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1), bad.run1().defects()),
                new Dtos.RunPayload("ILI-2023", LocalDate.of(2023, 1, 1), bad.run2().defects()));
        mockMvc.perform(post("/api/alignment-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("alignment_batch"));
    }

    @Test
    void concurrentSameKeySameParametersCreatesSingleBatch() throws Exception {
        createSegment();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> batchIds = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    var result = alignmentService.importAndAlign(TestData.sampleImport("REQ-RACE-SAME"));
                    batchIds.add(result.response().batchId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 全部线程拿到同一批次，数据库只有一份结果
        assertEquals(1, batchIds.size());
        assertEquals(1, count("alignment_batch"));
        assertEquals(8, count("defect_reading"));
        assertEquals(3, count("match_pair"));
    }

    @Test
    void concurrentSameKeyDifferentParametersYieldsSingleWinner() throws Exception {
        createSegment();
        Dtos.ImportAlignmentRequest variant = TestData.sampleImport("REQ-RACE-DIFF");
        variant = new Dtos.ImportAlignmentRequest("REQ-RACE-DIFF", TestData.SEGMENT_CODE, variant.run1(),
                new Dtos.RunPayload("ILI-2024", LocalDate.of(2024, 1, 1), List.of(
                        TestData.defect("A", "100.3", "15"),
                        TestData.defect("B", "200.4", "18"),
                        TestData.defect("C", "299.7", "30"),
                        TestData.defect("D", "400.0", "12"))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();
        for (Dtos.ImportAlignmentRequest request : List.of(TestData.sampleImport("REQ-RACE-DIFF"), variant)) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    alignmentService.importAndAlign(request);
                    return "CREATED";
                } catch (ApiException e) {
                    return "CONFLICT-" + e.status().value();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new java.util.ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 恰好一个获胜写入，另一个收到 409；不会两个都写入
        assertEquals(1, outcomes.stream().filter("CREATED"::equals).count());
        assertEquals(1, outcomes.stream().filter(o -> o.equals("CONFLICT-409")).count());
        assertEquals(1, count("alignment_batch"));
        assertEquals(8, count("defect_reading"));
    }
}
