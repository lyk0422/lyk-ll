package com.example.starter.gas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口端到端测试（真实 H2 + MockMvc，不启动外部系统）：
 * 覆盖浓度越限锁阀、同键异参 409、双人复开、日结封存、404 与复算查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
class GasHttpApiTest extends AbstractGasIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowViaHttp() throws Exception {
        // 门站初始状态
        mockMvc.perform(get("/api/gas/stations/ST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valveState").value("OPEN"))
                .andExpect(jsonPath("$.alarmActive").value(false));

        // 第一次越限：不锁阀（流量 0，锁阀前不产生勾稽差异）
        mockMvc.perform(post("/api/gas/stations/ST-001/detections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-1","stationCode":"ST-001",
                                 "detectedAt":"2026-09-26T08:00:00","concentrationMg":25.00,"flowM3":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inRange").value(false))
                .andExpect(jsonPath("$.consecutiveViolations").value(1))
                .andExpect(jsonPath("$.valveState").value("OPEN"));

        // 同键异参：409
        mockMvc.perform(post("/api/gas/stations/ST-001/detections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-1","stationCode":"ST-001",
                                 "detectedAt":"2026-09-26T08:00:00","concentrationMg":25.10,"flowM3":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

        // 第二次越限：锁阀，实投为 0
        mockMvc.perform(post("/api/gas/stations/ST-001/detections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-2","stationCode":"ST-001",
                                 "detectedAt":"2026-09-26T08:05:00","concentrationMg":25.20,"flowM3":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alarmTriggered").value(true))
                .andExpect(jsonPath("$.valveState").value("LOCKED"))
                .andExpect(jsonPath("$.injection.actualConsumeG").value(0));

        // 单人复开无效（400 语义下这里返回 200 但 effective=false；阀门保持锁定）
        mockMvc.perform(post("/api/gas/stations/ST-001/valve-commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-V1","stationCode":"ST-001","action":"REOPEN",
                                 "operator1":"alice","occurredAt":"2026-09-26T08:10:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(false))
                .andExpect(jsonPath("$.valveState").value("LOCKED"));

        // 双人复开生效
        mockMvc.perform(post("/api/gas/stations/ST-001/valve-commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-V2","stationCode":"ST-001","action":"REOPEN",
                                 "operator1":"alice","operator2":"bob",
                                 "occurredAt":"2026-09-26T08:15:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(true))
                .andExpect(jsonPath("$.valveState").value("OPEN"))
                .andExpect(jsonPath("$.alarmActive").value(false));

        // 低压联动：RAISE；复开后阀门已开，1000m³ 正常投加 20g，与勾稽闭环
        mockMvc.perform(post("/api/gas/stations/ST-001/linkages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-L1","stationCode":"ST-001",
                                 "detectedAt":"2026-09-26T09:00:00","pressureKpa":180.00,"flowM3":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pressureAction").value("RAISE"))
                .andExpect(jsonPath("$.regulated").value(true))
                .andExpect(jsonPath("$.injection.actualConsumeG").value(20.000000));

        // 同刻重复联动：409
        mockMvc.perform(post("/api/gas/stations/ST-001/linkages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-L2","stationCode":"ST-001",
                                 "detectedAt":"2026-09-26T09:00:00","pressureKpa":180.00,"flowM3":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LINKAGE_TASK_CONFLICT"));

        // 日结：当日有效流量仅联动的 1000m³，理论/实耗均 20g，偏差 0 -> 封存
        mockMvc.perform(post("/api/gas/stations/ST-001/settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-S1","stationCode":"ST-001","settleDate":"2026-09-26"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.deviationRatio").value(0.000000))
                .andExpect(jsonPath("$.totalFlowM3").value(1000.000000))
                .andExpect(jsonPath("$.theoreticalConsumeG").value(20.000000))
                .andExpect(jsonPath("$.actualConsumeG").value(20.000000));

        // 封存后迟到检测
        mockMvc.perform(post("/api/gas/stations/ST-001/detections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"H-LATE","stationCode":"ST-001",
                                 "detectedAt":"2026-09-26T23:00:00","concentrationMg":25.00,"flowM3":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedAsLate").value(true));

        // 浓度曲线复算（迟到数据不入曲线）
        mockMvc.perform(get("/api/gas/stations/ST-001/replay/concentration")
                        .param("settleDate", "2026-09-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockPointCount").value(1))
                .andExpect(jsonPath("$.points.length()").value(2));

        // 未知门站：404
        mockMvc.perform(get("/api/gas/stations/NO-SUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/gas/stations/ST-001/detections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","stationCode":"ST-001",
                                 "detectedAt":"2026-09-26T08:00:00","concentrationMg":20.00,"flowM3":1}
                                """))
                .andExpect(status().isBadRequest());
    }
}
