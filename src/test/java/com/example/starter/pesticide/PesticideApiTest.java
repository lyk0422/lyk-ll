package com.example.starter.pesticide;

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

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 农残快检 API 主流程与边界测试：恰 50% 疑似、疑似冻结复检、留样 48 小时、
 * 恰 3 批产地预警（UTC 日界）、重复送检拒绝、同键重放、结案终态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PesticideApiTest {

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
    void 主流程_报备抽样快检正常判定放行() throws Exception {
        long batchId = report("R-1", "山东寿光", "番茄", "B-001", "12.5");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest("T-1", batchId, "有机磷", new BigDecimal("30")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("NORMAL"))
                .andExpect(jsonPath("$.batchStatus").value("SAMPLED"));
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.tests", hasSize(1)));
    }

    @Test
    void 恰50抑制率判疑似并冻结准入() throws Exception {
        long batchId = report("R-2", "山东寿光", "番茄", "B-002", "8");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest("T-2", batchId, "有机磷", new BigDecimal("50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUSPECT"))
                .andExpect(jsonPath("$.batchStatus").value("FROZEN"));
        // 冻结批次不得放行
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isConflict());
    }

    @Test
    void 抑制率49点99判正常() throws Exception {
        long batchId = report("R-3", "山东寿光", "番茄", "B-003", "8");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest("T-3", batchId, "有机磷", new BigDecimal("49.99")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("NORMAL"));
    }

    @Test
    void 疑似冻结后复检合格放行() throws Exception {
        long batchId = report("R-4", "山东寿光", "黄瓜", "B-004", "5");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest("T-4", batchId, "有机磷", new BigDecimal("65")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("FROZEN"));
        postJson("/api/rechecks", new RecheckRequest("RC-4", batchId, "有机磷", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("RELEASED"))
                .andExpect(jsonPath("$.retentionUntil").doesNotExist());
        // 重复结案（放行）返回幂等成功
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void 复检不合格转处置并留样48小时() throws Exception {
        long batchId = report("R-5", "山东寿光", "黄瓜", "B-005", "5");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest("T-5", batchId, "有机磷", new BigDecimal("80")))
                .andExpect(status().isCreated());
        postJson("/api/rechecks", new RecheckRequest("RC-5", batchId, "有机磷", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("DISPOSED"))
                .andExpect(jsonPath("$.retentionUntil").value("2026-09-28T08:00:00Z"));
        // 处置结案后不得放行
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isConflict());
    }

    @Test
    void 重复送检拒绝() throws Exception {
        long batchId = report("R-6", "山东寿光", "番茄", "B-006", "3");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest("T-6", batchId, "有机磷", new BigDecimal("10")))
                .andExpect(status().isCreated());
        postJson("/api/tests", new TestRequest("T-6B", batchId, "有机磷", new BigDecimal("20")))
                .andExpect(status().isConflict());
        // 重复抽样同样拒绝
        postJson("/api/batches/" + batchId + "/samples", new SampleRequest("有机磷"))
                .andExpect(status().isConflict());
    }

    @Test
    void 未抽样不得送检() throws Exception {
        long batchId = report("R-7", "山东寿光", "番茄", "B-007", "3");
        postJson("/api/tests", new TestRequest("T-7", batchId, "有机磷", new BigDecimal("10")))
                .andExpect(status().isConflict());
    }

    @Test
    void 未判定不得放行() throws Exception {
        long batchId = report("R-8", "山东寿光", "番茄", "B-008", "3");
        sample(batchId, "有机磷");
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isConflict());
    }

    @Test
    void 同键重放返回首次结果_同键异参409() throws Exception {
        ReportRequest req = new ReportRequest("R-9", "山东寿光", "番茄", "B-009", new BigDecimal("3"));
        long batchId = om.readTree(postJson("/api/reports", req)
                        .andExpect(status().isCreated()).andReturn()
                        .getResponse().getContentAsString())
                .get("batchId").asLong();
        // 同键同参：返回首次结果
        postJson("/api/reports", req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.replayed").value(true));
        // 同键异参：409 且不产生新记录
        postJson("/api/reports", new ReportRequest("R-9", "山东寿光", "番茄", "B-009", new BigDecimal("4")))
                .andExpect(status().isConflict());
        Integer batchCount = jdbc.queryForObject("SELECT COUNT(*) FROM batch", Integer.class);
        org.assertj.core.api.Assertions.assertThat(batchCount).isEqualTo(1);

        // 快检同键重放/异参
        sample(batchId, "有机磷");
        TestRequest testReq = new TestRequest("T-9", batchId, "有机磷", new BigDecimal("20"));
        postJson("/api/tests", testReq).andExpect(status().isCreated());
        postJson("/api/tests", testReq)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.result").value("NORMAL"));
        postJson("/api/tests", new TestRequest("T-9", batchId, "有机磷", new BigDecimal("21")))
                .andExpect(status().isConflict());
        Integer testCount = jdbc.queryForObject("SELECT COUNT(*) FROM test_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(testCount).isEqualTo(1);
    }

    @Test
    void 结案终态后迟到检测只记迟到事件() throws Exception {
        long batchId = report("R-10", "山东寿光", "番茄", "B-010", "3");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest("T-10", batchId, "有机磷", new BigDecimal("10")))
                .andExpect(status().isCreated());
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isOk());
        // 重复放行幂等成功
        postJson("/api/batches/" + batchId + "/release", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        // 迟到快检：状态不变，只记迟到事件
        postJson("/api/tests", new TestRequest("T-10-LATE", batchId, "克百威", new BigDecimal("90")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));
        // 迟到复检：同样只记迟到事件
        postJson("/api/rechecks", new RecheckRequest("RC-10-LATE", batchId, "有机磷", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));
        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.lateEvents", hasSize(2)));
        Integer testCount = jdbc.queryForObject("SELECT COUNT(*) FROM test_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(testCount).isEqualTo(1);
    }

    @Test
    void 恰3批疑似触发产地预警并加严抽检() throws Exception {
        // 前 2 批疑似：不触发
        suspectBatch("RA-1", "TA-1", "云南元谋", "韭菜", "BA-1");
        suspectBatch("RA-2", "TA-2", "云南元谋", "韭菜", "BA-2");
        mvc.perform(get("/api/alerts")).andExpect(jsonPath("$", hasSize(0)));
        // 恰第 3 批疑似：触发预警
        suspectBatch("RA-3", "TA-3", "云南元谋", "韭菜", "BA-3");
        mvc.perform(get("/api/alerts"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].origin").value("云南元谋"))
                .andExpect(jsonPath("$[0].variety").value("韭菜"))
                .andExpect(jsonPath("$[0].triggerCount").value(3))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].alertDate").value("2026-09-26"));
        // 第 4 批疑似：不重复登记
        suspectBatch("RA-4", "TA-4", "云南元谋", "韭菜", "BA-4");
        mvc.perform(get("/api/alerts")).andExpect(jsonPath("$", hasSize(1)));
        // 预警期间该产地后续批次加严抽检
        postJson("/api/reports", new ReportRequest("RA-5", "云南元谋", "韭菜", "BA-5", new BigDecimal("1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.samplingLevel").value("STRICT"));
        // 其他产地不受影响
        postJson("/api/reports", new ReportRequest("RA-6", "山东寿光", "韭菜", "BA-6", new BigDecimal("1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.samplingLevel").value("NORMAL"));
        // 台账复算结果一致
        postJson("/api/alerts/recompute", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void 产地预警按UTC日界统计() throws Exception {
        // 当日 23:30（UTC）两批疑似
        when(clock.instant()).thenReturn(Instant.parse("2026-09-26T23:30:00Z"));
        suspectBatch("RB-1", "TB-1", "海南三亚", "豇豆", "BB-1");
        suspectBatch("RB-2", "TB-2", "海南三亚", "豇豆", "BB-2");
        // 跨 UTC 日界后第 3 批疑似：不计入前一日，不触发
        when(clock.instant()).thenReturn(Instant.parse("2026-09-27T00:30:00Z"));
        suspectBatch("RB-3", "TB-3", "海南三亚", "豇豆", "BB-3");
        mvc.perform(get("/api/alerts")).andExpect(jsonPath("$", hasSize(0)));
        // 次日再两批疑似：恰 3 批触发次日预警
        suspectBatch("RB-4", "TB-4", "海南三亚", "豇豆", "BB-4");
        suspectBatch("RB-5", "TB-5", "海南三亚", "豇豆", "BB-5");
        mvc.perform(get("/api/alerts"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].alertDate").value("2026-09-27"));
    }

    @Test
    void 非冻结批次复检拒绝() throws Exception {
        long batchId = report("R-11", "山东寿光", "番茄", "B-011", "3");
        sample(batchId, "有机磷");
        postJson("/api/rechecks", new RecheckRequest("RC-11", batchId, "有机磷", true))
                .andExpect(status().isConflict());
    }

    private void suspectBatch(String reportReqId, String testReqId,
                              String origin, String variety, String batchNo) throws Exception {
        long batchId = report(reportReqId, origin, variety, batchNo, "1");
        sample(batchId, "有机磷");
        postJson("/api/tests", new TestRequest(testReqId, batchId, "有机磷", new BigDecimal("60")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUSPECT"));
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
}
