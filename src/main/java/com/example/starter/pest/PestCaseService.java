package com.example.starter.pest;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotentExecutor;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 虫情处置链服务：上报、确认、虫迹登记、解除申请与双方确认解除。
 *
 * <p>写操作均通过 {@link IdempotentExecutor} 执行，事务在幂等锁内提交，
 * 保证并发下同键同参返回首次结果、同库区同时刻仅一条进行中处置链。
 */
@Service
public class PestCaseService {

    /** 已上报（尚未确认，不拦截出库）。 */
    public static final String STATUS_REPORTED = "REPORTED";
    /** 已确认，处置中，禁止该库区出库登记。 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 已投放，进入 14 天监测期。 */
    public static final String STATUS_TREATED = "TREATED";
    /** 已申请解除，待消杀与库房双方确认。 */
    public static final String STATUS_RELEASE_APPLIED = "RELEASE_APPLIED";
    /** 已解除，库区恢复正常。 */
    public static final String STATUS_RELEASED = "RELEASED";

    /** 投放后监测期天数。 */
    static final int MONITOR_DAYS = 14;
    /** 相邻库区虫情统计窗口天数。 */
    static final int ADJACENT_WINDOW_DAYS = 90;

    private static final RowMapper<CaseRow> CASE_MAPPER = (rs, rowNum) -> new CaseRow(
            rs.getLong("id"),
            rs.getLong("zone_id"),
            rs.getString("status"),
            rs.getString("reporter"),
            rs.getString("description"),
            rs.getObject("reported_at", LocalDateTime.class),
            rs.getObject("confirmed_at", LocalDateTime.class),
            rs.getObject("treated_at", LocalDateTime.class),
            rs.getObject("release_applied_at", LocalDateTime.class),
            rs.getObject("disinfect_confirmed_at", LocalDateTime.class),
            rs.getObject("storage_confirmed_at", LocalDateTime.class),
            rs.getObject("released_at", LocalDateTime.class));

    private static final RowMapper<Zone> ZONE_MAPPER = (rs, rowNum) -> new Zone(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            splitAdjacent(rs.getString("adjacent_codes")));

    private final JdbcClient jdbc;
    private final IdempotentExecutor idempotent;
    private final Clock clock;

    public PestCaseService(JdbcClient jdbc, IdempotentExecutor idempotent, Clock clock) {
        this.jdbc = jdbc;
        this.idempotent = idempotent;
        this.clock = clock;
    }

    /**
     * 虫情上报：按库区登记，创建处置链并占用库区处置位；同库区已有进行中处置链时拒绝（409）。
     * 上报后检查相邻组团 90 日窗口，恰第 2 次虫情时触发整体预防性消杀评估。
     */
    public PestDtos.CaseResponse report(String zoneCode, PestDtos.ReportRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zoneCode", zoneCode);
        payload.put("reporter", request.reporter());
        payload.put("description", request.description());
        return idempotent.execute(request.requestId(), payload, PestDtos.CaseResponse.class, () -> {
            Zone zone = zoneByCode(zoneCode);
            LocalDateTime now = LocalDateTime.now(clock);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO pest_case (zone_id, status, reporter, description, reported_at)"
                            + " VALUES (?, ?, ?, ?, ?)")
                    .param(zone.id()).param(STATUS_REPORTED).param(request.reporter())
                    .param(request.description()).param(now)
                    .update(keyHolder, "id");
            long caseId = keyHolder.getKey().longValue();
            try {
                jdbc.sql("INSERT INTO zone_active_case (zone_id, case_id, created_at) VALUES (?, ?, ?)")
                        .param(zone.id()).param(caseId).param(now)
                        .update();
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("该库区已存在进行中的处置链，不允许重复上报");
            }
            triggerEvaluationIfSecond(zone, caseId, now);
            return new PestDtos.CaseResponse(caseId, zone.code(), STATUS_REPORTED);
        });
    }

    /**
     * 确认虫情：处置链进入处置状态，此后该库区禁止出库登记。
     */
    public PestDtos.CaseResponse confirm(long caseId, PestDtos.OperatorRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("operator", request.operator());
        return idempotent.execute(request.requestId(), payload, PestDtos.CaseResponse.class, () -> {
            CaseRow row = mustFind(caseId);
            if (!STATUS_REPORTED.equals(row.status())) {
                throw ApiException.conflict("仅已上报状态的处置链可确认");
            }
            jdbc.sql("UPDATE pest_case SET status = ?, confirmed_at = ? WHERE id = ?")
                    .param(STATUS_CONFIRMED).param(LocalDateTime.now(clock)).param(caseId)
                    .update();
            return new PestDtos.CaseResponse(row.id(), zoneCodeOf(row.zoneId()), STATUS_CONFIRMED);
        });
    }

    /**
     * 登记新增虫迹：投放后监测期内存在虫迹将阻止解除申请。
     */
    public PestDtos.TraceResponse addTrace(long caseId, PestDtos.TraceRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("description", request.description());
        return idempotent.execute(request.requestId(), payload, PestDtos.TraceResponse.class, () -> {
            CaseRow row = mustFind(caseId);
            if (STATUS_RELEASED.equals(row.status())) {
                throw ApiException.conflict("处置链已解除，不可登记虫迹");
            }
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO pest_trace (case_id, description, found_at) VALUES (?, ?, ?)")
                    .param(caseId).param(request.description()).param(LocalDateTime.now(clock))
                    .update(keyHolder, "id");
            return new PestDtos.TraceResponse(keyHolder.getKey().longValue(), caseId);
        });
    }

    /**
     * 申请解除：须投放后恰满 14 天且监测期内无新增虫迹。
     */
    public PestDtos.CaseResponse applyRelease(long caseId, PestDtos.OperatorRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("operator", request.operator());
        return idempotent.execute(request.requestId(), payload, PestDtos.CaseResponse.class, () -> {
            CaseRow row = mustFind(caseId);
            if (!STATUS_TREATED.equals(row.status())) {
                throw ApiException.conflict("仅监测期（已投放）状态可申请解除");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime eligibleAt = row.treatedAt().plusDays(MONITOR_DAYS);
            if (now.isBefore(eligibleAt)) {
                throw ApiException.badRequest("投放后监测期未满 14 天，不得申请解除");
            }
            long traces = jdbc.sql("SELECT COUNT(*) FROM pest_trace WHERE case_id = ? AND found_at > ?")
                    .param(caseId).param(row.treatedAt())
                    .query(Long.class).single();
            if (traces > 0) {
                throw ApiException.badRequest("监测期内存在新增虫迹，不得申请解除");
            }
            jdbc.sql("UPDATE pest_case SET status = ?, release_applied_at = ? WHERE id = ?")
                    .param(STATUS_RELEASE_APPLIED).param(now).param(caseId)
                    .update();
            return new PestDtos.CaseResponse(row.id(), zoneCodeOf(row.zoneId()), STATUS_RELEASE_APPLIED);
        });
    }

    /**
     * 解除确认：消杀与库房双方均确认后处置链解除，库区恢复出库。
     */
    public PestDtos.CaseResponse confirmRelease(long caseId, PestDtos.SideRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("side", request.side());
        return idempotent.execute(request.requestId(), payload, PestDtos.CaseResponse.class, () -> {
            CaseRow row = mustFind(caseId);
            if (!STATUS_RELEASE_APPLIED.equals(row.status())) {
                throw ApiException.conflict("须先申请解除后方可确认");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if ("DISINFECTION".equals(request.side())) {
                if (row.disinfectConfirmedAt() != null) {
                    throw ApiException.conflict("消杀方已确认，请勿重复确认");
                }
                jdbc.sql("UPDATE pest_case SET disinfect_confirmed_at = ? WHERE id = ?")
                        .param(now).param(caseId).update();
            } else if ("STORAGE".equals(request.side())) {
                if (row.storageConfirmedAt() != null) {
                    throw ApiException.conflict("库房方已确认，请勿重复确认");
                }
                jdbc.sql("UPDATE pest_case SET storage_confirmed_at = ? WHERE id = ?")
                        .param(now).param(caseId).update();
            } else {
                throw ApiException.badRequest("未知确认方: " + request.side());
            }
            CaseRow refreshed = mustFind(caseId);
            String zoneCode = zoneCodeOf(row.zoneId());
            if (refreshed.disinfectConfirmedAt() != null && refreshed.storageConfirmedAt() != null) {
                jdbc.sql("UPDATE pest_case SET status = ?, released_at = ? WHERE id = ?")
                        .param(STATUS_RELEASED).param(now).param(caseId).update();
                jdbc.sql("DELETE FROM zone_active_case WHERE zone_id = ?")
                        .param(row.zoneId()).update();
                return new PestDtos.CaseResponse(caseId, zoneCode, STATUS_RELEASED);
            }
            return new PestDtos.CaseResponse(caseId, zoneCode, STATUS_RELEASE_APPLIED);
        });
    }

    /**
     * 整体预防性消杀评估列表。
     */
    public List<PestDtos.EvaluationResponse> listEvaluations() {
        return jdbc.sql("SELECT id, zone_codes, trigger_case_id, reason, created_at"
                        + " FROM preventive_evaluation ORDER BY id")
                .query((rs, rowNum) -> new PestDtos.EvaluationResponse(
                        rs.getLong("id"),
                        rs.getString("zone_codes"),
                        rs.getLong("trigger_case_id"),
                        rs.getString("reason"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }

    /**
     * 处置链详情查询。
     */
    public PestDtos.CaseDetailResponse detail(long caseId) {
        CaseRow row = mustFind(caseId);
        return new PestDtos.CaseDetailResponse(row.id(), zoneCodeOf(row.zoneId()), row.status(),
                row.reporter(), row.description(), row.reportedAt(), row.confirmedAt(), row.treatedAt(),
                row.releaseAppliedAt(), row.disinfectConfirmedAt(), row.storageConfirmedAt(), row.releasedAt());
    }

    /**
     * 按主键查询处置链，不存在时抛出 404。
     */
    public CaseRow mustFind(long caseId) {
        return jdbc.sql("SELECT * FROM pest_case WHERE id = ?")
                .param(caseId)
                .query(CASE_MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("处置链不存在: " + caseId));
    }

    /**
     * 按编码查询库区，不存在时抛出 404。
     */
    public Zone zoneByCode(String code) {
        return jdbc.sql("SELECT id, code, name, adjacent_codes FROM archive_zone WHERE code = ?")
                .param(code)
                .query(ZONE_MAPPER)
                .optional()
                .orElseThrow(() -> ApiException.notFound("库区不存在: " + code));
    }

    private String zoneCodeOf(long zoneId) {
        return jdbc.sql("SELECT code FROM archive_zone WHERE id = ?")
                .param(zoneId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> ApiException.notFound("库区不存在: " + zoneId));
    }

    /**
     * 相邻组团评估：以邻接关系求连通组团，统计组团内 90 日窗口的既有虫情数，
     * 恰为第 2 次（既存 1 次）时生成整体预防性消杀评估；第 3 次起不再重复触发。
     */
    private void triggerEvaluationIfSecond(Zone zone, long newCaseId, LocalDateTime now) {
        List<Zone> all = jdbc.sql("SELECT id, code, name, adjacent_codes FROM archive_zone")
                .query(ZONE_MAPPER).list();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (Zone z : all) {
            adjacency.put(z.code(), z.adjacentCodes());
        }
        Set<String> cluster = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(zone.code());
        while (!queue.isEmpty()) {
            String code = queue.poll();
            if (cluster.add(code)) {
                queue.addAll(adjacency.getOrDefault(code, List.of()));
            }
        }
        List<String> sorted = cluster.stream().sorted().toList();
        String placeholders = String.join(", ", Collections.nCopies(sorted.size(), "?"));
        JdbcClient.StatementSpec spec = jdbc.sql(
                "SELECT COUNT(*) FROM pest_case c JOIN archive_zone z ON c.zone_id = z.id"
                        + " WHERE z.code IN (" + placeholders + ") AND c.reported_at >= ? AND c.id <> ?");
        for (String code : sorted) {
            spec = spec.param(code);
        }
        long prior = spec.param(now.minusDays(ADJACENT_WINDOW_DAYS)).param(newCaseId)
                .query(Long.class).single();
        if (prior == 1) {
            jdbc.sql("INSERT INTO preventive_evaluation (zone_codes, trigger_case_id, reason, created_at)"
                            + " VALUES (?, ?, ?, ?)")
                    .param(String.join(",", sorted))
                    .param(newCaseId)
                    .param("相邻库区 90 日内连续第 2 次虫情，触发整体预防性消杀评估")
                    .param(now)
                    .update();
        }
    }

    private static List<String> splitAdjacent(String adjacentCodes) {
        if (adjacentCodes == null || adjacentCodes.isBlank()) {
            return List.of();
        }
        return List.of(adjacentCodes.split(","));
    }
}
