package com.example.starter.ros.web;

import com.example.starter.ros.adapter.SimulatedNodeAdapter;
import com.example.starter.ros.support.DemoSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口语义测试：默认值读取、成功 200、静态参数 409、未知参数 400、
 * 同键异参 409、事件续传。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RosParameterControllerTest {

    private static final String CAMERA = DemoSchemaInitializer.CAMERA_NODE;
    private static final String PLANNER = DemoSchemaInitializer.PLANNER_NODE;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SimulatedNodeAdapter nodeAdapter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        nodeAdapter.resetAll();
        jdbcTemplate.update("DELETE FROM committed_param");
        jdbcTemplate.update("DELETE FROM node_state");
        jdbcTemplate.update("DELETE FROM transaction_record");
        jdbcTemplate.update("DELETE FROM param_event");
        jdbcTemplate.update("DELETE FROM subscription_ack");
        jdbcTemplate.update("DELETE FROM pending_reboot");
    }

    @Test
    void getParametersReturnsMergedDefaults() throws Exception {
        mockMvc.perform(get("/api/ros/nodes/{nodeId}/parameters", CAMERA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frame_rate").value(30))
                .andExpect(jsonPath("$.resolution_width").value(640))
                .andExpect(jsonPath("$.device_serial").value("SIM-CAM-0001"));
    }

    @Test
    void twoNodeHotUpdateCommits() throws Exception {
        String body = """
                {
                  "transactionId": "http-tx-1",
                  "nodes": {
                    "%s": {"expectedVersion": 0, "values": {"frame_rate": 60}},
                    "%s": {"expectedVersion": 0, "values": {"max_speed": 1.2}}
                  }
                }
                """.formatted(CAMERA, PLANNER);
        mockMvc.perform(post("/api/ros/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.nodes." + CAMERA + ".status").value("ACCEPTED"))
                .andExpect(jsonPath("$.nodes." + PLANNER + ".status").value("ACCEPTED"))
                .andExpect(jsonPath("$.nodes." + CAMERA + ".currentVersion").value(1))
                .andExpect(jsonPath("$.eventCursor").isNumber());

        mockMvc.perform(get("/api/ros/nodes/{nodeId}/parameters", CAMERA))
                .andExpect(jsonPath("$.frame_rate").value(60));
    }

    @Test
    void staticParameterChangeIs409() throws Exception {
        String body = """
                {
                  "transactionId": "http-tx-static",
                  "nodes": {
                    "%s": {"expectedVersion": 0, "values": {"device_serial": "X"}}
                  }
                }
                """.formatted(CAMERA);
        mockMvc.perform(post("/api/ros/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("device_serial")));
    }

    @Test
    void unknownParameterIs400() throws Exception {
        String body = """
                {
                  "transactionId": "http-tx-schema",
                  "nodes": {
                    "%s": {"expectedVersion": 0, "values": {"ghost": 1}}
                  }
                }
                """.formatted(CAMERA);
        mockMvc.perform(post("/api/ros/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ghost")));
    }

    @Test
    void sameTransactionIdDifferentPayloadIs409AndReplayIsOriginal() throws Exception {
        String first = """
                {
                  "transactionId": "http-tx-clash",
                  "nodes": {
                    "%s": {"expectedVersion": 0, "values": {"frame_rate": 60}}
                  }
                }
                """.formatted(CAMERA);
        mockMvc.perform(post("/api/ros/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        // 同键同参：返回原结果（replayed=true）
        mockMvc.perform(post("/api/ros/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 同键异参：409
        String changed = """
                {
                  "transactionId": "http-tx-clash",
                  "nodes": {
                    "%s": {"expectedVersion": 1, "values": {"frame_rate": 90}}
                  }
                }
                """.formatted(CAMERA);
        mockMvc.perform(post("/api/ros/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("already exists with different parameters")));
    }

    @Test
    void rejectedNodeRollsBackWithPerNodeResults() throws Exception {
        mockMvc.perform(post("/api/ros/nodes/{nodeId}/simulation", PLANNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"REJECT\",\"reason\":\"map mismatch\"}"))
                .andExpect(status().isOk());

        String body = """
                {
                  "transactionId": "http-tx-reject",
                  "nodes": {
                    "%s": {"expectedVersion": 0, "values": {"frame_rate": 60}},
                    "%s": {"expectedVersion": 0, "values": {"max_speed": 1.0}}
                  }
                }
                """.formatted(CAMERA, PLANNER);
        mockMvc.perform(post("/api/ros/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.nodes." + PLANNER + ".status").value("REJECTED"))
                .andExpect(jsonPath("$.nodes." + CAMERA + ".status").value("ACCEPTED"))
                .andExpect(jsonPath("$.eventCursor").doesNotExist());
    }
}
