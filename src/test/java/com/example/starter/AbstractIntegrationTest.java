package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.web.dto.ApiDtos.ArriveRequest;
import com.example.starter.web.dto.ApiDtos.CloseRequest;
import com.example.starter.web.dto.ApiDtos.DispatchRequest;
import com.example.starter.web.dto.ApiDtos.ElevatorRecheckRequest;
import com.example.starter.web.dto.ApiDtos.ElevatorRequest;
import com.example.starter.web.dto.ApiDtos.MaintenanceRequest;
import com.example.starter.web.dto.ApiDtos.RecheckRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import com.example.starter.web.dto.ApiDtos.RescueRequest;
import com.example.starter.web.dto.ApiDtos.StaffRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：独立 H2 内存库（MODE=MySQL），每个用例前清空全部业务表，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper om;
    @Autowired
    protected JdbcTemplate jdbc;

    /** HTTP 响应快照 */
    protected record Resp(int status, JsonNode body) {
        long id() {
            return body.get("id").asLong();
        }

        String text(String field) {
            JsonNode n = body.get(field);
            return n == null || n.isNull() ? null : n.asText();
        }

        boolean bool(String field) {
            return body.get(field).asBoolean();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM rescue_event");
        jdbc.update("DELETE FROM rescue_staff");
        jdbc.update("DELETE FROM stop_resume_chain");
        jdbc.update("DELETE FROM maintenance_record");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM rescue_order");
        jdbc.update("DELETE FROM staff");
        jdbc.update("DELETE FROM elevator");
    }

    // ---------------------------------------------------------------- HTTP 辅助

    protected Resp postJson(String url, Object body) throws Exception {
        MvcResult r = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andReturn();
        return toResp(r);
    }

    protected Resp getJson(String url) throws Exception {
        return toResp(mockMvc.perform(get(url)).andReturn());
    }

    private Resp toResp(MvcResult r) throws Exception {
        String content = r.getResponse().getContentAsString();
        JsonNode node = content.isEmpty() ? null : om.readTree(content);
        return new Resp(r.getResponse().getStatus(), node);
    }

    // ---------------------------------------------------------------- 业务辅助

    protected long createElevator(String code, LocalDate commissionedDay) throws Exception {
        Resp r = postJson("/api/elevators", new ElevatorRequest(code, "合成电梯-" + code, commissionedDay));
        assert r.status() == 201 : "createElevator failed: " + r.status();
        return r.id();
    }

    protected long createStaff(String certNo, boolean certified) throws Exception {
        Resp r = postJson("/api/elevators/staff", new StaffRequest("合成人员-" + certNo, certNo, certified));
        assert r.status() == 201 : "createStaff failed: " + r.status();
        return r.body().asLong();
    }

    protected Resp report(String requestId, long elevatorId, Instant reportedAt) throws Exception {
        return postJson("/api/rescues/report", new ReportRequest(requestId, elevatorId, reportedAt));
    }

    protected Resp dispatch(String requestId, long rescueId, List<Long> staffIds) throws Exception {
        return postJson("/api/rescues/" + rescueId + "/dispatch", new DispatchRequest(requestId, staffIds));
    }

    protected Resp arrive(long rescueId, Instant arrivedAt) throws Exception {
        return postJson("/api/rescues/" + rescueId + "/arrive", new ArriveRequest(arrivedAt));
    }

    protected Resp rescue(long rescueId, List<Long> staffIds) throws Exception {
        return postJson("/api/rescues/" + rescueId + "/rescue", new RescueRequest(staffIds, null));
    }

    protected Resp recheck(long rescueId, boolean passed) throws Exception {
        return postJson("/api/rescues/" + rescueId + "/recheck", new RecheckRequest(passed, null));
    }

    protected Resp close(String requestId, long rescueId) throws Exception {
        return postJson("/api/rescues/" + rescueId + "/close", new CloseRequest(requestId, null));
    }

    protected Resp elevator(long elevatorId) throws Exception {
        return getJson("/api/elevators/" + elevatorId);
    }

    protected Resp chain(long elevatorId) throws Exception {
        return getJson("/api/elevators/" + elevatorId + "/chain");
    }

    protected Resp timeline(long rescueId) throws Exception {
        return getJson("/api/rescues/" + rescueId + "/timeline");
    }

    protected Resp maintenance(String requestId, long elevatorId, LocalDate performedDay,
                               String rescueRequestId) throws Exception {
        return postJson("/api/maintenance",
                new MaintenanceRequest(requestId, elevatorId, performedDay, rescueRequestId, null));
    }

    protected Resp elevatorRecheck(long elevatorId, boolean passed) throws Exception {
        return postJson("/api/elevators/" + elevatorId + "/recheck", new ElevatorRecheckRequest(passed));
    }

    /** 走完 接报→派单→到场→施救→复检→结案 全流程，返回救援单 ID。 */
    protected long fullLifecycle(long elevatorId, long rescuer1, long rescuer2,
                                 String keyPrefix, Instant reportedAt) throws Exception {
        Resp rep = report(keyPrefix + "-report", elevatorId, reportedAt);
        assert rep.status() == 200 : "report failed: " + rep.status() + " " + rep.body();
        long rescueId = rep.id();
        assert dispatch(keyPrefix + "-dispatch", rescueId, List.of(rescuer1, rescuer2)).status() == 200;
        assert arrive(rescueId, reportedAt.plusSeconds(600)).status() == 200;
        assert rescue(rescueId, List.of(rescuer1, rescuer2)).status() == 200;
        assert recheck(rescueId, true).status() == 200;
        assert close(keyPrefix + "-close", rescueId).status() == 200;
        return rescueId;
    }

    protected boolean chainHas(long elevatorId, String action, String reason) throws Exception {
        for (JsonNode e : chain(elevatorId).body()) {
            if (action.equals(e.get("action").asText()) && reason.equals(e.get("reason").asText())) {
                return true;
            }
        }
        return false;
    }

    protected boolean timelineHas(long rescueId, String event) throws Exception {
        for (JsonNode e : timeline(rescueId).body()) {
            if (event.equals(e.get("event").asText())) {
                return true;
            }
        }
        return false;
    }
}
