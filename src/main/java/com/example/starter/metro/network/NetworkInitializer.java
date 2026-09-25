package com.example.starter.metro.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合成线网初始化器：定义两条线路及站间里程，启动时计算任意两站间
 * 最短路径里程与按线路拆分的承运里程，写入 mileage / route_line_mileage 表。
 * 表内已有数据时跳过，保证幂等。
 */
@Component
public class NetworkInitializer implements ApplicationRunner {

    /** 线路段定义：起站、讫站、里程（公里）、所属线路。 */
    private record Segment(String from, String to, int km, String line) {
    }

    /** 合成线网：L1 与 L2 在 S3 换乘。 */
    private static final List<Segment> SEGMENTS = List.of(
            new Segment("S1", "S2", 3, "L1"),
            new Segment("S2", "S3", 4, "L1"),
            new Segment("S3", "S4", 3, "L1"),
            new Segment("S4", "S5", 2, "L1"),
            new Segment("S3", "T1", 5, "L2"),
            new Segment("T1", "T2", 4, "L2"),
            new Segment("T2", "T3", 3, "L2"));

    private static final Map<String, String> STATION_LINES = Map.of(
            "S1", "L1", "S2", "L1", "S3", "L1", "S4", "L1", "S5", "L1",
            "T1", "L2", "T2", "L2", "T3", "L2");

    private final JdbcTemplate jdbcTemplate;

    public NetworkInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM station", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        STATION_LINES.forEach((code, line) -> jdbcTemplate.update(
                "INSERT INTO station(code, name, line_code) VALUES (?,?,?)",
                code, "合成站-" + code, line));

        Map<String, List<Edge>> graph = buildGraph();
        for (String source : STATION_LINES.keySet()) {
            Map<String, Path> paths = dijkstra(graph, source);
            for (String target : STATION_LINES.keySet()) {
                if (source.equals(target)) {
                    jdbcTemplate.update(
                            "INSERT INTO mileage(from_station, to_station, distance_km) VALUES (?,?,0)",
                            source, target);
                    jdbcTemplate.update(
                            "INSERT INTO route_line_mileage(from_station, to_station, line_code, distance_km)"
                                    + " VALUES (?,?,?,0)",
                            source, target, STATION_LINES.get(source));
                    continue;
                }
                Path path = paths.get(target);
                jdbcTemplate.update(
                        "INSERT INTO mileage(from_station, to_station, distance_km) VALUES (?,?,?)",
                        source, target, path.totalKm);
                Map<String, Integer> byLine = new HashMap<>();
                for (Edge edge : path.edges) {
                    byLine.merge(edge.line, edge.km, Integer::sum);
                }
                byLine.forEach((line, km) -> jdbcTemplate.update(
                        "INSERT INTO route_line_mileage(from_station, to_station, line_code, distance_km)"
                                + " VALUES (?,?,?,?)",
                        source, target, line, km));
            }
        }
    }

    private record Edge(String to, int km, String line) {
    }

    private record Path(int totalKm, List<Edge> edges) {
    }

    private Map<String, List<Edge>> buildGraph() {
        Map<String, List<Edge>> graph = new HashMap<>();
        for (Segment segment : SEGMENTS) {
            graph.computeIfAbsent(segment.from, k -> new ArrayList<>())
                    .add(new Edge(segment.to, segment.km, segment.line));
            graph.computeIfAbsent(segment.to, k -> new ArrayList<>())
                    .add(new Edge(segment.from, segment.km, segment.line));
        }
        return graph;
    }

    /**
     * Dijkstra 最短路径；合成线网最短路径唯一，按线路汇总承运里程确定。
     */
    private Map<String, Path> dijkstra(Map<String, List<Edge>> graph, String source) {
        Map<String, Path> best = new HashMap<>();
        best.put(source, new Path(0, List.of()));
        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.comparingInt(node -> best.get(node).totalKm));
        queue.add(source);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            Path currentPath = best.get(current);
            for (Edge edge : graph.getOrDefault(current, List.of())) {
                int candidate = currentPath.totalKm + edge.km;
                Path known = best.get(edge.to);
                if (known == null || candidate < known.totalKm) {
                    List<Edge> edges = new ArrayList<>(currentPath.edges);
                    edges.add(edge);
                    best.put(edge.to, new Path(candidate, edges));
                    queue.add(edge.to);
                }
            }
        }
        return best;
    }
}
