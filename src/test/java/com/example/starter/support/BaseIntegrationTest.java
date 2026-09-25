package com.example.starter.support;

import java.util.Arrays;
import java.util.List;

import com.example.starter.order.OrderDtos.ConfirmRequest;
import com.example.starter.order.OrderDtos.DraftRequest;
import com.example.starter.order.OrderDtos.IssueRequest;
import com.example.starter.order.OrderDtos.ReviewRequest;
import com.example.starter.order.OrderDtos.StepSpec;
import com.example.starter.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：真实 H2（MySQL 兼容模式）+ 可控时钟；每个用例前清理业务数据并重置设备状态。
 */
@SpringBootTest
@Import(TestTimeConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected OrderService orderService;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM order_event");
        jdbc.update("DELETE FROM unlock_record");
        jdbc.update("DELETE FROM telemetry_event");
        jdbc.update("DELETE FROM interlock_hit");
        jdbc.update("DELETE FROM order_step");
        jdbc.update("DELETE FROM switching_order");
        resetDevice("BR-1101", "ON");
        resetDevice("DIS-1101", "CLOSED");
        resetDevice("GW-1101", "REMOVED");
        resetDevice("BR-1102", "ON");
        resetDevice("DIS-1102", "CLOSED");
        resetDevice("GW-1102", "REMOVED");
        resetDevice("BUS-1", "ENERGIZED");
        clock.setInstant(TestTimeConfig.T0);
    }

    private void resetDevice(String deviceId, String state) {
        jdbc.update("UPDATE device_state SET state = ?, unlocked = FALSE WHERE device_id = ?",
                state, deviceId);
    }

    /** 拟票并返回操作票ID。 */
    protected long draft(String requestId, String intervalId, StepSpec... steps) {
        return draft(requestId, intervalId, "测试操作票", steps);
    }

    /** 拟票并返回操作票ID。 */
    protected long draft(String requestId, String intervalId, String title, StepSpec... steps) {
        ResponseEntity<String> response = orderService.draft(new DraftRequest(
                requestId, intervalId, title, "拟票人甲", Arrays.asList(steps)));
        try {
            return objectMapper.readTree(response.getBody()).get("orderId").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("拟票响应解析失败: " + response.getBody(), e);
        }
    }

    /** 审核。 */
    protected void review(long orderId) {
        orderService.review(orderId, new ReviewRequest("审核人乙"));
    }

    /** 预发并返回 HTTP 状态码。 */
    protected int issue(long orderId, String requestId) {
        return orderService.issue(orderId, new IssueRequest(requestId)).getStatusCode().value();
    }

    /** 步确认并返回响应。 */
    protected ResponseEntity<String> confirm(long orderId, int seq, String requestId,
                                             String operator, String guardian) {
        return orderService.confirm(orderId, seq,
                new ConfirmRequest(requestId, operator, guardian));
    }

    /** 一步式建票：拟票→审核→预发，返回操作票ID。 */
    protected long draftReviewIssue(String requestIdPrefix, String intervalId, StepSpec... steps) {
        long orderId = draft(requestIdPrefix + "-draft", intervalId, steps);
        review(orderId);
        int status = issue(orderId, requestIdPrefix + "-issue");
        if (status != 200) {
            throw new IllegalStateException("预发失败: " + status);
        }
        return orderId;
    }

    /** 查询操作票状态。 */
    protected String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM switching_order WHERE id = ?",
                String.class, orderId);
    }

    /** 查询步骤状态。 */
    protected String stepStatus(long orderId, int seq) {
        return jdbc.queryForObject(
                "SELECT status FROM order_step WHERE order_id = ? AND seq = ?",
                String.class, orderId, seq);
    }

    /** 查询设备状态。 */
    protected String deviceState(String deviceId) {
        return jdbc.queryForObject("SELECT state FROM device_state WHERE device_id = ?",
                String.class, deviceId);
    }

    /** 统计表行数。 */
    protected int count(String table, String where, Object... args) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args);
        return count == null ? 0 : count;
    }

    protected static StepSpec step(int seq, String deviceId, String action, String expectedState) {
        return new StepSpec(seq, deviceId, action, expectedState);
    }

    protected static List<StepSpec> steps(StepSpec... specs) {
        return Arrays.asList(specs);
    }
}
