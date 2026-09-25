package com.example.starter.schedule.web;

import com.example.starter.schedule.domain.ContactWindow;
import com.example.starter.schedule.repository.MasterDataRepository;
import com.example.starter.schedule.support.MutableClock;
import com.example.starter.schedule.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 契约测试：成功编排、同级冲突拒绝、同键异参 409、发布冻结 409、
 * 资源不存在 404、参数校验 400、回报去重。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:schedule_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "schedule.synthetic-seed.enabled=false",
        "schedule.missed-report.enabled=false"
})
class ScheduleControllerTest {

    private static final String DAY = "2026-09-25";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MasterDataRepository masterData;

    @Autowired
    @SuppressWarnings("unused")
    private MutableClock mutableClock;

    private long satA;
    private long satB;
    private long station;

    @BeforeEach
    void setUp() {
        for (String table : List.of(
                "contact_report_event", "contact_report", "schedule_entry", "schedule_request",
                "schedule_version", "station_day_lock", "contact_window",
                "ground_station", "satellite")) {
            jdbc.update("DELETE FROM " + table);
        }
        satA = masterData.insertSatellite("SAT-A", "卫星A");
        satB = masterData.insertSatellite("SAT-B", "卫星B");
        station = masterData.insertStation("GS-1", "地面站1");
    }

    private long window(long sat, int sh, int sm, int eh, int em) {
        LocalDate day = LocalDate.parse(DAY);
        return masterData.insertWindow(sat, station,
                day.atTime(sh, sm).toInstant(ZoneOffset.UTC),
                day.atTime(eh, em).toInstant(ZoneOffset.UTC), DAY);
    }

    @Test
    @DisplayName("正常编排返回 200 与 SCHEDULED")
    void submitOk() throws Exception {
        long w = window(satA, 2, 0, 2, 15);
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r1","satelliteCode":"SAT-A","stationCode":"GS-1",
                                 "windowId":%d,"priority":3,"emergency":false}""".formatted(w)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SCHEDULED"))
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.entryId").isNumber());
    }

    @Test
    @DisplayName("同优先级后到者返回 REJECTED 与冲突详情")
    void samePriorityRejectedWithConflictDetail() throws Exception {
        long w1 = window(satA, 2, 0, 2, 15);
        long w2 = window(satB, 2, 5, 2, 20);
        apply("r1", "SAT-A", w1, 3, false);

        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r2","satelliteCode":"SAT-B","stationCode":"GS-1",
                                 "windowId":%d,"priority":3,"emergency":false}""".formatted(w2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.conflicts[0].requestId").value("r1"))
                .andExpect(jsonPath("$.conflicts[0].reason")
                        .value("SAME_PRIORITY_FIRST_COME_FIRST_SERVED"));
    }

    @Test
    @DisplayName("同键异参返回 409 IDEMPOTENCY_FINGERPRINT_MISMATCH")
    void sameKeyDifferentParamsReturns409() throws Exception {
        long w1 = window(satA, 2, 0, 2, 15);
        long w2 = window(satA, 3, 0, 3, 10);
        apply("rk", "SAT-A", w1, 3, false);

        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rk","satelliteCode":"SAT-A","stationCode":"GS-1",
                                 "windowId":%d,"priority":4,"emergency":false}""".formatted(w2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_FINGERPRINT_MISMATCH"));
    }

    @Test
    @DisplayName("发布冻结后普通申请返回 409 DAY_FROZEN，紧急插入成功并产生新版本")
    void frozenDayRejectsNormalAndAllowsEmergency() throws Exception {
        long w1 = window(satA, 2, 0, 2, 15);
        long w2 = window(satB, 5, 0, 5, 20);
        apply("r1", "SAT-A", w1, 3, false);

        mockMvc.perform(post("/api/schedule/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationCode":"GS-1","dayKey":"%s"}""".formatted(DAY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.alreadyPublished").value(false));

        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r2","satelliteCode":"SAT-B","stationCode":"GS-1",
                                 "windowId":%d,"priority":3,"emergency":false}""".formatted(w2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAY_FROZEN"));

        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r3","satelliteCode":"SAT-B","stationCode":"GS-1",
                                 "windowId":%d,"priority":4,"emergency":true}""".formatted(w2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SCHEDULED"));

        mockMvc.perform(get("/api/schedule/stations/GS-1/days/{day}/versions", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNo").value(1))
                .andExpect(jsonPath("$[1].versionNo").value(2));
    }

    @Test
    @DisplayName("资源不存在返回 404；非法优先级返回 400")
    void notFoundAndValidationErrors() throws Exception {
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rx","satelliteCode":"NOPE","stationCode":"GS-1",
                                 "windowId":1,"priority":3,"emergency":false}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        long w = window(satA, 2, 0, 2, 15);
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ry","satelliteCode":"SAT-A","stationCode":"GS-1",
                                 "windowId":%d,"priority":9,"emergency":false}""".formatted(w)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("重复发布幂等成功；重复回报去重不新增记录")
    void idempotentPublishAndDuplicateReport() throws Exception {
        long w = window(satA, 2, 0, 2, 15);
        apply("rr", "SAT-A", w, 3, false);

        mockMvc.perform(post("/api/schedule/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationCode":"GS-1","dayKey":"%s"}""".formatted(DAY)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/schedule/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationCode":"GS-1","dayKey":"%s"}""".formatted(DAY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyPublished").value(true));

        String reportBody = """
                {"windowId":%d,"status":"SUCCESS","remark":"ok"}""".formatted(w);
        mockMvc.perform(post("/api/schedule/stations/GS-1/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        mockMvc.perform(post("/api/schedule/stations/GS-1/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report WHERE station_id = ? AND window_id = ?",
                Integer.class, station, w);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    private void apply(String requestId, String satCode, long windowId, int priority,
                       boolean emergency) throws Exception {
        mockMvc.perform(post("/api/schedule/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","satelliteCode":"%s","stationCode":"GS-1",
                         "windowId":%d,"priority":%d,"emergency":%s}"""
                        .formatted(requestId, satCode, windowId, priority, emergency)))
                .andExpect(status().isOk());
    }
}
