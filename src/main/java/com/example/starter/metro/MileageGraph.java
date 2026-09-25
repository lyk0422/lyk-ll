package com.example.starter.metro;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 线网里程图：加载合成里程表，提供最短路径里程、按线路拆分里程、
 * 线网最高票价里程与指定站最远站里程。区间按无向边处理。
 */
@Component
public class MileageGraph {

    private final JdbcTemplate jdbcTemplate;

    public MileageGraph(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 计算两站间最短路径（Dijkstra）。
     *
     * @param from 起点站编码
     * @param to   终点站编码
     * @return 最短路径结果，含总里程与按线路拆分里程
     */
    public ShortestPath shortestPath(String from, String to) {
        if (from == null || to == null) {
            throw MetroException.badRequest("STATION_INVALID", "车站编码不能为空");
        }
        if (from.equals(to)) {
            return new ShortestPath(0L, Map.of());
        }
        Map<String, List<Edge>> adjacency = loadAdjacency();
        if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) {
            throw MetroException.badRequest("STATION_UNKNOWN", "未知车站编码: " + from + " -> " + to);
        }
        Map<String, Long> dist = new HashMap<>();
        Map<String, Edge> prev = new HashMap<>();
        PriorityQueue<Map.Entry<String, Long>> queue =
                new PriorityQueue<>(Comparator.comparingLong(Map.Entry::getValue));
        dist.put(from, 0L);
        queue.offer(Map.entry(from, 0L));
        while (!queue.isEmpty()) {
            Map.Entry<String, Long> top = queue.poll();
            String station = top.getKey();
            long d = top.getValue();
            if (d > dist.getOrDefault(station, Long.MAX_VALUE)) {
                continue;
            }
            if (station.equals(to)) {
                break;
            }
            for (Edge edge : adjacency.getOrDefault(station, List.of())) {
                long nd = d + edge.distanceM();
                if (nd < dist.getOrDefault(edge.to(), Long.MAX_VALUE)) {
                    dist.put(edge.to(), nd);
                    prev.put(edge.to(), edge);
                    queue.offer(Map.entry(edge.to(), nd));
                }
            }
        }
        Long total = dist.get(to);
        if (total == null) {
            throw MetroException.badRequest("STATION_UNREACHABLE", "车站不可达: " + from + " -> " + to);
        }
        Map<String, Long> lineDistance = new LinkedHashMap<>();
        String cursor = to;
        while (!cursor.equals(from)) {
            Edge edge = prev.get(cursor);
            lineDistance.merge(edge.lineCode(), (long) edge.distanceM(), Long::sum);
            cursor = edge.from();
        }
        return new ShortestPath(total, lineDistance);
    }

    /**
     * 指定车站到线网任意其他站的最远最短路径里程（米）。
     */
    public long farthestDistanceM(String station) {
        Map<String, List<Edge>> adjacency = loadAdjacency();
        if (!adjacency.containsKey(station)) {
            throw MetroException.badRequest("STATION_UNKNOWN", "未知车站编码: " + station);
        }
        long max = 0L;
        for (String other : adjacency.keySet()) {
            if (!other.equals(station)) {
                max = Math.max(max, shortestPath(station, other).totalDistanceM());
            }
        }
        return max;
    }

    /**
     * 线网任意两站间最短路径的最大里程（米）。
     */
    public long maxNetworkDistanceM() {
        Map<String, List<Edge>> adjacency = loadAdjacency();
        long max = 0L;
        List<String> stations = new ArrayList<>(adjacency.keySet());
        for (int i = 0; i < stations.size(); i++) {
            for (int j = i + 1; j < stations.size(); j++) {
                max = Math.max(max, shortestPath(stations.get(i), stations.get(j)).totalDistanceM());
            }
        }
        return max;
    }

    /**
     * 判断车站编码是否存在于线网。
     */
    public boolean stationExists(String station) {
        return station != null && loadAdjacency().containsKey(station);
    }

    private Map<String, List<Edge>> loadAdjacency() {
        Map<String, List<Edge>> adjacency = new HashMap<>();
        for (Edge edge : allEdges()) {
            adjacency.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.to(), k -> new ArrayList<>())
                    .add(new Edge(edge.to(), edge.from(), edge.lineCode(), edge.distanceM()));
        }
        return adjacency;
    }

    /**
     * 加载全部区间（供测试与内部复算）。
     */
    List<Edge> allEdges() {
        return jdbcTemplate.query(
                "SELECT from_station, to_station, line_code, distance_m FROM metro_segment",
                (rs, rowNum) -> new Edge(rs.getString("from_station"), rs.getString("to_station"),
                        rs.getString("line_code"), rs.getInt("distance_m")));
    }

    /**
     * 最短路径结果。
     *
     * @param totalDistanceM 总里程（米）
     * @param lineDistanceM  按线路拆分的承运里程（米），键为线路编码
     */
    public record ShortestPath(long totalDistanceM, Map<String, Long> lineDistanceM) {
    }

    /**
     * 邻接边。
     */
    record Edge(String from, String to, String lineCode, int distanceM) {
    }
}
