package com.example.starter.epidemic;

import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.CloseClusterRequest;
import com.example.starter.epidemic.api.ClusterEventResponse;
import com.example.starter.epidemic.api.ConcludeInvestigationRequest;
import com.example.starter.epidemic.api.CreateInvestigationRequest;
import com.example.starter.epidemic.api.InvestigationResponse;
import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.api.TimelineEntryResponse;
import com.example.starter.epidemic.domain.ClusterStatus;
import com.example.starter.epidemic.domain.Conclusion;
import com.example.starter.epidemic.domain.InvestigationStatus;
import com.example.starter.epidemic.service.BusinessRuleException;
import com.example.starter.epidemic.service.ClusterService;
import com.example.starter.epidemic.service.EpidemicCardService;
import com.example.starter.epidemic.service.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚集性分析测试：72 小时窗口 3 例触发、协查单幂等与并发唯一、结论回写、
 * 关闭终态与迟到归并、时间线复算稳定。均基于真实 H2（MySQL 兼容模式）验证。
 */
class ClusterAnalysisTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService cardService;

    @Autowired
    private ClusterService clusterService;

    /** 在指定时刻向指定场所提交一张报告卡（乙类，诊断早 1 小时，及时）。 */
    private CardResponse submitCard(String requestId, String patientId, String siteCode,
                                    Instant submitAt) {
        clock.setFixed(submitAt);
        return cardService.submit(new SubmitCardRequest(
                requestId, patientId, "合成患者-" + patientId, 35, "M", "B02", "CONFIRMED",
                LocalDate.of(2026, 9, 19), submitAt.minusSeconds(3600), "H001", "张报告",
                "示范市第一中学家属院 3 栋", "13800000000", null, siteCode));
    }

    private String onlyEventNo() {
        return jdbc.queryForObject("SELECT event_no FROM cluster_event", String.class);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    @Test
    void exactlyThreeCasesTriggerClusterEvent() {
        submitCard("req-c1", "P-C1", "S001", BASE_TIME);
        submitCard("req-c2", "P-C2", "S001", BASE_TIME.plusSeconds(3600));
        assertEquals(0, count("SELECT COUNT(*) FROM cluster_event"), "2 例不应触发聚集事件");

        CardResponse third = submitCard("req-c3", "P-C3", "S001", BASE_TIME.plusSeconds(2 * 3600));
        assertEquals("S001", third.exposureSiteCode());
        assertEquals(1, count("SELECT COUNT(*) FROM cluster_event"), "恰 3 例必须触发聚集事件");

        ClusterEventResponse event = clusterService.getEvent(onlyEventNo());
        assertEquals(ClusterStatus.OPEN, event.status());
        assertEquals("S001", event.siteCode());
        assertEquals("示范市第一中学食堂", event.siteName());
        assertEquals(3, event.cardCount());
        assertEquals(BASE_TIME, event.firstCaseAt(), "首例时刻为窗口内最早报告时刻");
        assertEquals(BASE_TIME.plusSeconds(2 * 3600), event.lastCaseAt(), "末例时刻为窗口内最晚报告时刻");
        assertEquals(3, event.cardNos().size());

        // 事件生成即自动推送协查单到相关机构（涉及卡片的报告机构 H001）
        List<InvestigationResponse> investigations =
                clusterService.getEventInvestigations(event.eventNo());
        assertEquals(1, investigations.size());
        InvestigationResponse auto = investigations.get(0);
        assertEquals(InvestigationStatus.OPEN, auto.status());
        assertEquals("auto-" + event.eventNo(), auto.requestId());
        assertEquals(List.of("H001"), auto.targets());
        assertEquals("system", auto.creator());
    }

    @Test
    void windowIsSeventyTwoHours_oldCardsDoNotCount() {
        // 73 小时前的报告不在 72 小时窗口内
        submitCard("req-old", "P-OLD", "S002", BASE_TIME.minusSeconds(73 * 3600));
        submitCard("req-w1", "P-W1", "S002", BASE_TIME);
        submitCard("req-w2", "P-W2", "S002", BASE_TIME.plusSeconds(1800));
        assertEquals(0, count("SELECT COUNT(*) FROM cluster_event"),
                "窗口外旧卡不计入，2 例不应触发");

        submitCard("req-w3", "P-W3", "S002", BASE_TIME.plusSeconds(3600));
        assertEquals(1, count("SELECT COUNT(*) FROM cluster_event"));
        ClusterEventResponse event = clusterService.getEvent(onlyEventNo());
        assertEquals(3, event.cardCount(), "事件只含 72 小时窗口内 3 例");
        assertEquals(BASE_TIME, event.firstCaseAt());
        assertEquals(BASE_TIME.plusSeconds(3600), event.lastCaseAt());
    }

    @Test
    void fourthCardAttachesToOpenEventAndAdvancesLastCase() {
        submitCard("req-a1", "P-A1", "S001", BASE_TIME);
        submitCard("req-a2", "P-A2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-a3", "P-A3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();

        CardResponse fourth = submitCard("req-a4", "P-A4", "S001", BASE_TIME.plusSeconds(1800));
        ClusterEventResponse event = clusterService.getEvent(eventNo);
        assertEquals(4, event.cardCount());
        assertEquals(BASE_TIME.plusSeconds(1800), event.lastCaseAt(), "新卡归入推进末例时刻");
        assertTrue(event.cardNos().contains(fourth.cardNo()));
        assertEquals(1, count("SELECT COUNT(*) FROM cluster_event"), "未关闭事件不重复生成");
    }

    @Test
    void autoMatchByAddressKeywordAndUnknownSiteRejected() {
        clock.setFixed(BASE_TIME);
        CardResponse matched = cardService.submit(new SubmitCardRequest(
                "req-kw", "P-KW", "合成患者-KW", 40, "F", "B02", "CONFIRMED",
                LocalDate.of(2026, 9, 19), BASE_TIME.minusSeconds(3600), "H001", "张报告",
                "示范市康养养老院 2 号楼", "13800000000", null, null));
        assertEquals("S003", matched.exposureSiteCode(), "现住址命中关键词应自动归并场所");

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> submitCard("req-bad-site", "P-BAD", "S999", BASE_TIME));
        assertTrue(ex.getMessage().contains("暴露场所不存在"));
    }

    @Test
    void investigationIdempotency_replayReturnsFirstAndConflict409() {
        submitCard("req-i1", "P-I1", "S001", BASE_TIME);
        submitCard("req-i2", "P-I2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-i3", "P-I3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();
        // 先结案自动协查单，腾出进行中名额
        long autoId = clusterService.getEventInvestigations(eventNo).get(0).id();
        clusterService.concludeInvestigation(autoId,
                new ConcludeInvestigationRequest("EXCLUDED", "cdc-01", "先排除"));

        CreateInvestigationRequest request =
                new CreateInvestigationRequest("req-inv-1", "复核协查", List.of("H001", "H002"));
        InvestigationResponse first = clusterService.createInvestigation(eventNo, request);
        assertEquals(InvestigationStatus.OPEN, first.status());
        assertEquals(List.of("H001", "H002"), first.targets());

        // 同键同参重放：返回首次结果，不产生新协查单
        InvestigationResponse replay = clusterService.createInvestigation(eventNo, request);
        assertEquals(first, replay);
        assertEquals(2, count("SELECT COUNT(*) FROM investigation"),
                "重放不得产生新协查单（自动 1 + 手工 1）");

        // 同键异参：409 且不产生新协查单
        CreateInvestigationRequest tampered =
                new CreateInvestigationRequest("req-inv-1", "篡改说明", List.of("H001"));
        assertThrows(IdempotencyConflictException.class,
                () -> clusterService.createInvestigation(eventNo, tampered));
        assertEquals(2, count("SELECT COUNT(*) FROM investigation"), "409 冲突不得产生新协查单");
    }

    @Test
    void onlyOneOpenInvestigationPerEvent() {
        submitCard("req-o1", "P-O1", "S001", BASE_TIME);
        submitCard("req-o2", "P-O2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-o3", "P-O3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();

        // 自动协查单进行中时，手工生成被拒
        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () ->
                clusterService.createInvestigation(eventNo,
                        new CreateInvestigationRequest("req-inv-x", null, null)));
        assertTrue(ex.getMessage().contains("只允许一张进行中协查单"));

        // 结案后可再生成
        long autoId = clusterService.getEventInvestigations(eventNo).get(0).id();
        clusterService.concludeInvestigation(autoId,
                new ConcludeInvestigationRequest("CONFIRMED", "cdc-01", "确认聚集"));
        InvestigationResponse manual = clusterService.createInvestigation(eventNo,
                new CreateInvestigationRequest("req-inv-y", "二次协查", null));
        assertEquals(InvestigationStatus.OPEN, manual.status());
        assertEquals(List.of("H001"), manual.targets(), "默认推送事件涉及卡片的报告机构");
    }

    @Test
    void concludeWritesBackEventStatusInSameTransaction() {
        submitCard("req-t1", "P-T1", "S001", BASE_TIME);
        submitCard("req-t2", "P-T2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-t3", "P-T3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();
        long autoId = clusterService.getEventInvestigations(eventNo).get(0).id();

        clock.setFixed(BASE_TIME.plusSeconds(7200));
        InvestigationResponse concluded = clusterService.concludeInvestigation(autoId,
                new ConcludeInvestigationRequest("CONFIRMED", "cdc-01", "现场核实确认"));
        assertEquals(InvestigationStatus.CONCLUDED, concluded.status());
        assertEquals(Conclusion.CONFIRMED, concluded.conclusion());
        assertEquals(BASE_TIME.plusSeconds(7200), concluded.concludedAt());

        ClusterEventResponse event = clusterService.getEvent(eventNo);
        assertEquals(ClusterStatus.CONFIRMED, event.status(), "协查结论须同事务回写事件状态");

        // 重复结案拒绝
        assertThrows(BusinessRuleException.class, () -> clusterService.concludeInvestigation(
                autoId, new ConcludeInvestigationRequest("EXCLUDED", "cdc-02", null)));
    }

    @Test
    void closeIsTerminal_idempotentAndLateCardsFormNewEvent() {
        submitCard("req-z1", "P-Z1", "S001", BASE_TIME);
        submitCard("req-z2", "P-Z2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-z3", "P-Z3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();
        long autoId = clusterService.getEventInvestigations(eventNo).get(0).id();
        clusterService.concludeInvestigation(autoId,
                new ConcludeInvestigationRequest("CONFIRMED", "cdc-01", null));

        clock.setFixed(BASE_TIME.plusSeconds(3 * 3600));
        ClusterEventResponse closed = clusterService.closeEvent(eventNo,
                new CloseClusterRequest("cdc-01", "处置完毕"));
        assertEquals(ClusterStatus.CLOSED, closed.status());
        assertEquals(BASE_TIME.plusSeconds(3 * 3600), closed.closedAt());

        // 重复关闭：幂等成功，返回相同结果
        ClusterEventResponse again = clusterService.closeEvent(eventNo,
                new CloseClusterRequest("cdc-02", "重复关闭"));
        assertEquals(closed, again);
        assertEquals(1, count("SELECT COUNT(*) FROM cluster_event"));

        // 关闭后迟到的报告卡：只记迟到事件，不并入已关闭事件
        submitCard("req-late1", "P-LATE1", "S001", BASE_TIME.plusSeconds(4 * 3600));
        assertEquals(1, count("SELECT COUNT(*) FROM cluster_late_record"));
        assertEquals(1, count("SELECT COUNT(*) FROM cluster_event"));
        assertEquals(3, clusterService.getEvent(eventNo).cardCount(), "已关闭事件不再接收新卡");

        // 迟到卡可另行归并成新事件
        submitCard("req-late2", "P-LATE2", "S001", BASE_TIME.plusSeconds(5 * 3600));
        submitCard("req-late3", "P-LATE3", "S001", BASE_TIME.plusSeconds(6 * 3600));
        assertEquals(3, count("SELECT COUNT(*) FROM cluster_late_record"));
        assertEquals(2, count("SELECT COUNT(*) FROM cluster_event"), "迟到 3 例应另行生成新事件");

        String newEventNo = jdbc.queryForObject(
                "SELECT event_no FROM cluster_event WHERE status = 'OPEN'", String.class);
        assertNotEquals(eventNo, newEventNo);
        ClusterEventResponse newEvent = clusterService.getEvent(newEventNo);
        assertEquals(3, newEvent.cardCount());
        assertEquals(BASE_TIME.plusSeconds(4 * 3600), newEvent.firstCaseAt());
        assertEquals(BASE_TIME.plusSeconds(6 * 3600), newEvent.lastCaseAt());
    }

    @Test
    void closeRejectedWhileInvestigationOpen() {
        submitCard("req-r1", "P-R1", "S001", BASE_TIME);
        submitCard("req-r2", "P-R2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-r3", "P-R3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> clusterService.closeEvent(eventNo, new CloseClusterRequest("cdc-01", null)));
        assertTrue(ex.getMessage().contains("进行中协查单"));
        assertEquals(ClusterStatus.OPEN, clusterService.getEvent(eventNo).status());
    }

    @Test
    void timelineIsRecomputableAndStable() {
        CardResponse c1 = submitCard("req-tl1", "P-TL1", "S001", BASE_TIME);
        submitCard("req-tl2", "P-TL2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-tl3", "P-TL3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();
        long autoId = clusterService.getEventInvestigations(eventNo).get(0).id();
        clock.setFixed(BASE_TIME.plusSeconds(7200));
        clusterService.concludeInvestigation(autoId,
                new ConcludeInvestigationRequest("EXCLUDED", "cdc-01", "排除"));
        clusterService.closeEvent(eventNo, new CloseClusterRequest("cdc-01", "办结"));

        List<TimelineEntryResponse> first = clusterService.getTimeline(eventNo);
        List<TimelineEntryResponse> second = clusterService.getTimeline(eventNo);
        assertEquals(first, second, "时间线重复查询必须稳定一致");

        List<String> types = first.stream().map(TimelineEntryResponse::type).toList();
        assertEquals(3, types.stream().filter("CARD_ATTACHED"::equals).count());
        assertTrue(types.contains("EVENT_CREATED"));
        assertTrue(types.contains("INVESTIGATION_CREATED"));
        assertTrue(types.contains("INVESTIGATION_CONCLUDED"));
        assertTrue(types.contains("EVENT_CLOSED"));
        assertTrue(first.stream().anyMatch(e -> e.detail().contains(c1.cardNo())));
    }

    @Test
    void concurrentTriggering_singleEventForSite() throws Exception {
        submitCard("req-base1", "P-BASE1", "S001", BASE_TIME);
        submitCard("req-base2", "P-BASE2", "S001", BASE_TIME.plusSeconds(600));
        clock.setFixed(BASE_TIME.plusSeconds(1200));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<CardResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return submitCard("req-conc-" + idx, "P-CONC-" + idx, "S001",
                            BASE_TIME.plusSeconds(1200));
                }));
            }
            for (Future<CardResponse> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, count("SELECT COUNT(*) FROM cluster_event"),
                "并发触发只能生成一个聚集事件");
        ClusterEventResponse event = clusterService.getEvent(onlyEventNo());
        assertEquals(4, event.cardCount(), "两张并发卡都应归入同一事件");
        assertEquals(4, count("SELECT COUNT(*) FROM cluster_event_card"));
        assertEquals(1, count("SELECT COUNT(*) FROM investigation"), "自动协查单只有一张");
    }

    @Test
    void concurrentInvestigationCreation_onlyOneSucceeds() throws Exception {
        submitCard("req-ci1", "P-CI1", "S001", BASE_TIME);
        submitCard("req-ci2", "P-CI2", "S001", BASE_TIME.plusSeconds(600));
        submitCard("req-ci3", "P-CI3", "S001", BASE_TIME.plusSeconds(1200));
        String eventNo = onlyEventNo();
        long autoId = clusterService.getEventInvestigations(eventNo).get(0).id();
        clusterService.concludeInvestigation(autoId,
                new ConcludeInvestigationRequest("EXCLUDED", "cdc-01", null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<InvestigationResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return clusterService.createInvestigation(eventNo,
                            new CreateInvestigationRequest("req-conc-inv-" + idx, "并发协查", null));
                }));
            }
            int success = 0;
            int rejected = 0;
            for (Future<InvestigationResponse> future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    assertTrue(e.getCause() instanceof BusinessRuleException,
                            "落败方须为业务异常，实际: " + e.getCause());
                    rejected++;
                }
            }
            assertEquals(1, success, "并发生成协查单只能一个成功");
            assertEquals(1, rejected);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("SELECT COUNT(*) FROM investigation WHERE status = 'OPEN'"),
                "同一事件同时刻只允许一张进行中协查单");
        assertEquals(2, count("SELECT COUNT(*) FROM investigation"));
    }
}
