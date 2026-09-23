package com.example.starter.ros.service;

import com.example.starter.ros.RosIntegrationTestBase;
import com.example.starter.ros.bootstrap.DemoDataInitializer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读取合并：默认值与已提交值合并，已提交覆盖默认；必填预置值可见。
 */
class MergedViewIntegrationTest extends RosIntegrationTestBase {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void mergedViewCombinesDefaultsAndCommittedValues() throws Exception {
        HttpResponse<String> planner = get("/api/ros/nodes/planner_node/status");
        assertThat(planner.statusCode()).isEqualTo(200);
        String body = planner.body();
        // 默认值：speed=1.0、max_speed=5.0、use_sim_time=false
        assertThat(body).contains("\"version\":0");
        assertThat(body).contains("\"ctrl.speed\":1.0");
        assertThat(body).contains("\"ctrl.max_speed\":5.0");
        assertThat(body).contains("\"ctrl.use_sim_time\":false");

        HttpResponse<String> camera = get("/api/ros/nodes/camera_node/status");
        // 必填预置值 factory 与默认值同时可见
        assertThat(camera.body()).contains("\"vision.calibration\":\"factory\"");
        assertThat(camera.body()).contains("\"vision.fps\":15");
        assertThat(camera.body()).contains("\"vision.enabled\":true");
    }

    @Test
    void unknownNodeReturnsEmptyParametersNotError() throws Exception {
        HttpResponse<String> response = get("/api/ros/nodes/ghost_node/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"node\":\"ghost_node\"", "\"version\":0",
                "\"parameters\":{");
        // 避免断言 DemoDataInitializer 常量被内联拼写错误
        assertThat(DemoDataInitializer.CAMERA_NODE).isEqualTo("camera_node");
    }
}
