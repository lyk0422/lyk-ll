package com.example.starter.ride.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.starter.ride.AbstractRideTest;
import com.example.starter.ride.RideStatus;
import com.example.starter.ride.dto.ArchiveRequest;
import com.example.starter.ride.dto.DailyCheckRequest;
import com.example.starter.ride.dto.OpenRequest;
import com.example.starter.ride.dto.ReadingRequest;
import com.example.starter.ride.dto.RecheckRequest;
import com.example.starter.ride.dto.SafetyFaultRequest;
import com.example.starter.ride.dto.TrialRunRequest;

/**
 * 随机端口全栈冒烟：真实 Web 容器 + H2 上走通“日检→试运行→开放→故障停运→复检→复开→归档”。
 * 上下文随用例结束关闭，不依赖外部进程。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class,
        com.example.starter.ride.RideTestConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RideFullStackSmokeTest extends AbstractRideTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        resetAndSeed();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName("真实 HTTP 全链路：日检试运行→载客→压杠故障停运→复检合格→复开，状态唯一正确")
    void fullFlowOverRealHttp() {
        // 1) 未日检禁止开放
        ResponseEntity<Map> early = restTemplate.postForEntity(url("/api/rides/open"),
                new OpenRequest("op-early", "R001"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, early.getStatusCode());

        // 2) 日检逐项合格
        List<DailyCheckRequest.ItemResult> items = new ArrayList<>();
        for (String code : List.of("PRESSURE_BAR", "SEAT_BELT", "INTERLOCK", "BRAKE", "EMERGENCY_STOP")) {
            items.add(new DailyCheckRequest.ItemResult(code, true, "正常"));
        }
        ResponseEntity<Map> dc = restTemplate.postForEntity(url("/api/rides/daily-check"),
                new DailyCheckRequest("dc-1", "R001", items), Map.class);
        assertEquals(HttpStatus.OK, dc.getStatusCode());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) body(dc).get("data")).get("qualified"));

        // 3) 恰 3 次空载试运行
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<Map> tr = restTemplate.postForEntity(url("/api/rides/trial-run"),
                    new TrialRunRequest("tr-" + i, "R001", true, true, true, true), Map.class);
            assertEquals(HttpStatus.OK, tr.getStatusCode());
        }
        // 第 4 次非法
        ResponseEntity<Map> fourth = restTemplate.postForEntity(url("/api/rides/trial-run"),
                new TrialRunRequest("tr-4", "R001", true, true, true, true), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, fourth.getStatusCode());

        // 4) 首次开放
        ResponseEntity<Map> opened = restTemplate.postForEntity(url("/api/rides/open"),
                new OpenRequest("op-1", "R001"), Map.class);
        assertEquals(HttpStatus.OK, opened.getStatusCode());
        assertEquals(RideStatus.OPEN.name(), ((Map<?, ?>) body(opened).get("data")).get("status"));

        // 5) 恰等阈值读数合格
        ResponseEntity<Map> reading = restTemplate.postForEntity(url("/api/rides/readings"),
                new ReadingRequest("rd-eq", "R001", 800.0d, 20.0d), Map.class);
        assertEquals(HttpStatus.OK, reading.getStatusCode());

        // 6) 压杠失效 → 整机立即停运
        ResponseEntity<Map> fault = restTemplate.postForEntity(url("/api/rides/safety-fault"),
                new SafetyFaultRequest("sf-1", "R001", "PRESSURE_BAR"), Map.class);
        assertEquals(HttpStatus.OK, fault.getStatusCode());

        // 7) 未复检直接复开被拒
        ResponseEntity<Map> reopenTooEarly = restTemplate.postForEntity(url("/api/rides/open"),
                new OpenRequest("op-2", "R001"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, reopenTooEarly.getStatusCode());

        // 8) 复检合格 → 复开
        ResponseEntity<Map> recheck = restTemplate.postForEntity(url("/api/rides/recheck"),
                new RecheckRequest("rc-1", "R001", true, true, true), Map.class);
        assertEquals(HttpStatus.OK, recheck.getStatusCode());
        ResponseEntity<Map> reopen = restTemplate.postForEntity(url("/api/rides/open"),
                new OpenRequest("op-2b", "R001"), Map.class);
        assertEquals(HttpStatus.OK, reopen.getStatusCode());

        // 9) 状态查询显示在园开放且未归档（唯一终态不冲突）
        ResponseEntity<Map> statusResp = restTemplate.getForEntity(url("/api/rides/R001"), Map.class);
        Map<?, ?> statusData = (Map<?, ?>) body(statusResp).get("data");
        assertEquals(RideStatus.OPEN.name(), statusData.get("status"));
        assertEquals(1, ((Number) statusData.get("reopenCount")).intValue());
        assertFalse((Boolean) statusData.get("archived"));

        // 10) 归档终态 + 迟到读数
        ResponseEntity<Map> archive = restTemplate.postForEntity(url("/api/rides/archive"),
                new ArchiveRequest("ar-1", "R001"), Map.class);
        assertEquals(HttpStatus.OK, archive.getStatusCode());
        ResponseEntity<Map> late = restTemplate.postForEntity(url("/api/rides/readings"),
                new ReadingRequest("rd-late", "R001", 9999.0d, 999.0d), Map.class);
        Map<?, ?> lateData = (Map<?, ?>) body(late).get("data");
        assertEquals(Boolean.TRUE, lateData.get("lateAfterArchive"));
        assertEquals(RideStatus.ARCHIVED.name(), lateData.get("status"));
        assertTrue((Boolean) ((Map<?, ?>) body(
                restTemplate.getForEntity(url("/api/rides/R001"), Map.class)).get("data"))
                .get("archived"));
    }
}
