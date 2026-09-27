package com.example.starter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.testsupport.TestClockConfig;

/**
 * 本地力矩采集 HTTP 端到端测试：会话创建 → 乱序上传（2 先到缓冲、1 到达后连续入账）
 * → 水位线/包证据查询 → 关会话 → 归档；以及参数校验 400 与版本冲突 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class AcquisitionHttpFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM acquisition_packet");
        jdbcTemplate.update("DELETE FROM acquisition_batch");
        jdbcTemplate.update("DELETE FROM acquisition_session_point");
        jdbcTemplate.update("DELETE FROM acquisition_session");
        jdbcTemplate.update("DELETE FROM late_measurement_event");
        jdbcTemplate.update("DELETE FROM release_record");
        jdbcTemplate.update("DELETE FROM witness_record");
        jdbcTemplate.update("DELETE FROM inspection_record");
        jdbcTemplate.update("DELETE FROM rework_record");
        jdbcTemplate.update("DELETE FROM torque_record");
        jdbcTemplate.update("DELETE FROM torque_tool");
        jdbcTemplate.update("DELETE FROM fastener");
        jdbcTemplate.update("DELETE FROM station_guard");
        jdbcTemplate.update("DELETE FROM operation_card");
        jdbcTemplate.update("DELETE FROM idempotent_request");
    }

    private void prepareCardAndTool() throws Exception {
        mockMvc.perform(post("/api/tools").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolCode\":\"TW-ACQ\",\"calibrationDate\":\"2026-09-26\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cards/dispatch").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-acq-d","cardNo":"HTTP-ACQ1","station":"WS-ACQ",
                                 "productSn":"SN-ACQ","witnessPoint":"N","operator":"op-A",
                                 "fasteners":[{"pointCode":"B1","nominalTorque":100.000},
                                              {"pointCode":"B2","nominalTorque":50.000}]}"""))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("HTTP 端到端：乱序包缓冲→连续前缀入账→水位线查询→关会话→归档")
    void acquisitionFlowOverHttp() throws Exception {
        prepareCardAndTool();

        // 创建采集会话
        mockMvc.perform(post("/api/acquisition/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-acq-s1","sessionNo":"SES-HTTP1",
                                 "cardNo":"HTTP-ACQ1","toolCode":"TW-ACQ",
                                 "pointCodes":["B1","B2"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.watermark").value(0))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 序号 2 先到：缓冲，水位线保持 0
        mockMvc.perform(post("/api/acquisition/sessions/SES-HTTP1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-acq-b1","expectedVersion":1,
                                 "packets":[{"seq":2,"pointCode":"B2",
                                             "measuredAt":"2026-09-26T08:00:02Z","torque":50.000}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(0))
                .andExpect(jsonPath("$.watermark").value(0));

        // 水位线查询：2 在缓冲中
        mockMvc.perform(get("/api/acquisition/sessions/SES-HTTP1/watermark"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watermark").value(0))
                .andExpect(jsonPath("$.bufferedSeqs[0]").value(2));

        // 序号 1 到达：1、2 连续入账，水位线推进到 2
        mockMvc.perform(post("/api/acquisition/sessions/SES-HTTP1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-acq-b2","expectedVersion":2,
                                 "packets":[{"seq":1,"pointCode":"B1",
                                             "measuredAt":"2026-09-26T08:00:01Z","torque":100.000}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(2))
                .andExpect(jsonPath("$.watermark").value(2))
                .andExpect(jsonPath("$.cardStatus").value("TIGHTENED"));

        // 包证据查询
        mockMvc.perform(get("/api/acquisition/sessions/SES-HTTP1/packets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("COMMITTED"));

        // 批次证据查询
        mockMvc.perform(get("/api/acquisition/sessions/SES-HTTP1/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 关会话
        mockMvc.perform(post("/api/acquisition/sessions/SES-HTTP1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-acq-c1\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 自检互检后归档（会话已全部关闭）
        mockMvc.perform(post("/api/inspections").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-acq-i","cardNo":"HTTP-ACQ1",
                                 "selfInspector":"p1","mutualInspector":"p2"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/release").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-acq-r","cardNo":"HTTP-ACQ1",
                                 "releasedBy":"qa-lead"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("HTTP：参数非法 400、版本冲突 409、会话不存在 404")
    void validationOverHttp() throws Exception {
        prepareCardAndTool();
        mockMvc.perform(post("/api/acquisition/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-v-s1","sessionNo":"SES-HTTP2",
                                 "cardNo":"HTTP-ACQ1","toolCode":"TW-ACQ","pointCodes":["B1"]}"""))
                .andExpect(status().isCreated());

        // 缺 requestId：400
        mockMvc.perform(post("/api/acquisition/sessions/SES-HTTP2/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1,
                                 "packets":[{"seq":1,"pointCode":"B1",
                                             "measuredAt":"2026-09-26T08:00:01Z","torque":100.000}]}"""))
                .andExpect(status().isBadRequest());

        // 超过 20 包：400
        StringBuilder packets = new StringBuilder();
        for (int i = 1; i <= 21; i++) {
            if (i > 1) {
                packets.append(',');
            }
            packets.append("{\"seq\":").append(i)
                    .append(",\"pointCode\":\"B1\",\"measuredAt\":\"2026-09-26T08:00:01Z\","
                            + "\"torque\":100.000}");
        }
        mockMvc.perform(post("/api/acquisition/sessions/SES-HTTP2/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-v-b21\",\"expectedVersion\":1,"
                                + "\"packets\":[" + packets + "]}"))
                .andExpect(status().isBadRequest());

        // 版本冲突：409
        mockMvc.perform(post("/api/acquisition/sessions/SES-HTTP2/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-v-bv","expectedVersion":9,
                                 "packets":[{"seq":1,"pointCode":"B1",
                                             "measuredAt":"2026-09-26T08:00:01Z","torque":100.000}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"));

        // 会话不存在：404
        mockMvc.perform(get("/api/acquisition/sessions/SES-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));
    }
}
