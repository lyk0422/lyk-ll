package com.example.starter.route.matcher;

import com.example.starter.route.api.RouteSubmitRequest;
import com.example.starter.route.domain.MapSnapshot;
import com.example.starter.route.domain.MapVersionStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RouteMatcher} 纯计算单元测试：候选生成、评分构成、排序、拒绝原因与确定性选择。
 */
class RouteMatcherTest {

    private final RouteMatcher matcher = new RouteMatcher();

    private MapSnapshot snapshot(List<MapSnapshot.Node> nodes, List<MapSnapshot.Segment> segments) {
        Map<String, MapSnapshot.Node> nodeMap = new LinkedHashMap<>();
        nodes.forEach(n -> nodeMap.put(n.nodeId(), n));
        return new MapSnapshot("v-test", MapVersionStatus.ACTIVE, nodeMap, segments);
    }

    private MapSnapshot.Node node(String id, double x, double y) {
        return new MapSnapshot.Node(id, x, y);
    }

    private MapSnapshot.Segment segment(String id, String from, String to, boolean valid) {
        return new MapSnapshot.Segment(id, from, to, 0.0, valid);
    }

    private RouteSubmitRequest.PointInput pose(double x, double y, double heading) {
        return new RouteSubmitRequest.PointInput(x, y, heading);
    }

    @Test
    void matched_path_exposes_multi_candidate_scores_and_deterministic_choice() {
        // 链状有向图：N1 -> s1 -> N2 -> s2 -> N3 -> s3 -> N4
        MapSnapshot map = snapshot(
                List.of(node("N1", 0, 0), node("N2", 100, 0),
                        node("N3", 200, 0), node("N4", 300, 0)),
                List.of(segment("s1", "N1", "N2", true),
                        segment("s2", "N2", "N3", true),
                        segment("s3", "N3", "N4", true)));

        // 起点靠近 s1 且航向朝东；终点靠近 s3 且航向朝东
        MatchOutcome outcome = matcher.match(map, pose(5, 2, 90), pose(295, 2, 90));

        assertTrue(outcome.matched());
        assertNull(outcome.errorCode());
        assertEquals("s1", outcome.selectedStartSegmentId());
        assertEquals("s3", outcome.selectedEndSegmentId());
        assertEquals(List.of("N1", "N2", "N3", "N4"), outcome.pathNodeIds());
        assertEquals(List.of("s1", "s2", "s3"), outcome.pathSegmentIds());

        ScoredCandidate chosen = outcome.startCandidates().getFirst();
        assertEquals("s1", chosen.segmentId());
        assertTrue(chosen.selected());
        assertEquals(100.0, chosen.connectivityScore(), 0.001);
        assertTrue(chosen.distanceScore() > 90.0);
        assertTrue(chosen.headingScore() > 90.0);
        assertEquals(chosen.distanceScore() + chosen.headingScore() + chosen.connectivityScore(),
                chosen.totalScore(), 0.01);
        assertTrue(chosen.rejectReasons().isEmpty());
    }

    @Test
    void connectivity_score_can_override_closer_segment_and_dead_end_is_rejected() {
        // s1：N1(0,0)->N2(100,0)，其后接 s3：N2->N3(200,0)，构成连通链
        // s2：C(0,10)->D(100,10)，平行断头路，到不了 s3
        MapSnapshot map = snapshot(
                List.of(node("A", 0, 0), node("B", 100, 0),
                        node("C", 0, 10), node("D", 100, 10),
                        node("E", 200, 0)),
                List.of(segment("s1", "A", "B", true),
                        segment("s2", "C", "D", true),
                        segment("s3", "B", "E", true)));

        // 起点 (50,8)：几何上离 s2(距离2) 比 s1(距离8) 更近；终点 (150,2) 靠近 s3
        MatchOutcome outcome = matcher.match(map, pose(50, 8, 90), pose(150, 2, 90));

        assertTrue(outcome.matched());
        // s2 虽更近却不通向终点；s1 靠连通性评分反超并被选中
        assertEquals("s1", outcome.selectedStartSegmentId());
        assertEquals("s3", outcome.selectedEndSegmentId());
        assertEquals("s1", outcome.startCandidates().getFirst().segmentId());
        assertTrue(outcome.startCandidates().getFirst().selected());
        assertEquals(100.0, outcome.startCandidates().getFirst().connectivityScore(), 0.0);

        ScoredCandidate deadEnd = outcome.startCandidates().stream()
                .filter(c -> c.segmentId().equals("s2")).findFirst().orElseThrow();
        assertTrue(deadEnd.rejectReasons().contains("NOT_CONNECTED"));
        assertEquals(0.0, deadEnd.connectivityScore(), 0.0);
        assertFalse(deadEnd.selected());
    }

    @Test
    void start_without_any_segment_returns_start_no_candidate() {
        MapSnapshot map = snapshot(
                List.of(node("A", 0, 0), node("B", 100, 0)),
                List.of(segment("s1", "A", "B", true)));

        MatchOutcome outcome = matcher.match(map, pose(1000, 1000, 0), pose(50, 0, 90));

        assertFalse(outcome.matched());
        assertEquals("START_NO_CANDIDATE", outcome.errorCode());
        assertTrue(outcome.startCandidates().isEmpty());
    }

    @Test
    void end_without_any_segment_returns_end_no_candidate() {
        MapSnapshot map = snapshot(
                List.of(node("A", 0, 0), node("B", 100, 0)),
                List.of(segment("s1", "A", "B", true)));

        MatchOutcome outcome = matcher.match(map, pose(50, 0, 90), pose(1000, 1000, 0));

        assertFalse(outcome.matched());
        assertEquals("END_NO_CANDIDATE", outcome.errorCode());
        assertTrue(outcome.endCandidates().isEmpty());
    }

    @Test
    void only_invalid_segments_near_start_returns_segment_invalid() {
        MapSnapshot map = snapshot(
                List.of(node("A", 0, 0), node("B", 100, 0)),
                List.of(segment("s1", "A", "B", false)));

        MatchOutcome outcome = matcher.match(map, pose(50, 2, 90), pose(50, 2, 90));

        assertFalse(outcome.matched());
        assertEquals("SEGMENT_INVALID", outcome.errorCode());
        // 失效候选仍输出解释，且带 SEGMENT_INVALID 拒绝原因
        assertEquals(1, outcome.startCandidates().size());
        assertTrue(outcome.startCandidates().getFirst().rejectReasons().contains("SEGMENT_INVALID"));
        assertFalse(outcome.startCandidates().getFirst().selected());
    }

    @Test
    void two_valid_components_returns_path_disconnected() {
        // 两条互不相连的有效链：N1->N2 与 N3->N4
        MapSnapshot map = snapshot(
                List.of(node("N1", 0, 0), node("N2", 100, 0),
                        node("N3", 200, 0), node("N4", 300, 0)),
                List.of(segment("s1", "N1", "N2", true),
                        segment("s2", "N3", "N4", true)));

        MatchOutcome outcome = matcher.match(map, pose(5, 2, 90), pose(295, 2, 90));

        assertFalse(outcome.matched());
        assertEquals("PATH_DISCONNECTED", outcome.errorCode());
        assertEquals("s1", outcome.startCandidates().getFirst().segmentId());
        assertTrue(outcome.startCandidates().getFirst().rejectReasons().contains("NOT_CONNECTED"));
        assertTrue(outcome.endCandidates().getFirst().rejectReasons().contains("NOT_CONNECTED"));
    }

    @Test
    void same_inputs_produce_identical_results() {
        MapSnapshot map = snapshot(
                List.of(node("N1", 0, 0), node("N2", 100, 0),
                        node("N3", 200, 0), node("N4", 300, 0)),
                List.of(segment("s1", "N1", "N2", true),
                        segment("s2", "N2", "N3", true),
                        segment("s3", "N3", "N4", true)));

        MatchOutcome first = matcher.match(map, pose(5, 2, 90), pose(295, 2, 90));
        MatchOutcome second = matcher.match(map, pose(5, 2, 90), pose(295, 2, 90));

        assertEquals(first.selectedStartSegmentId(), second.selectedStartSegmentId());
        assertEquals(first.selectedEndSegmentId(), second.selectedEndSegmentId());
        assertEquals(first.pathNodeIds(), second.pathNodeIds());
        assertEquals(first.totalScore(), second.totalScore(), 0.0);
    }

    @Test
    void direction_matters_reverse_direction_is_disconnected() {
        // 有向边方向为 N2 -> N1，起点在该路段、终点在其前方 N3->N4，方向相反不可达
        MapSnapshot map = snapshot(
                List.of(node("N1", 0, 0), node("N2", 100, 0),
                        node("N3", 200, 0), node("N4", 300, 0)),
                List.of(segment("s1", "N2", "N1", true),
                        segment("s2", "N3", "N4", true)));

        MatchOutcome outcome = matcher.match(map, pose(5, 2, 90), pose(295, 2, 90));

        assertFalse(outcome.matched());
        assertEquals("PATH_DISCONNECTED", outcome.errorCode());
    }
}
