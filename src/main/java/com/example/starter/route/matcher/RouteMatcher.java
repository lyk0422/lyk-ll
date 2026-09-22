package com.example.starter.route.matcher;

import com.example.starter.route.api.RouteSubmitRequest;
import com.example.starter.route.domain.MapSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性地图匹配器：输入版本快照与起终点，输出多候选评分、排序、拒绝原因与确定性选择。
 * 纯计算组件，不访问数据库，保证同版本同输入结果恒定。
 *
 * <p>规则：</p>
 * <ol>
 *   <li>端点搜索半径 {@value #SEARCH_RADIUS_M} 米内的路段均作为候选，点到路段取线段几何最短距离；</li>
 *   <li>距离评分 100*(1-d/R)，航向评分 100*(1-Δ/180)，连通性评分 100/0；</li>
 *   <li>仅有效路段参与有向连通；按「起点路段.to 可达终点路段.from」判定候选对连通；</li>
 *   <li>在连通候选对中按两端评分总和最大确定性选择，平分按路段 ID 字典序；</li>
 *   <li>候选按总分降序、路段 ID 升序排名。</li>
 * </ol>
 */
@Component
public class RouteMatcher {

    /** 端点候选搜索半径，单位米 */
    public static final double SEARCH_RADIUS_M = 30.0;

    private static final String REASON_SEGMENT_INVALID = "SEGMENT_INVALID";
    private static final String REASON_NOT_CONNECTED = "NOT_CONNECTED";

    /**
     * 执行匹配；失败时返回携带稳定错误码与已生成候选解释的结果。
     */
    public MatchOutcome match(MapSnapshot snapshot, RouteSubmitRequest.PointInput start,
                              RouteSubmitRequest.PointInput end) {
        List<RawCandidate> startRaws = buildCandidates(snapshot, start);
        List<RawCandidate> endRaws = buildCandidates(snapshot, end);

        if (startRaws.isEmpty()) {
            return failed("START_NO_CANDIDATE", startRaws, endRaws);
        }
        if (endRaws.isEmpty()) {
            return failed("END_NO_CANDIDATE", startRaws, endRaws);
        }

        List<RawCandidate> startValid = startRaws.stream().filter(r -> r.segment.valid()).toList();
        List<RawCandidate> endValid = endRaws.stream().filter(r -> r.segment.valid()).toList();
        if (startValid.isEmpty() || endValid.isEmpty()) {
            return failed("SEGMENT_INVALID", startRaws, endRaws);
        }

        // 仅有效路段构成有向图：node -> 出边（按路段 ID 排序保证遍历确定性）
        Map<String, List<Edge>> adjacency = buildAdjacency(snapshot);

        // 为每个有效起点候选计算可达节点集合，得到全部连通候选对
        Map<String, Set<String>> reachableFromStart = new HashMap<>();
        for (RawCandidate s : startValid) {
            reachableFromStart.put(s.segment.segmentId(),
                    reachableNodes(s.segment.toNodeId(), adjacency));
        }
        List<CandidatePair> connectedPairs = new ArrayList<>();
        for (RawCandidate s : startValid) {
            Set<String> reachable = reachableFromStart.get(s.segment.segmentId());
            for (RawCandidate e : endValid) {
                // 起终点落在同一有效路段，或起点路段终点可达终点路段起点，均视为连通
                if (s.segment.segmentId().equals(e.segment.segmentId())
                        || reachable.contains(e.segment.fromNodeId())) {
                    connectedPairs.add(new CandidatePair(s, e, s.baseScore() + e.baseScore()));
                }
            }
        }
        if (connectedPairs.isEmpty()) {
            return failed("PATH_DISCONNECTED", startRaws, endRaws);
        }

        CandidatePair selected = connectedPairs.stream()
                .max(Comparator.comparingDouble(CandidatePair::pairScore)
                        .thenComparing(p -> p.start().segment.segmentId())
                        .thenComparing(p -> p.end().segment.segmentId()))
                .orElseThrow();

        Path path = extractPath(adjacency, selected.start().segment, selected.end().segment);

        Set<String> connectedStartIds = new HashSet<>();
        Set<String> connectedEndIds = new HashSet<>();
        for (CandidatePair pair : connectedPairs) {
            connectedStartIds.add(pair.start().segment.segmentId());
            connectedEndIds.add(pair.end().segment.segmentId());
        }

        List<ScoredCandidate> startViews = toViews("START", startRaws, connectedStartIds,
                selected.start().segment.segmentId());
        List<ScoredCandidate> endViews = toViews("END", endRaws, connectedEndIds,
                selected.end().segment.segmentId());

        double totalScore = round3(startViews.stream().filter(ScoredCandidate::selected)
                .mapToDouble(ScoredCandidate::totalScore).sum()
                + endViews.stream().filter(ScoredCandidate::selected)
                .mapToDouble(ScoredCandidate::totalScore).sum());

        return new MatchOutcome(true, null, startViews, endViews,
                selected.start().segment.segmentId(), selected.end().segment.segmentId(),
                path.nodes(), path.segments(), totalScore);
    }

    /**
     * 失败结果构造：候选仍按统一规则排名输出，用于多候选解释；无选中、无路径。
     */
    private MatchOutcome failed(String errorCode, List<RawCandidate> startRaws,
                                List<RawCandidate> endRaws) {
        return new MatchOutcome(false, errorCode,
                toViews("START", startRaws, Set.of(), null),
                toViews("END", endRaws, Set.of(), null),
                null, null, List.of(), List.of(), 0.0);
    }

    /**
     * 生成单个端点的全部几何候选并评分。
     */
    private List<RawCandidate> buildCandidates(MapSnapshot snapshot, RouteSubmitRequest.PointInput pose) {
        List<RawCandidate> raws = new ArrayList<>();
        for (MapSnapshot.Segment segment : snapshot.segments()) {
            MapSnapshot.Node from = snapshot.nodes().get(segment.fromNodeId());
            MapSnapshot.Node to = snapshot.nodes().get(segment.toNodeId());
            if (from == null || to == null) {
                continue;
            }
            double distance = pointToSegmentDistance(pose.x(), pose.y(), from.x(), from.y(), to.x(), to.y());
            if (distance > SEARCH_RADIUS_M) {
                continue;
            }
            // 航向约定：正北顺时针（东=90、南=180、西=270），故使用 atan2(东向增量, 北向增量)
            double segmentHeading = normalizeHeading(Math.toDegrees(
                    Math.atan2(to.x() - from.x(), to.y() - from.y())));
            double headingDelta = headingDelta(normalizeHeading(pose.heading()), segmentHeading);
            double distanceScore = 100.0 * (1.0 - distance / SEARCH_RADIUS_M);
            double headingScore = 100.0 * (1.0 - headingDelta / 180.0);
            raws.add(new RawCandidate(segment, round3(distance), round3(headingDelta),
                    round3(distanceScore), round3(headingScore),
                    round3(distanceScore + headingScore)));
        }
        return raws;
    }

    /**
     * 候选转视图：先标注连通性并得到含连通性的总分，再按总分降序、路段 ID 升序排名。
     */
    private List<ScoredCandidate> toViews(String endpoint, List<RawCandidate> raws,
                                          Set<String> connectedIds, String selectedId) {
        List<ScoredCandidate> views = new ArrayList<>();
        for (RawCandidate raw : raws) {
            String segmentId = raw.segment.segmentId();
            List<String> reasons = new ArrayList<>();
            double connectivityScore = 0.0;
            if (!raw.segment.valid()) {
                reasons.add(REASON_SEGMENT_INVALID);
            } else if (connectedIds.contains(segmentId)) {
                connectivityScore = 100.0;
            } else {
                reasons.add(REASON_NOT_CONNECTED);
            }
            double totalScore = round3(raw.baseScore() + connectivityScore);
            views.add(new ScoredCandidate(
                    segmentId, endpoint, raw.distance(), raw.headingDelta(),
                    raw.distanceScore(), raw.headingScore(), connectivityScore,
                    totalScore, segmentId.equals(selectedId), List.copyOf(reasons)));
        }
        views.sort(Comparator.comparingDouble(ScoredCandidate::totalScore).reversed()
                .thenComparing(ScoredCandidate::segmentId));
        return views;
    }

    /**
     * 构造版本内全部有效路段的有向邻接表，同节点出边按路段 ID 升序。
     */
    private Map<String, List<Edge>> buildAdjacency(MapSnapshot snapshot) {
        Map<String, List<Edge>> adjacency = new LinkedHashMap<>();
        snapshot.segments().stream()
                .filter(MapSnapshot.Segment::valid)
                .sorted(Comparator.comparing(MapSnapshot.Segment::segmentId))
                .forEach(segment -> adjacency
                        .computeIfAbsent(segment.fromNodeId(), k -> new ArrayList<>())
                        .add(new Edge(segment.segmentId(), segment.fromNodeId(), segment.toNodeId())));
        return adjacency;
    }

    /**
     * 从某节点出发经有向有效边可达的全部节点集合（含起点）。
     */
    private Set<String> reachableNodes(String source, Map<String, List<Edge>> adjacency) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(source);
        visited.add(source);
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            for (Edge edge : adjacency.getOrDefault(node, List.of())) {
                if (visited.add(edge.toNodeId())) {
                    queue.add(edge.toNodeId());
                }
            }
        }
        return visited;
    }

    /**
     * 提取选中候选对间的确定性连通路径：BFS 最少路段，邻接表已按路段 ID 排序保证遍历稳定。
     * 返回节点序列（含起终路段两端点）与路段序列。
     */
    private Path extractPath(Map<String, List<Edge>> adjacency,
                             MapSnapshot.Segment startSegment, MapSnapshot.Segment endSegment) {
        List<String> nodes = new ArrayList<>();
        List<String> segments = new ArrayList<>();
        nodes.add(startSegment.fromNodeId());

        if (startSegment.segmentId().equals(endSegment.segmentId())) {
            nodes.add(startSegment.toNodeId());
            segments.add(startSegment.segmentId());
            return new Path(nodes, segments);
        }

        Map<String, Edge> parent = new HashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(startSegment.toNodeId());
        visited.add(startSegment.toNodeId());
        boolean found = false;
        while (!queue.isEmpty() && !found) {
            String node = queue.removeFirst();
            if (node.equals(endSegment.fromNodeId())) {
                found = true;
                break;
            }
            for (Edge edge : adjacency.getOrDefault(node, List.of())) {
                if (visited.add(edge.toNodeId())) {
                    parent.put(edge.toNodeId(), edge);
                    queue.add(edge.toNodeId());
                    if (edge.toNodeId().equals(endSegment.fromNodeId())) {
                        found = true;
                        break;
                    }
                }
            }
        }

        // 从终点衔接节点回溯到 startSegment.to，再反转为正向
        List<Edge> middle = new ArrayList<>();
        String cursor = endSegment.fromNodeId();
        while (!cursor.equals(startSegment.toNodeId())) {
            Edge edge = parent.get(cursor);
            if (edge == null) {
                break;
            }
            middle.add(edge);
            cursor = edge.fromNodeId();
        }
        middle = middle.reversed();

        nodes.add(startSegment.toNodeId());
        segments.add(startSegment.segmentId());
        for (Edge edge : middle) {
            nodes.add(edge.toNodeId());
            segments.add(edge.segmentId());
        }
        nodes.add(endSegment.toNodeId());
        segments.add(endSegment.segmentId());
        return new Path(nodes, segments);
    }

    /**
     * 点 P 到线段 AB 的最短距离：投影落在线段内取垂直距离，否则取较近端点距离。
     */
    private double pointToSegmentDistance(double px, double py,
                                          double ax, double ay, double bx, double by) {
        double abx = bx - ax;
        double aby = by - ay;
        double apx = px - ax;
        double apy = py - ay;
        double lengthSquared = abx * abx + aby * aby;
        double t = lengthSquared == 0.0 ? 0.0 : (apx * abx + apy * aby) / lengthSquared;
        t = Math.clamp(t, 0.0, 1.0);
        double closestX = ax + t * abx;
        double closestY = ay + t * aby;
        return Math.hypot(px - closestX, py - closestY);
    }

    private static double normalizeHeading(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    private static double headingDelta(double a, double b) {
        double diff = Math.abs(a - b) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /**
     * 内部候选：路段引用与几何/航向基础评分（连通性评分在成图后补入）。
     */
    private record RawCandidate(
            MapSnapshot.Segment segment,
            double distance,
            double headingDelta,
            double distanceScore,
            double headingScore,
            double baseScore) {
    }

    /**
     * 有向图出边。
     */
    private record Edge(String segmentId, String fromNodeId, String toNodeId) {
    }

    /**
     * 连通候选对及其选择评分。
     */
    private record CandidatePair(RawCandidate start, RawCandidate end, double pairScore) {
    }

    /**
     * 路径提取结果。
     */
    private record Path(List<String> nodes, List<String> segments) {
    }
}
