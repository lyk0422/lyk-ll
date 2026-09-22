package com.example.starter.route.service;

import com.example.starter.route.api.CandidateView;
import com.example.starter.route.api.MapVersionResponse;
import com.example.starter.route.api.MatchView;
import com.example.starter.route.api.PublishMapRequest;
import com.example.starter.route.api.RouteResponse;
import com.example.starter.route.api.RouteSubmitRequest;
import com.example.starter.route.cache.RouteQueryCache;
import com.example.starter.route.domain.ApiException;
import com.example.starter.route.domain.ErrorCode;
import com.example.starter.route.domain.MapSnapshot;
import com.example.starter.route.domain.MapVersionStatus;
import com.example.starter.route.matcher.MatchOutcome;
import com.example.starter.route.matcher.RouteMatcher;
import com.example.starter.route.matcher.ScoredCandidate;
import com.example.starter.route.repo.MapNodeRow;
import com.example.starter.route.repo.MapSegmentRow;
import com.example.starter.route.repo.MapVersionRepository;
import com.example.starter.route.repo.MapVersionRow;
import com.example.starter.route.repo.RouteCandidateRow;
import com.example.starter.route.repo.RouteMatchRow;
import com.example.starter.route.repo.RouteRepository;
import com.example.starter.route.repo.RouteRequestRow;
import com.example.starter.route.support.JsonLists;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路线版本与地图匹配应用服务：编排版本发布/废弃、请求幂等、匹配计算、缓存与持久化。
 */
@Service
public class RouteService {

    private final MapVersionRepository mapVersionRepository;
    private final RouteRepository routeRepository;
    private final RouteMatcher routeMatcher;
    private final RouteQueryCache queryCache;
    private final Clock clock;

    public RouteService(MapVersionRepository mapVersionRepository,
                        RouteRepository routeRepository,
                        RouteMatcher routeMatcher,
                        RouteQueryCache queryCache,
                        Clock clock) {
        this.mapVersionRepository = mapVersionRepository;
        this.routeRepository = routeRepository;
        this.routeMatcher = routeMatcher;
        this.queryCache = queryCache;
        this.clock = clock;
    }

    /**
     * 发布全新地图版本。版本号唯一、拓扑自洽；整体一个事务写入不可变快照。
     */
    @Transactional
    public MapVersionResponse publishMap(PublishMapRequest request) {
        validateTopology(request);
        List<PublishMapRequest.SegmentSpec> normalizedSegments = normalizeSegments(request);
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            mapVersionRepository.insertVersion(
                    request.version(), MapVersionStatus.ACTIVE.name(), now);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.VERSION_ALREADY_EXISTS,
                    "地图版本已存在，热更新只能发布新版本: " + request.version());
        }
        mapVersionRepository.insertNodes(request.version(), request.nodes());
        mapVersionRepository.insertSegments(request.version(), normalizedSegments);
        return new MapVersionResponse(request.version(), MapVersionStatus.ACTIVE.name(),
                now, null, request.nodes().size(), normalizedSegments.size());
    }

    public MapVersionResponse getMap(String version) {
        MapVersionRow row = requireVersion(version);
        return toVersionResponse(row);
    }

    /**
     * 废弃版本：只改版本状态并驱逐该版本缓存；不改变已绑定请求的任何历史结果。
     * 对已废弃版本重复调用是幂等的，返回当前状态。
     */
    @Transactional
    public MapVersionResponse deprecateMap(String version) {
        MapVersionRow row = requireVersion(version);
        if (row.status().equals(MapVersionStatus.ACTIVE.name())) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            mapVersionRepository.markDeprecated(version, now);
            row = new MapVersionRow(row.id(), row.version(), MapVersionStatus.DEPRECATED.name(),
                    row.publishedAt(), now);
            queryCache.invalidateVersion(version);
        }
        return toVersionResponse(row);
    }

    /**
     * 提交路线匹配。同 requestId 同参重放返回原始结果；异参重放返回 409。
     */
    @Transactional
    public RouteResponse submitRoute(RouteSubmitRequest request) {
        String inputHash = inputHash(request);

        // 既有请求优先：版本废弃/切换不影响已执行请求的同参重放与异参 409 判定
        RouteRequestRow existing = routeRepository.findRequest(request.requestId()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, request, inputHash);
        }

        MapVersionRow versionRow = requireVersion(request.mapVersion());
        if (versionRow.status().equals(MapVersionStatus.DEPRECATED.name())) {
            throw new ApiException(ErrorCode.MAP_VERSION_DEPRECATED,
                    "地图版本已废弃，不能提交新请求: " + request.mapVersion());
        }

        MapSnapshot snapshot = loadSnapshot(versionRow);
        MatchOutcome outcome = queryCache.get(request.mapVersion(), inputHash)
                .orElseGet(() -> {
                    MatchOutcome computed = routeMatcher.match(snapshot, request.start(), request.end());
                    queryCache.put(request.mapVersion(), inputHash, computed);
                    return computed;
                });

        OffsetDateTime createdAt = OffsetDateTime.now(clock);

        // 请求行是权威幂等闸门：并发提交时由唯一约束决定唯一胜者
        try {
            routeRepository.insertRequest(
                    request.requestId(), request.mapVersion(), inputHash,
                    request.start().x(), request.start().y(), request.start().heading(),
                    request.end().x(), request.end().y(), request.end().heading(),
                    outcome.matched() ? "MATCHED" : "FAILED",
                    outcome.errorCode(), createdAt);
        } catch (DuplicateKeyException ex) {
            RouteRequestRow winner = waitForCompetingRequest(request.requestId());
            return replayOrConflict(winner, request, inputHash);
        }

        persistOutcome(request.requestId(), outcome);

        return assembleResponse(
                request.requestId(), request.mapVersion(),
                outcome.matched() ? "MATCHED" : "FAILED", outcome.errorCode(),
                createdAt, outcome);
    }

    public RouteResponse getRoute(String requestId) {
        RouteRequestRow row = routeRepository.findRequest(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.REQUEST_NOT_FOUND,
                        "路线请求不存在: " + requestId));
        return loadResponse(row);
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    /**
     * 重放判定：版本与输入摘要均一致才返回原始结果，否则 409。
     */
    private RouteResponse replayOrConflict(RouteRequestRow existing,
                                           RouteSubmitRequest incoming, String incomingHash) {
        boolean sameVersion = existing.mapVersion().equals(incoming.mapVersion());
        boolean sameInput = existing.inputHash().equals(incomingHash);
        if (!sameVersion || !sameInput) {
            throw new ApiException(ErrorCode.REPLAY_PARAM_CONFLICT,
                    "requestId 已绑定不同的地图版本或起终点参数: " + incoming.requestId());
        }
        return loadResponse(existing);
    }

    /**
     * 并发唯一约束冲突后等待竞争事务可见（含提交等待），最多约 3 秒。
     */
    private RouteRequestRow waitForCompetingRequest(String requestId) {
        for (int i = 0; i < 60; i++) {
            RouteRequestRow row = routeRepository.findRequest(requestId).orElse(null);
            if (row != null) {
                return row;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "等待并发请求结果时被中断");
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "并发提交后无法读取竞争请求结果");
    }

    /**
     * 将计算结果的候选与路径落库；调用前请求行已作为幂等闸门写入成功。
     */
    private void persistOutcome(String requestId, MatchOutcome outcome) {
        List<RouteCandidateRow> rows = new ArrayList<>();
        appendCandidateRows(requestId, "START", outcome.startCandidates(), rows);
        appendCandidateRows(requestId, "END", outcome.endCandidates(), rows);
        routeRepository.insertCandidates(requestId, rows);
        if (outcome.matched()) {
            routeRepository.insertMatch(new RouteMatchRow(
                    requestId,
                    outcome.selectedStartSegmentId(),
                    outcome.selectedEndSegmentId(),
                    JsonLists.writeStringList(outcome.pathNodeIds()),
                    JsonLists.writeStringList(outcome.pathSegmentIds()),
                    outcome.totalScore()));
        }
    }

    private void appendCandidateRows(String requestId, String endpoint,
                                     List<ScoredCandidate> candidates, List<RouteCandidateRow> target) {
        int rank = 1;
        for (ScoredCandidate c : candidates) {
            target.add(new RouteCandidateRow(
                    0L, requestId, endpoint, rank++, c.segmentId(),
                    c.distanceM(), c.headingDeltaDeg(),
                    c.distanceScore(), c.headingScore(), c.connectivityScore(),
                    c.totalScore(), c.selected(), String.join(",", c.rejectReasons())));
        }
    }

    private RouteResponse loadResponse(RouteRequestRow requestRow) {
        List<RouteCandidateRow> rows = routeRepository.findCandidates(requestRow.requestId());
        MatchOutcome outcome;
        if (requestRow.state().equals("MATCHED")) {
            RouteMatchRow matchRow = routeRepository.findMatch(requestRow.requestId())
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "匹配结果缺失: " + requestRow.requestId()));
            outcome = new MatchOutcome(true, null,
                    toScored(filter(rows, "START")), toScored(filter(rows, "END")),
                    matchRow.startSegmentId(), matchRow.endSegmentId(),
                    JsonLists.readStringList(matchRow.pathNodeIds()),
                    JsonLists.readStringList(matchRow.pathSegmentIds()),
                    matchRow.totalScore());
        } else {
            outcome = new MatchOutcome(false, requestRow.errorCode(),
                    toScored(filter(rows, "START")), toScored(filter(rows, "END")),
                    null, null, List.of(), List.of(), 0.0);
        }
        return assembleResponse(requestRow.requestId(), requestRow.mapVersion(),
                requestRow.state(), requestRow.errorCode(), requestRow.createdAt(), outcome);
    }

    private RouteResponse assembleResponse(String requestId, String mapVersion, String state,
                                           String errorCode, OffsetDateTime createdAt,
                                           MatchOutcome outcome) {
        MatchView matchView = null;
        if (outcome.matched()) {
            matchView = new MatchView(
                    outcome.selectedStartSegmentId(), outcome.selectedEndSegmentId(),
                    outcome.pathNodeIds(), outcome.pathSegmentIds(), outcome.totalScore());
        }
        return new RouteResponse(requestId, mapVersion, state, errorCode, createdAt,
                toViews(outcome.startCandidates()), toViews(outcome.endCandidates()), matchView);
    }

    private static List<RouteCandidateRow> filter(List<RouteCandidateRow> rows, String endpoint) {
        return rows.stream().filter(r -> r.endpoint().equals(endpoint)).toList();
    }

    private static List<ScoredCandidate> toScored(List<RouteCandidateRow> rows) {
        List<ScoredCandidate> result = new ArrayList<>();
        for (RouteCandidateRow r : rows) {
            List<String> reasons = r.rejectReasons().isBlank()
                    ? List.of() : List.of(r.rejectReasons().split(","));
            result.add(new ScoredCandidate(
                    r.segmentId(), r.endpoint(), r.distanceM(), r.headingDeltaDeg(),
                    r.distanceScore(), r.headingScore(), r.connectivityScore(),
                    r.totalScore(), r.selected(), reasons));
        }
        return result;
    }

    private static List<CandidateView> toViews(List<ScoredCandidate> candidates) {
        List<CandidateView> views = new ArrayList<>();
        int rank = 1;
        for (ScoredCandidate c : candidates) {
            views.add(new CandidateView(
                    c.endpoint(), rank++, c.segmentId(), c.distanceM(), c.headingDeltaDeg(),
                    c.distanceScore(), c.headingScore(), c.connectivityScore(),
                    c.totalScore(), c.selected(), c.rejectReasons()));
        }
        return views;
    }

    private MapVersionRow requireVersion(String version) {
        return mapVersionRepository.findVersion(version)
                .orElseThrow(() -> new ApiException(ErrorCode.MAP_VERSION_NOT_FOUND,
                        "地图版本不存在: " + version));
    }

    private MapVersionResponse toVersionResponse(MapVersionRow row) {
        return new MapVersionResponse(row.version(), row.status(), row.publishedAt(),
                row.deprecatedAt(),
                mapVersionRepository.countNodes(row.version()),
                mapVersionRepository.countSegments(row.version()));
    }

    private MapSnapshot loadSnapshot(MapVersionRow versionRow) {
        List<MapNodeRow> nodeRows = mapVersionRepository.findNodes(versionRow.version());
        List<MapSegmentRow> segmentRows = mapVersionRepository.findSegments(versionRow.version());

        Map<String, MapSnapshot.Node> nodes = new LinkedHashMap<>();
        for (MapNodeRow n : nodeRows) {
            nodes.put(n.nodeId(), new MapSnapshot.Node(n.nodeId(), n.x(), n.y()));
        }
        List<MapSnapshot.Segment> segments = new ArrayList<>();
        for (MapSegmentRow s : segmentRows) {
            double length = s.lengthM();
            if (length == 0.0) {
                MapSnapshot.Node from = nodes.get(s.fromNodeId());
                MapSnapshot.Node to = nodes.get(s.toNodeId());
                if (from != null && to != null) {
                    length = Math.hypot(to.x() - from.x(), to.y() - from.y());
                }
            }
            segments.add(new MapSnapshot.Segment(
                    s.segmentId(), s.fromNodeId(), s.toNodeId(), length, s.valid()));
        }
        MapVersionStatus status = MapVersionStatus.valueOf(versionRow.status());
        return new MapSnapshot(versionRow.version(), status, Map.copyOf(nodes), List.copyOf(segments));
    }

    /**
     * 发布载荷拓扑校验：节点/路段 ID 不重复，路段引用端点存在。
     */
    private void validateTopology(PublishMapRequest request) {
        Set<String> nodeIds = new HashSet<>();
        for (PublishMapRequest.NodeSpec node : request.nodes()) {
            if (!nodeIds.add(node.nodeId())) {
                throw new ApiException(ErrorCode.MAP_TOPOLOGY_INVALID,
                        "端点 ID 重复: " + node.nodeId());
            }
        }
        Set<String> segmentIds = new HashSet<>();
        for (PublishMapRequest.SegmentSpec segment : request.segments()) {
            if (!segmentIds.add(segment.segmentId())) {
                throw new ApiException(ErrorCode.MAP_TOPOLOGY_INVALID,
                        "路段 ID 重复: " + segment.segmentId());
            }
            if (!nodeIds.contains(segment.fromNodeId())) {
                throw new ApiException(ErrorCode.MAP_TOPOLOGY_INVALID,
                        "路段 " + segment.segmentId() + " 引用了不存在的起点端点: " + segment.fromNodeId());
            }
            if (!nodeIds.contains(segment.toNodeId())) {
                throw new ApiException(ErrorCode.MAP_TOPOLOGY_INVALID,
                        "路段 " + segment.segmentId() + " 引用了不存在的终点端点: " + segment.toNodeId());
            }
        }
    }

    /**
     * 规范化路段：valid 缺省视为 true；lengthM 缺省按起终节点几何距离计算。
     */
    private List<PublishMapRequest.SegmentSpec> normalizeSegments(PublishMapRequest request) {
        Map<String, PublishMapRequest.NodeSpec> nodeMap = new LinkedHashMap<>();
        request.nodes().forEach(n -> nodeMap.put(n.nodeId(), n));
        List<PublishMapRequest.SegmentSpec> normalized = new ArrayList<>();
        for (PublishMapRequest.SegmentSpec segment : request.segments()) {
            double length = segment.lengthM() == null ? 0.0 : segment.lengthM();
            if (length == 0.0) {
                PublishMapRequest.NodeSpec from = nodeMap.get(segment.fromNodeId());
                PublishMapRequest.NodeSpec to = nodeMap.get(segment.toNodeId());
                length = Math.hypot(to.x() - from.x(), to.y() - from.y());
            }
            normalized.add(new PublishMapRequest.SegmentSpec(
                    segment.segmentId(), segment.fromNodeId(), segment.toNodeId(),
                    length, !Boolean.FALSE.equals(segment.valid())));
        }
        return normalized;
    }

    /**
     * 计算路线输入的确定性 SHA-256 摘要（仅起终点坐标与航向），作为重放判定与缓存输入摘要；
     * 地图版本不混入摘要，而由缓存键的版本分区与请求绑定版本单独承担。
     */
    private static String inputHash(RouteSubmitRequest request) {
        String canonical = String.join("|",
                Double.toString(request.start().x()),
                Double.toString(request.start().y()),
                Double.toString(normalizeHeading(request.start().heading())),
                Double.toString(request.end().x()),
                Double.toString(request.end().y()),
                Double.toString(normalizeHeading(request.end().heading())));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static double normalizeHeading(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }
}
