package com.example.starter.tf.api;

import com.example.starter.tf.TfTestConfiguration;
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
 * TF HTTP 入口的端到端测试（真实 Spring MVC + H2），验证状态码、错误码与环路径响应。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TfTestConfiguration.class})
@AutoConfigureMockMvc
class TfControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM tf_dynamic_sample");
        jdbcTemplate.update("DELETE FROM tf_static_transform");
        jdbcTemplate.update("DELETE FROM tf_edge");
        jdbcTemplate.update("DELETE FROM tf_frame");
    }

    @Test
    void registersFrameAndWritesStaticThenLooksUpOverHttp() throws Exception {
        mockMvc.perform(post("/api/tf/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frame\":\"world\"}"))
                .andExpect(status().isCreated());

        String body = "{\"parentFrame\":\"world\",\"childFrame\":\"base\",\"source\":\"calib\","
                + "\"pose\":{\"tx\":1.5,\"ty\":-2.25,\"tz\":3.0,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1}}";
        mockMvc.perform(post("/api/tf/transforms/static")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tf/lookup")
                        .param("source", "base")
                        .param("target", "world")
                        .param("stampNanos", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("base"))
                .andExpect(jsonPath("$.target").value("world"))
                .andExpect(jsonPath("$.path[0]").value("base"))
                .andExpect(jsonPath("$.path[1]").value("world"))
                .andExpect(jsonPath("$.transform.tx").value(1.5))
                .andExpect(jsonPath("$.transform.ty").value(-2.25))
                .andExpect(jsonPath("$.interpolated").value(false));
    }

    @Test
    void cycleReturns422WithFullCandidatePath() throws Exception {
        writeStatic("world", "a");
        writeStatic("a", "b");
        String closing = "{\"parentFrame\":\"b\",\"childFrame\":\"world\",\"source\":\"x\","
                + "\"pose\":{\"tx\":0,\"ty\":0,\"tz\":0,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1}}";
        mockMvc.perform(post("/api/tf/transforms/static")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closing))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_DETECTED"))
                .andExpect(jsonPath("$.details.cycle[0]").value("world"))
                .andExpect(jsonPath("$.details.cycle[1]").value("a"))
                .andExpect(jsonPath("$.details.cycle[2]").value("b"))
                .andExpect(jsonPath("$.details.cycle[3]").value("world"));
    }

    @Test
    void replayConflictReturns409() throws Exception {
        writeDynamic(100, 1.0);
        String conflicting = "{\"parentFrame\":\"world\",\"childFrame\":\"d\",\"stampNanos\":100,"
                + "\"source\":\"s\",\"pose\":{\"tx\":9,\"ty\":0,\"tz\":0,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1}}";
        mockMvc.perform(post("/api/tf/transforms/dynamic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflicting))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPLAY_CONFLICT"));
    }

    @Test
    void missingFrameReturns404AndBrokenPathReturns422() throws Exception {
        mockMvc.perform(post("/api/tf/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frame\":\"alive\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tf/lookup")
                        .param("source", "alive")
                        .param("target", "ghost")
                        .param("stampNanos", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRAME_NOT_FOUND"));

        mockMvc.perform(post("/api/tf/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frame\":\"tree2\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/tf/lookup")
                        .param("source", "alive")
                        .param("target", "tree2")
                        .param("stampNanos", "1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_TRANSFORM_PATH"));
    }

    @Test
    void dynamicWithoutStampIsBadRequest() throws Exception {
        String body = "{\"parentFrame\":\"world\",\"childFrame\":\"d\",\"source\":\"s\","
                + "\"pose\":{\"tx\":0,\"ty\":0,\"tz\":0,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1}}";
        mockMvc.perform(post("/api/tf/transforms/dynamic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void dynamicInterpolationAndTimeRangeErrorsOverHttp() throws Exception {
        writeDynamic(1000, 0.0);
        writeDynamic(2000, 10.0);

        mockMvc.perform(get("/api/tf/lookup")
                        .param("source", "d")
                        .param("target", "world")
                        .param("stampNanos", "1500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transform.tx").value(5.0))
                .andExpect(jsonPath("$.interpolated").value(true));

        mockMvc.perform(get("/api/tf/lookup")
                        .param("source", "d")
                        .param("target", "world")
                        .param("stampNanos", "999"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BEFORE_EARLIEST_SAMPLE"));

        mockMvc.perform(get("/api/tf/lookup")
                        .param("source", "d")
                        .param("target", "world")
                        .param("stampNanos", "2001"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AFTER_LATEST_SAMPLE"));
    }

    private void writeStatic(String parent, String child) throws Exception {
        String body = "{\"parentFrame\":\"" + parent + "\",\"childFrame\":\"" + child
                + "\",\"source\":\"s\",\"pose\":{\"tx\":0,\"ty\":0,\"tz\":0,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1}}";
        mockMvc.perform(post("/api/tf/transforms/static")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }

    private void writeDynamic(long stamp, double tx) throws Exception {
        String body = "{\"parentFrame\":\"world\",\"childFrame\":\"d\",\"stampNanos\":" + stamp
                + ",\"source\":\"s\",\"pose\":{\"tx\":" + tx
                + ",\"ty\":0,\"tz\":0,\"qx\":0,\"qy\":0,\"qz\":0,\"qw\":1}}";
        mockMvc.perform(post("/api/tf/transforms/dynamic")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }
}
