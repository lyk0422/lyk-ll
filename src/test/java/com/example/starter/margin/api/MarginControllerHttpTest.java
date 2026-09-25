package com.example.starter.margin.api;

import com.example.starter.margin.repo.AccountDataRepository;
import com.example.starter.margin.support.MarginClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端 HTTP 测试：真实 Spring MVC + H2，覆盖装配、追保、幂等入账、409/400 错误语义、
 * 逾期强平阶梯与恢复的完整调用链。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarginControllerHttpTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private AccountDataRepository accountData;
    @Autowired
    private MarginClock clock;

    @BeforeEach
    void clean() {
        accountData.deleteAll();
        clock.reset();
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/margin" + path;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private Map<String, Object> body(ResponseEntity<Map> resp) {
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return resp.getBody();
    }

    @Test
    void fullFlow_marginCall_overdueLiquidationLadder_andRecovery() {
        // 1) 装配合成账户：资产 1100（恰好 110%）、负债 500/300/200
        String setupBody = """
                {
                  "accountNo":"W1","clientName":"王五","currency":"CNY","totalAssets":"1100.00",
                  "liabilities":[
                    {"liabilityNo":"L-BIG","amount":"500.00"},
                    {"liabilityNo":"L-MID","amount":"300.00"},
                    {"liabilityNo":"L-SML","amount":"200.00"}
                  ]
                }
                """;
        assertThat(rest.postForEntity(url("/accounts"), new HttpEntity<>(setupBody, json()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 2) 评估：恰好 110% 走追保（不直接强平）
        Map<String, Object> assessed = body(rest.postForEntity(url("/accounts/W1/assess"),
                new HttpEntity<>("", json()), Map.class));
        assertThat(assessed).containsEntry("action", "MARGIN_NOTICE");
        assertThat(assessed.get("noticeCreated")).isEqualTo(true);
        String deadline = (String) assessed.get("deadline");
        assertThat(deadline).isNotNull();

        // 3) 拨动时钟到截止时刻（15:00 整点即逾期）→ 生成 OVERDUE 强平
        ResponseEntity<Map> clockResp = rest.exchange(url("/admin/clock"),
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("{\"iso\":\"" + deadline + "\"}", json()), Map.class);
        assertThat(clockResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> overdue = body(rest.postForEntity(url("/accounts/W1/overdue"),
                new HttpEntity<>("", json()), Map.class));
        assertThat(overdue).containsEntry("action", "LIQUIDATE");
        long jobId = ((Number) overdue.get("jobId")).longValue();

        // 4) 阶梯强平：先全额平最大笔 500（600/500=120% 仍不足），再部分平 250（350/250=140%）即停
        Map<String, Object> executed = body(rest.postForEntity(url("/liquidations/" + jobId + "/execute"),
                new HttpEntity<>("", json()), Map.class));
        assertThat(executed).containsEntry("status", "EXECUTED");
        assertThat(executed.get("finalRatio")).isEqualTo(1.4000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) executed.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("liabilityNo")).isEqualTo("L-BIG");
        assertThat(items.get(0).get("repaidAmount")).isEqualTo(500.00);
        assertThat(items.get(0).get("ratioAfterPct")).isEqualTo(1.2000);
        assertThat(items.get(1).get("liabilityNo")).isEqualTo("L-MID");
        assertThat(items.get(1).get("repaidAmount")).isEqualTo(250.00);

        // 5) 恢复：强平终态后的保证金计入余下负债（250 未偿）重算：350+100=450，450/250=180%
        String recovery = """
                {"requestId":"D3","accountNo":"W1","amount":"100.00","currency":"CNY"}""";
        Map<String, Object> d3 = body(rest.postForEntity(url("/deposits"),
                new HttpEntity<>(recovery, json()), Map.class));
        assertThat(d3.get("result")).isEqualTo("APPLIED_TO_REMAINING");
        assertThat(d3.get("ratioAfter")).isEqualTo(1.8000);

        Map<String, Object> view = body(rest.getForEntity(url("/accounts/W1"), Map.class));
        assertThat(view.get("ratio")).isEqualTo(1.8000);
        assertThat(view.get("executedJobStatus")).isEqualTo("EXECUTED");

        // 6) 终态作业只读查询，指令仍为 2 条，不重复执行
        Map<String, Object> jobView = body(rest.getForEntity(url("/liquidations/" + jobId), Map.class));
        assertThat(jobView).containsEntry("status", "EXECUTED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> storedItems = (List<Map<String, Object>>) jobView.get("items");
        assertThat(storedItems).hasSize(2);
    }

    @Test
    void depositAcceptedIdempotentReplay_conflict409_validation400() {
        // 1250/1000 = 125% 追保账户
        String setupBody = """
                {"accountNo":"W3","clientName":"孙七","currency":"CNY","totalAssets":"1250.00",
                 "liabilities":[{"liabilityNo":"L1","amount":"1000.00"}]}""";
        body(rest.postForEntity(url("/accounts"), new HttpEntity<>(setupBody, json()), Map.class));
        body(rest.postForEntity(url("/accounts/W3/assess"),
                new HttpEntity<>("", json()), Map.class));

        // 首次入账 150：1400/1000=140%，通知解除
        String deposit = """
                {"requestId":"D1","accountNo":"W3","amount":"150.00","currency":"CNY"}""";
        Map<String, Object> first = body(rest.postForEntity(url("/deposits"),
                new HttpEntity<>(deposit, json()), Map.class));
        assertThat(first.get("result")).isEqualTo("ACCEPTED");
        assertThat(first.get("replayed")).isEqualTo(false);
        assertThat(first.get("ratioAfter")).isEqualTo(1.4000);
        assertThat(first.get("noticeResolved")).isEqualTo(true);

        // 同键同参重放：replayed=true，不重复入账
        Map<String, Object> replay = body(rest.postForEntity(url("/deposits"),
                new HttpEntity<>(deposit, json()), Map.class));
        assertThat(replay.get("replayed")).isEqualTo(true);
        Map<String, Object> view = body(rest.getForEntity(url("/accounts/W3"), Map.class));
        assertThat(view.get("totalAssets")).isEqualTo(1400.00);

        // 同键异参（金额不同）→ 409
        String conflict = """
                {"requestId":"D1","accountNo":"W3","amount":"500.00","currency":"CNY"}""";
        assertThat(rest.postForEntity(url("/deposits"), new HttpEntity<>(conflict, json()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 同键异账户 → 409
        String conflictAcct = """
                {"requestId":"D1","accountNo":"OTHER","amount":"150.00","currency":"CNY"}""";
        assertThat(rest.postForEntity(url("/deposits"), new HttpEntity<>(conflictAcct, json()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 金额非法（0 元）→ 400
        String bad = """
                {"requestId":"D2","accountNo":"W3","amount":"0","currency":"CNY"}""";
        assertThat(rest.postForEntity(url("/deposits"), new HttpEntity<>(bad, json()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 400/409 均未产生新入账
        assertThat(view).containsEntry("totalAssets", 1400.00);
    }

    @Test
    void duplicateSetupAndUnknownAccountRejected() {
        String setupBody = """
                {"accountNo":"W2","clientName":"钱六","currency":"CNY","totalAssets":"1500.00",
                 "liabilities":[{"liabilityNo":"L1","amount":"1000.00"}]}""";
        body(rest.postForEntity(url("/accounts"), new HttpEntity<>(setupBody, json()), Map.class));
        // 重复装配 → 400
        ResponseEntity<String> dup = rest.postForEntity(url("/accounts"),
                new HttpEntity<>(setupBody, json()), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 不存在账户 → 400
        ResponseEntity<String> missing = rest.getForEntity(url("/accounts/NOPE"), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
