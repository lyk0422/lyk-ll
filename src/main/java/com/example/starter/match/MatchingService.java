package com.example.starter.match;

import com.example.starter.error.ApiException;
import com.example.starter.error.ErrorCode;
import com.example.starter.map.MapSegment;
import com.example.starter.route.MatchPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性地图匹配：候选生成、评分构成、排序、拒绝原因与连通路径选择。
 * 同一（版本， 输入）必产生同一结果；不依赖时间、随机数或迭代顺序。
 */
@Service
public class MatchingService {

    /** 候选距离阈值，单位米。 */
    static final double CANDIDATE_THRESHOLD_METERS = 15.0;

    private static final double W_DISTANCE = 0.5;
    private static final double W_HEADING = 0.3;
    private static final double W_CONNECTIVITY = 0.2;

    /**
     * 对起终点输入执行匹配；失败时抛出携带稳定错误码的 {@link ApiException}。
     *
     * @param segments    绑定版本的全量路段（含失效路段）
     * @param origin      起点匹配输入
     * @param destination 终点匹配输入
     * @return 匹配结果快照
     */
    public MatchResult match(List<MapSegment> segments, MatchPoint origin, MatchPoint destination) {
        List<MapSegment> active = segments.stream().filter(MapSegment::active).toList();

        List<MapSegment> originCandidates = selectCandidates(segments, active, origin, true);
        List<MapSegment> destCandidates = selectCandidates(segments, active, destination, false);

        Map<String, List<MapSegment>> adjacency = buildAdjacency(active);
        Set<String> destCandidateIds = segmentIds(destCandidates);
        Set<String> reachableFromOrigin = reachableFrom(originCandidates, adjacency);

        MatchSide originSide = scoreSide(originCandidates, origin, adjacency, destCandidateIds, true);
        MatchSide destSide = scoreSide(destCandidates, destination, adjacency, reachableFromOrigin, false);

        List<String> path = findPath(originSide.selectedSegmentId(), destSide.selectedSegmentId(), adjacency);
        if (path == null) {
            throw new ApiException(ErrorCode.PATH_DISCONNECTED,
                    "起点路段 " + originSide.selectedSegmentId() + " 与终点路段 "
                            + destSide.selectedSegmentId() + " 之间拓扑不连通");
        }
        return new MatchResult(originSide, destSide, path);
    }

    /**
     * 生成一侧候选：阈值内无任何路段报无候选；阈值内仅存在失效路段报路段失效。
     */
    private List<MapSegment> selectCandidates(List<MapSegment> all, List<MapSegment> active,
                                              MatchPoint point, boolean originSide) {
        boolean anyNear = all.stream().anyMatch(s -> distanceMeters(s, point) <= CANDIDATE_THRESHOLD_METERS);
        List<MapSegment> candidates = active.stream()
                .filter(s -> distanceMeters(s, point) <= CANDIDATE_THRESHOLD_METERS)
                .toList();
        if (candidates.isEmpty()) {
            if (anyNear) {
                throw new ApiException(ErrorCode.SEGMENT_INACTIVE,
                        (originSide ? "起点" : "终点") + "附近路段均已失效");
            }
            throw new ApiException(
                    originSide ? ErrorCode.NO_ORIGIN_CANDIDATE : ErrorCode.NO_DESTINATION_CANDIDATE,
                    (originSide ? "起点" : "终点") + "附近 " + (int) CANDIDATE_THRESHOLD_METERS + " 米内无候选路段");
        }
        return candidates;
    }

    /**
     * 计算一侧全部候选的评分构成、总分、排序与拒绝原因，并确定性选择第一名。
     *
     * @param connectedToOtherSide 与对端候选集合连通的候选 segmentId 集合（对 origin 侧传入对端候选 id 集合，
     *                             对 destination 侧传入从起点候选可达的 id 集合）
     */
    private MatchSide scoreSide(List<MapSegment> candidates, MatchPoint point,
                                Map<String, List<MapSegment>> adjacency,
                                Set<String> connectedToOtherSide, boolean originSide) {
        record Scored(MapSegment segment, double distance, double distanceScore,
                      double headingScore, double connectivityScore, double total) {
        }

        List<Scored> scored = new ArrayList<>();
        for (MapSegment s : candidates) {
            double distance = distanceMeters(s, point);
            double distanceScore = 1.0 - distance / CANDIDATE_THRESHOLD_METERS;
            double headingScore = 1.0 - headingDiffDegrees(point.heading(), s.headingDegrees()) / 180.0;
            double connectivityScore;
            if (originSide) {
                connectivityScore = reachesAny(s.segmentId(), adjacency, connectedToOtherSide) ? 1.0 : 0.0;
            } else {
                connectivityScore = connectedToOtherSide.contains(s.segmentId()) ? 1.0 : 0.0;
            }
            double total = round6(W_DISTANCE * distanceScore + W_HEADING * headingScore
                    + W_CONNECTIVITY * connectivityScore);
            scored.add(new Scored(s, round6(distance), round6(distanceScore),
                    round6(headingScore), connectivityScore, total));
        }

        scored.sort(Comparator.comparingDouble(Scored::total).reversed()
                .thenComparing(sc -> sc.segment().segmentId()));

        List<CandidateEvaluation> evaluations = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Scored sc = scored.get(i);
            boolean selected = i == 0;
            evaluations.add(new CandidateEvaluation(
                    sc.segment().segmentId(),
                    sc.distance(),
                    sc.distanceScore(),
                    sc.headingScore(),
                    sc.connectivityScore(),
                    sc.total(),
                    i + 1,
                    selected,
                    selected ? null : "SCORE_LOWER_THAN_SELECTED"));
        }
        return new MatchSide(evaluations, scored.get(0).segment().segmentId());
    }

    /**
     * 有向邻接：A.endNode == B.startNode 时 A -> B（仅有效路段参与）。
     */
    private Map<String, List<MapSegment>> buildAdjacency(List<MapSegment> active) {
        Map<String, List<MapSegment>> byStartNode = new LinkedHashMap<>();
        for (MapSegment s : active) {
            byStartNode.computeIfAbsent(s.startNode(), k -> new ArrayList<>()).add(s);
        }
        Map<String, List<MapSegment>> adjacency = new HashMap<>();
        for (MapSegment s : active) {
            adjacency.put(s.segmentId(),
                    List.copyOf(byStartNode.getOrDefault(s.endNode(), List.of())));
        }
        return adjacency;
    }

    private Set<String> segmentIds(List<MapSegment> segments) {
        Set<String> ids = new HashSet<>();
        for (MapSegment s : segments) {
            ids.add(s.segmentId());
        }
        return ids;
    }

    /**
     * 从候选集合出发沿有向拓扑可达的全部 segmentId（含起点候选自身）。
     */
    private Set<String> reachableFrom(List<MapSegment> starts, Map<String, List<MapSegment>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (MapSegment s : starts) {
            if (visited.add(s.segmentId())) {
                queue.add(s.segmentId());
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (MapSegment next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next.segmentId())) {
                    queue.add(next.segmentId());
                }
            }
        }
        return visited;
    }

    private boolean reachesAny(String segmentId, Map<String, List<MapSegment>> adjacency, Set<String> targets) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(segmentId);
        queue.add(segmentId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (targets.contains(current)) {
                return true;
            }
            for (MapSegment next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next.segmentId())) {
                    queue.add(next.segmentId());
                }
            }
        }
        return false;
    }

    /**
     * BFS 最短路径（按 segmentId 字典序展开保证确定性）；不连通返回 null。
     */
    private List<String> findPath(String fromSegmentId, String toSegmentId,
                                  Map<String, List<MapSegment>> adjacency) {
        if (fromSegmentId.equals(toSegmentId)) {
            return List.of(fromSegmentId);
        }
        Map<String, String> predecessor = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(fromSegmentId);
        queue.add(fromSegmentId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<MapSegment> nexts = new ArrayList<>(adjacency.getOrDefault(current, List.of()));
            nexts.sort(Comparator.comparing(MapSegment::segmentId));
            for (MapSegment next : nexts) {
                if (visited.add(next.segmentId())) {
                    predecessor.put(next.segmentId(), current);
                    if (next.segmentId().equals(toSegmentId)) {
                        List<String> path = new ArrayList<>();
                        for (String node = toSegmentId; node != null; node = predecessor.get(node)) {
                            path.add(0, node);
                        }
                        return path;
                    }
                    queue.add(next.segmentId());
                }
            }
        }
        return null;
    }

    /**
     * 点到直线段的垂直距离，单位米。
     */
    static double distanceMeters(MapSegment s, MatchPoint p) {
        double dx = s.endX() - s.startX();
        double dy = s.endY() - s.startY();
        double lengthSq = dx * dx + dy * dy;
        if (lengthSq == 0.0) {
            return Math.hypot(p.x() - s.startX(), p.y() - s.startY());
        }
        double t = ((p.x() - s.startX()) * dx + (p.y() - s.startY()) * dy) / lengthSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(p.x() - (s.startX() + t * dx), p.y() - (s.startY() + t * dy));
    }

    /**
     * 两个航向角的最小夹角，单位度，范围 [0,180]。
     */
    static double headingDiffDegrees(double a, double b) {
        double diff = Math.abs(a - b) % 360.0;
        return Math.min(diff, 360.0 - diff);
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
