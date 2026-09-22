package com.example.starter.match;

import com.example.starter.error.ApiException;
import com.example.starter.error.ErrorCode;
import com.example.starter.map.MapSegment;
import com.example.starter.route.MatchPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匹配规则的纯单元测试：多候选评分构成、确定性排序与选择、失败分支。
 */
class MatchingServiceTest {

    private final MatchingService service = new MatchingService();

    /** 两条平行起点路段 + 两条平行终点路段，拓扑 n1->n2->n3。 */
    private static List<MapSegment> twoLaneMap() {
        return List.of(
                new MapSegment("A1", "n1", "n2", 0, 0, 100, 0, true),
                new MapSegment("A2", "n1", "n2", 0, 5, 100, 5, true),
                new MapSegment("B1", "n2", "n3", 100, 0, 200, 0, true),
                new MapSegment("B2", "n2", "n3", 100, 6, 200, 6, true));
    }

    @Test
    void 多候选输出评分构成排序与拒绝原因() {
        MatchResult result = service.match(twoLaneMap(),
                new MatchPoint(50, 2, 0), new MatchPoint(150, 3, 0));

        // 起点侧：A1 距离更近应排第一并被选中，A2 被拒绝且给出原因
        List<CandidateEvaluation> originCandidates = result.origin().candidates();
        assertEquals(2, originCandidates.size());
        CandidateEvaluation first = originCandidates.get(0);
        assertEquals("A1", first.segmentId());
        assertEquals(1, first.rank());
        assertTrue(first.selected());
        assertNull(first.rejectionReason());
        assertEquals(2.0, first.distanceMeters(), 1e-6);
        assertEquals(1.0, first.headingScore(), 1e-6);
        assertEquals(1.0, first.connectivityScore(), 1e-6);
        assertEquals(0.5 * (1 - 2.0 / 15.0) + 0.3 + 0.2, first.totalScore(), 1e-6);

        CandidateEvaluation second = originCandidates.get(1);
        assertEquals("A2", second.segmentId());
        assertEquals(2, second.rank());
        assertFalse(second.selected());
        assertEquals("SCORE_LOWER_THAN_SELECTED", second.rejectionReason());
        assertTrue(second.totalScore() < first.totalScore());
        assertEquals("A1", result.origin().selectedSegmentId());

        // 终点侧：B1/B2 总分相同，按 segmentId 字典序确定性选择 B1
        assertEquals("B1", result.destination().selectedSegmentId());
        assertEquals(List.of("A1", "B1"), result.pathSegmentIds());
    }

    @Test
    void 相同输入产生完全相同结果() {
        MatchResult r1 = service.match(twoLaneMap(),
                new MatchPoint(50, 2, 0), new MatchPoint(150, 3, 0));
        MatchResult r2 = service.match(twoLaneMap(),
                new MatchPoint(50, 2, 0), new MatchPoint(150, 3, 0));
        assertEquals(r1, r2);
    }

    @Test
    void 航向偏离降低航向得分() {
        MatchResult aligned = service.match(twoLaneMap(),
                new MatchPoint(50, 2, 0), new MatchPoint(150, 3, 0));
        MatchResult opposed = service.match(twoLaneMap(),
                new MatchPoint(50, 2, 180), new MatchPoint(150, 3, 0));
        assertTrue(opposed.origin().candidates().get(0).headingScore()
                < aligned.origin().candidates().get(0).headingScore());
    }

    @Test
    void 起点附近无任何路段报无起点候选() {
        ApiException ex = assertThrows(ApiException.class, () -> service.match(twoLaneMap(),
                new MatchPoint(50, 500, 0), new MatchPoint(150, 3, 0)));
        assertEquals(ErrorCode.NO_ORIGIN_CANDIDATE, ex.errorCode());
    }

    @Test
    void 终点附近无任何路段报无终点候选() {
        ApiException ex = assertThrows(ApiException.class, () -> service.match(twoLaneMap(),
                new MatchPoint(50, 2, 0), new MatchPoint(150, 500, 0)));
        assertEquals(ErrorCode.NO_DESTINATION_CANDIDATE, ex.errorCode());
    }

    @Test
    void 附近路段全部失效报路段失效() {
        List<MapSegment> segments = List.of(
                new MapSegment("A1", "n1", "n2", 0, 0, 100, 0, false),
                new MapSegment("B1", "n2", "n3", 100, 0, 200, 0, true));
        ApiException ex = assertThrows(ApiException.class, () -> service.match(segments,
                new MatchPoint(50, 2, 0), new MatchPoint(150, 3, 0)));
        assertEquals(ErrorCode.SEGMENT_INACTIVE, ex.errorCode());
    }

    @Test
    void 起终点拓扑断裂报路径不连通() {
        List<MapSegment> segments = List.of(
                new MapSegment("A1", "n1", "n2", 0, 0, 100, 0, true),
                new MapSegment("D1", "n5", "n6", 0, 100, 100, 100, true));
        ApiException ex = assertThrows(ApiException.class, () -> service.match(segments,
                new MatchPoint(50, 2, 0), new MatchPoint(50, 102, 0)));
        assertEquals(ErrorCode.PATH_DISCONNECTED, ex.errorCode());
    }

    @Test
    void 失效路段不参与路径连通() {
        // A1 -> X(失效) -> B1 唯一链路被失效路段切断
        List<MapSegment> segments = List.of(
                new MapSegment("A1", "n1", "n2", 0, 0, 100, 0, true),
                new MapSegment("X1", "n2", "n8", 100, 0, 100, 50, false),
                new MapSegment("B1", "n8", "n3", 100, 50, 200, 50, true));
        // 终点靠近 B1 中段，起点靠近 A1；A1 与 B1 之间唯一通路经过失效的 X1
        ApiException ex = assertThrows(ApiException.class, () -> service.match(segments,
                new MatchPoint(50, 2, 0), new MatchPoint(150, 52, 0)));
        assertEquals(ErrorCode.PATH_DISCONNECTED, ex.errorCode());
    }

    @Test
    void 起终点同一路段时路径为单段() {
        List<MapSegment> segments = List.of(
                new MapSegment("A1", "n1", "n2", 0, 0, 100, 0, true));
        MatchResult result = service.match(segments,
                new MatchPoint(20, 1, 0), new MatchPoint(80, 1, 0));
        assertEquals(List.of("A1"), result.pathSegmentIds());
        assertNotNull(result.origin().selectedSegmentId());
    }
}
