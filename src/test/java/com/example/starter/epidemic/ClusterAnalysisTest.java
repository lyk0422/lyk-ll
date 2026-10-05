package com.example.starter.epidemic;

import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.ClusterCloseResponse;
import com.example.starter.epidemic.api.ClusterEventResponse;
import com.example.starter.epidemic.api.ClusterEventSummary;
import com.example.starter.epidemic.api.ConcludeInvestigationRequest;
import com.example.starter.epidemic.api.CreateInvestigationRequest;
import com.example.starter.epidemic.api.InvestigationResponse;
import com.example.starter.epidemic.domain.ClusterStatus;
import com.example.starter.epidemic.domain.InvestigationStatus;
import com.example.starter.epidemic.service.BusinessRuleException;
import com.example.starter.epidemic.service.ClusterService;
import com.example.starter.epidemic.service.EpidemicCardService;
import com.example.starter.epidemic.service.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚集性分析主流程测试：72 小时 3 例触发（恰 3 例触发）、协查单幂等与唯一进行中、
 * 结论回写同事务、关闭终态与迟到卡另行归并、时间线查询稳定。
 */
class ClusterAnalysisTest extends AbstractIntegrationTest {

    private static final String SITE_ADDR = "示范市第一小学 3 班";
    private static final String OTHER_ADDR = "示范市滨河路 9 号";

    @Autowired
    private EpidemicCardService cardService;

    @Autowired
    private ClusterService clusterService;

    /** 在指定时刻提交一张归入 S001（第一小学）的报告卡。 */
    private CardResponse submitAtSite(String tag, Instant reportedAt) {
        clock.setFixed(reportedAt);
        return cardService.submit(submitRequestAt("req-" + tag, "P-" + tag, "B02",
                reportedAt.minusSeconds(1800), SITE_ADDR));
    }

    /** 恰 3 例触发：构造 3 例聚集事件，返回事件编号。 */
    private long triggerEvent() {
        submitAtSite("t1-" + uid(), BASE_TIME);
        submitAtSite("t2-" + uid(), BASE_TIME.plusSeconds(3600));
        submitAtSite("t3-" + uid(), BASE_TIME.plusSeconds(7200));
        List<ClusterEventSummary> events = clusterService.listEvents();
        assertEquals(1, events.size(), "恰 3 例必须生成一个聚集事件");
        return events.get(0).id();
    }

    @Test
    void exactlyThreeReportsTriggerClusterEvent() {
        submitAtSite("a1", BASE_TIME);
        submitAtSite("a2", BASE_TIME.plusSeconds(3600));
        assertTrue(clusterService.listEvents().isEmpty(), "2 例不得触发聚集事件");

        // 不匹配任何场所关键词的报告卡不参与归并计数。
        clock.setFixed(BASE_TIME.plusSeconds(5400));
        cardService.submit(submitRequestAt("req-other", "P-OTHER", "B02",
                BASE_TIME, OTHER_ADDR));
        assertTrue(clusterService.listEvents().isEmpty(), "未归并场所的卡不得计入触发");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM card_exposure", Integer.class),
                "只有命中场所关键词的卡才记录归并");

        CardResponse third = submitAtSite("a3", BASE_TIME.plusSeconds(7200));
        List<ClusterEventSummary> events = clusterService.listEvents();
        assertEquals(1, events.size(), "恰 3 例必须触发聚集事件");

        ClusterEventResponse event = clusterService.getEvent(events.get(0).id());
        assertEquals("S001", event.siteCode());
        assertEquals(ClusterStatus.OPEN, event.status());
        assertEquals(BASE_TIME, event.firstCaseAt(), "首例时刻为窗口内最早报告时刻");
        assertEquals(BASE_TIME.plusSeconds(7200), event.lastCaseAt(), "末例时刻为窗口内最晚报告时刻");
        assertEquals(3, event.cards().size(), "事件须记录涉及卡号");
        assertTrue(event.cards().stream().anyMatch(c -> c.cardNo().equals(third.cardNo())));
        assertTrue(event.cards().stream().allMatch(c -> c.arrival().equals("NORMAL")));
    }

    @Test
    void fourthReportWithinWindowJoinsEvent_beyondWindowDoesNot() {
        long eventId = triggerEvent();

        // 窗口内第 4 例：加入事件并推进末例时刻。
        CardResponse fourth = submitAtSite("a4", BASE_TIME.plusSeconds(10_000));
        ClusterEventResponse event = clusterService.getEvent(eventId);
        assertEquals(4, event.cards().size());
        assertEquals(BASE_TIME.plusSeconds(10_000), event.lastCaseAt());
        assertTrue(event.cards().stream().anyMatch(c -> c.cardNo().equals(fourth.cardNo())));

        // 窗口外（首例+72h 之后）：不加入原事件，单例也不生成新事件。
        CardResponse late = submitAtSite("a5", BASE_TIME.plusSeconds(73 * 3600));
        event = clusterService.getEvent(eventId);
        assertEquals(4, event.cards().size(), "窗口外报告不得加入原事件");
        assertEquals(1, clusterService.listEvents().size(), "窗口外单例不得生成新事件");
        assertTrue(clusterService.getEvent(eventId).cards().stream()
                .noneMatch(c -> c.cardNo().equals(late.cardNo())));
    }

    @Test
    void investigationIdempotency_replaySameParams_conflictOnDifferentParams() {
        long eventId = triggerEvent();
        CreateInvestigationRequest request = new CreateInvestigationRequest("req-inv-1", "H002", "现场核实");

        InvestigationResponse first = clusterService.createInvestigation(eventId, request);
        assertEquals(InvestigationStatus.IN_PROGRESS, first.status());
        assertEquals(eventId, first.eventId());

        // 同键同参重放：返回首次结果，不产生新协查单。
        InvestigationResponse replay = clusterService.createInvestigation(eventId, request);
        assertEquals(first, replay, "同键同参重放必须返回首次结果");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM investigation_order", Integer.class));

        // 同键异参：409 冲突，且不产生新协查单。
        CreateInvestigationRequest altered = new CreateInvestigationRequest("req-inv-1", "H001", "现场核实");
        assertThrows(IdempotencyConflictException.class,
                () -> clusterService.createInvestigation(eventId, altered));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM investigation_order", Integer.class),
                "同键异参不得产生新协查单");
    }

    @Test
    void singleOpenInvestigationPerEvent_conclusionWritesBackInSameTransaction() {
        long eventId = triggerEvent();
        InvestigationResponse first = clusterService.createInvestigation(eventId,
                new CreateInvestigationRequest("req-inv-a", "H002", null));

        // 同一事件已存在进行中协查单：第二张被拒绝。
        assertThrows(BusinessRuleException.class, () -> clusterService.createInvestigation(eventId,
                new CreateInvestigationRequest("req-inv-b", "H001", null)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM investigation_order", Integer.class));

        // 办结：结论回写事件状态，与事件推进同事务生效。
        InvestigationResponse concluded = clusterService.concludeInvestigation(first.id(),
                new ConcludeInvestigationRequest("确认聚集性疫情，已现场处置", "CONFIRMED"));
        assertEquals(InvestigationStatus.CONCLUDED, concluded.status());
        assertEquals("确认聚集性疫情，已现场处置", concluded.conclusion());

        ClusterEventResponse event = clusterService.getEvent(eventId);
        assertEquals(ClusterStatus.CONFIRMED, event.status(), "结论须回写事件状态");
        assertEquals("确认聚集性疫情，已现场处置", event.conclusion());
        assertNull(event.openInvestigationId(), "办结后不得有进行中协查单");

        // 重复办结幂等：返回当前状态，不重复回写。
        InvestigationResponse again = clusterService.concludeInvestigation(first.id(),
                new ConcludeInvestigationRequest("重复提交", "EXCLUDED"));
        assertEquals(InvestigationStatus.CONCLUDED, again.status());
        assertEquals("确认聚集性疫情，已现场处置", again.conclusion());
        assertEquals(ClusterStatus.CONFIRMED, clusterService.getEvent(eventId).status());

        // 办结后可生成下一张协查单。
        InvestigationResponse second = clusterService.createInvestigation(eventId,
                new CreateInvestigationRequest("req-inv-b", "H001", "复核"));
        assertEquals(InvestigationStatus.IN_PROGRESS, second.status());
        assertEquals(2, clusterService.getInvestigations(eventId).size(), "协查轨迹须完整保留");
    }

    @Test
    void closeIsTerminal_repeatCloseIdempotent() {
        long eventId = triggerEvent();
        ClusterCloseResponse closed = clusterService.closeEvent(eventId);
        assertEquals(ClusterStatus.CLOSED, closed.status());
        Instant closedAt = clusterService.getEvent(eventId).closedAt();

        // 重复关闭：幂等成功，关闭时刻不变。
        ClusterCloseResponse again = clusterService.closeEvent(eventId);
        assertEquals(ClusterStatus.CLOSED, again.status());
        assertEquals(closedAt, clusterService.getEvent(eventId).closedAt(), "重复关闭不得改变关闭时刻");

        // 终态事件不可再生成协查单。
        assertThrows(BusinessRuleException.class, () -> clusterService.createInvestigation(eventId,
                new CreateInvestigationRequest("req-inv-closed", "H002", null)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM investigation_order", Integer.class));
    }

    @Test
    void lateCardAfterClose_recordedAsLate_andRegroupedIntoNewEvent() {
        long eventId = triggerEvent();
        clusterService.closeEvent(eventId);

        // 关闭后窗口内迟到的报告卡：只记迟到事件，事件保持关闭终态。
        CardResponse late1 = submitAtSite("l1", BASE_TIME.plusSeconds(10_000));
        ClusterEventResponse closedEvent = clusterService.getEvent(eventId);
        assertEquals(ClusterStatus.CLOSED, closedEvent.status());
        assertEquals(4, closedEvent.cards().size());
        assertEquals("LATE", closedEvent.cards().stream()
                .filter(c -> c.cardNo().equals(late1.cardNo())).findFirst().orElseThrow().arrival());
        assertEquals(1, clusterService.listEvents().size(), "单张迟到卡不得生成新事件");

        // 迟到卡可另行归并：凑满 3 例即生成新事件。
        submitAtSite("l2", BASE_TIME.plusSeconds(11_000));
        CardResponse late3 = submitAtSite("l3", BASE_TIME.plusSeconds(12_000));
        List<ClusterEventSummary> events = clusterService.listEvents();
        assertEquals(2, events.size(), "3 张迟到卡须另行归并成新事件");

        ClusterEventResponse newEvent = clusterService.getEvent(events.get(1).id());
        assertEquals(ClusterStatus.OPEN, newEvent.status());
        assertEquals(3, newEvent.cards().size());
        assertTrue(newEvent.cards().stream().allMatch(c -> c.arrival().equals("NORMAL")));
        assertEquals(BASE_TIME.plusSeconds(10_000), newEvent.firstCaseAt());
        assertEquals(BASE_TIME.plusSeconds(12_000), newEvent.lastCaseAt());
        assertTrue(newEvent.cards().stream().anyMatch(c -> c.cardNo().equals(late3.cardNo())));

        // 原事件时间线仍保留迟到记录（可复算）。
        assertEquals(6, clusterService.getEvent(eventId).cards().size());
    }

    @Test
    void timelineAndInvestigationTrail_queriesAreStable() {
        long eventId = triggerEvent();
        clusterService.createInvestigation(eventId,
                new CreateInvestigationRequest("req-inv-s", "H002", "核实"));

        ClusterEventResponse first = clusterService.getEvent(eventId);
        ClusterEventResponse second = clusterService.getEvent(eventId);
        assertEquals(first, second, "事件时间线重复查询必须稳定一致");
        assertEquals(clusterService.getInvestigations(eventId), clusterService.getInvestigations(eventId),
                "协查轨迹重复查询必须稳定一致");
        assertEquals(first.cards().stream().map(c -> c.cardNo()).toList(),
                second.cards().stream().map(c -> c.cardNo()).toList(), "卡号顺序必须稳定");
    }
}
