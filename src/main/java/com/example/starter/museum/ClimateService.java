package com.example.starter.museum;

import java.math.BigDecimal;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 展柜微环境监测服务：读数按“展柜＋采样时刻”去重、乱序按采样时刻归位；
 * 连续 3 个采样点越限（恰 3 个即触发）自动生成保护工单，合同挂起；
 * 工单 24 小时内须处置确认，恰满 24 小时未确认升级“紧急”并冻结展柜新入藏。
 */
@Service
public class ClimateService {

    /** 连续越限触发阈值：恰 3 个采样点即触发。 */
    static final int BREACH_TRIGGER = 3;
    /** 工单处置时限：24 小时（恰满未确认即升级）。 */
    static final long ORDER_DEADLINE_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final LoanService loanService;

    public ClimateService(JdbcTemplate jdbc, Clock clock, LoanService loanService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.loanService = loanService;
    }

    /**
     * 写入一条微环境读数：按展柜＋采样时刻去重；结案后到达的读数仅记迟到事件；
     * 乱序读数按采样时刻归位后重算连续越限序列，必要时生成保护工单并挂起合同。
     */
    @Transactional
    public Map<String, Object> ingest(long showcaseId, Instant sampledAt,
            BigDecimal temperature, BigDecimal humidity) {
        if (sampledAt == null || temperature == null || humidity == null) {
            throw ApiException.badRequest("采样时刻、温度、湿度均不能为空");
        }
        Map<String, Object> showcase = findShowcase(showcaseId);
        boolean outOfRange = isOutOfRange(showcase, temperature, humidity);
        LoanService.Contract active = loanService.findActiveContractByShowcase(showcaseId);
        final boolean late = active == null
                && loanService.findLatestClosedContractByShowcase(showcaseId) != null;
        long readingId;
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var ps = connection.prepareStatement(
                        "INSERT INTO climate_reading (showcase_id, sampled_at, temperature, humidity, out_of_range, late, created_at)"
                                + " VALUES (?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, showcaseId);
                ps.setTimestamp(2, Timestamp.from(sampledAt));
                ps.setBigDecimal(3, temperature);
                ps.setBigDecimal(4, humidity);
                ps.setBoolean(5, outOfRange);
                ps.setBoolean(6, late);
                ps.setTimestamp(7, Timestamp.from(Instant.now(clock)));
                return ps;
            }, keyHolder);
            readingId = keyHolder.getKey().longValue();
        } catch (DuplicateKeyException e) {
            Map<String, Object> existing = jdbc.queryForMap(
                    "SELECT * FROM climate_reading WHERE showcase_id = ? AND sampled_at = ?",
                    showcaseId, Timestamp.from(sampledAt));
            Map<String, Object> view = readingView(existing);
            view.put("deduplicated", true);
            return view;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("readingId", readingId);
        view.put("showcaseId", showcaseId);
        view.put("sampledAt", sampledAt.toString());
        view.put("temperature", temperature);
        view.put("humidity", humidity);
        view.put("outOfRange", outOfRange);
        view.put("late", late);
        view.put("deduplicated", false);
        if (late) {
            jdbc.update("INSERT INTO late_reading_event (showcase_id, sampled_at, recorded_at) VALUES (?,?,?)",
                    showcaseId, Timestamp.from(sampledAt), Timestamp.from(Instant.now(clock)));
            view.put("lateEventRecorded", true);
            return view;
        }
        int consecutive = 0;
        if (active != null) {
            consecutive = evaluateBreaches(showcaseId, active);
        }
        view.put("consecutiveBreaches", consecutive);
        escalateDueOrders();
        return view;
    }

    /**
     * 重算展柜连续越限序列（按采样时刻归位，仅统计上一已处置工单之后的读数），
     * 达到阈值且无未结工单时生成保护工单并挂起在展合同。
     *
     * @return 当前最长连续越限采样点数
     */
    private int evaluateBreaches(long showcaseId, LoanService.Contract active) {
        List<Map<String, Object>> readings = jdbc.queryForList(
                "SELECT r.sampled_at, r.out_of_range FROM climate_reading r"
                        + " WHERE r.showcase_id = ? AND r.late = FALSE"
                        + " AND r.sampled_at > COALESCE((SELECT MAX(o.watermark_at) FROM protection_order o"
                        + "     WHERE o.showcase_id = ? AND o.status = 'CONFIRMED'),"
                        + "   TIMESTAMP '1970-01-01 00:00:00')"
                        + " ORDER BY r.sampled_at",
                showcaseId, showcaseId);
        int maxRun = 0;
        int run = 0;
        for (Map<String, Object> reading : readings) {
            if (Boolean.TRUE.equals(reading.get("out_of_range"))) {
                run++;
                maxRun = Math.max(maxRun, run);
            } else {
                run = 0;
            }
        }
        if (maxRun >= BREACH_TRIGGER) {
            Integer openOrders = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM protection_order WHERE showcase_id = ? AND status IN ('OPEN','ESCALATED')",
                    Integer.class, showcaseId);
            if (openOrders != null && openOrders == 0) {
                Instant now = Instant.now(clock);
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var ps = connection.prepareStatement(
                            "INSERT INTO protection_order (showcase_id, contract_id, status, created_at, deadline_at)"
                                    + " VALUES (?,?,'OPEN',?,?)",
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, showcaseId);
                    ps.setLong(2, active.id());
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setTimestamp(4, Timestamp.from(now.plus(ORDER_DEADLINE_HOURS, ChronoUnit.HOURS)));
                    return ps;
                }, keyHolder);
                loanService.suspend(active.id(), "CLIMATE_ANOMALY");
            }
        }
        return maxRun;
    }

    /**
     * 处置确认：工单置为已确认；若展柜因升级被冻结则解除冻结。重复确认返回当前状态。
     */
    @Transactional
    public Map<String, Object> confirmOrder(long orderId, String handler) {
        if (handler == null || handler.isBlank()) {
            throw ApiException.badRequest("处置人 handler 不能为空");
        }
        Map<String, Object> order = getOrderRow(orderId);
        String status = (String) order.get("status");
        if (!"CONFIRMED".equals(status)) {
            Instant now = Instant.now(clock);
            int updated = jdbc.update(
                    "UPDATE protection_order SET status = 'CONFIRMED', confirmed_at = ?,"
                            + " watermark_at = (SELECT MAX(sampled_at) FROM climate_reading WHERE showcase_id = ?)"
                            + " WHERE id = ? AND status IN ('OPEN','ESCALATED')",
                    Timestamp.from(now), order.get("showcase_id"), orderId);
            if (updated == 0) {
                throw ApiException.conflict("工单状态已变更，确认未生效");
            }
            jdbc.update("UPDATE showcase SET frozen = FALSE WHERE id = ?", order.get("showcase_id"));
        }
        return orderView(getOrderRow(orderId));
    }

    /**
     * 升级检查：OPEN 工单恰满 24 小时（now >= deadline）未确认即升级“紧急”，并冻结展柜新入藏。
     *
     * @return 本次升级的工单数
     */
    @Transactional
    public int escalateDueOrders() {
        Instant now = Instant.now(clock);
        List<Long> dueIds = jdbc.queryForList(
                "SELECT id FROM protection_order WHERE status = 'OPEN' AND deadline_at <= ?",
                Long.class, Timestamp.from(now));
        for (Long id : dueIds) {
            int updated = jdbc.update(
                    "UPDATE protection_order SET status = 'ESCALATED', escalated_at = ?"
                            + " WHERE id = ? AND status = 'OPEN'",
                    Timestamp.from(now), id);
            if (updated == 1) {
                jdbc.update(
                        "UPDATE showcase SET frozen = TRUE WHERE id ="
                                + " (SELECT showcase_id FROM protection_order WHERE id = ?)",
                        id);
            }
        }
        return dueIds.size();
    }

    /**
     * 查询工单详情（含处置时限，供复算）。
     */
    public Map<String, Object> getOrder(long orderId) {
        return orderView(getOrderRow(orderId));
    }

    /**
     * 查询展柜越限轨迹（全部读数按采样时刻排序，供复算）。
     */
    public List<Map<String, Object>> readings(long showcaseId) {
        return jdbc.queryForList(
                "SELECT * FROM climate_reading WHERE showcase_id = ? ORDER BY sampled_at", showcaseId)
                .stream().map(this::readingView).toList();
    }

    /**
     * 查询展柜工单列表（含处置时限，供复算）。
     */
    public List<Map<String, Object>> orders(long showcaseId) {
        return jdbc.queryForList(
                "SELECT * FROM protection_order WHERE showcase_id = ? ORDER BY id", showcaseId)
                .stream().map(this::orderView).toList();
    }

    /**
     * 查询迟到读数事件（结案后到达的读数）。
     */
    public List<Map<String, Object>> lateEvents(long showcaseId) {
        return jdbc.queryForList(
                "SELECT * FROM late_reading_event WHERE showcase_id = ? ORDER BY id", showcaseId);
    }

    private Map<String, Object> findShowcase(long showcaseId) {
        try {
            return jdbc.queryForMap("SELECT * FROM showcase WHERE id = ?", showcaseId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("展柜不存在：" + showcaseId);
        }
    }

    private Map<String, Object> getOrderRow(long orderId) {
        try {
            return jdbc.queryForMap("SELECT * FROM protection_order WHERE id = ?", orderId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("保护工单不存在：" + orderId);
        }
    }

    private boolean isOutOfRange(Map<String, Object> showcase, BigDecimal temperature, BigDecimal humidity) {
        BigDecimal tempMin = (BigDecimal) showcase.get("temp_min");
        BigDecimal tempMax = (BigDecimal) showcase.get("temp_max");
        BigDecimal humidityMin = (BigDecimal) showcase.get("humidity_min");
        BigDecimal humidityMax = (BigDecimal) showcase.get("humidity_max");
        return temperature.compareTo(tempMin) < 0 || temperature.compareTo(tempMax) > 0
                || humidity.compareTo(humidityMin) < 0 || humidity.compareTo(humidityMax) > 0;
    }

    private Map<String, Object> readingView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("readingId", row.get("id"));
        view.put("showcaseId", row.get("showcase_id"));
        view.put("sampledAt", String.valueOf(row.get("sampled_at")));
        view.put("temperature", row.get("temperature"));
        view.put("humidity", row.get("humidity"));
        view.put("outOfRange", row.get("out_of_range"));
        view.put("late", row.get("late"));
        return view;
    }

    private Map<String, Object> orderView(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", row.get("id"));
        view.put("showcaseId", row.get("showcase_id"));
        view.put("contractId", row.get("contract_id"));
        view.put("status", row.get("status"));
        view.put("createdAt", String.valueOf(row.get("created_at")));
        view.put("deadlineAt", String.valueOf(row.get("deadline_at")));
        view.put("confirmedAt", row.get("confirmed_at") == null ? null : String.valueOf(row.get("confirmed_at")));
        view.put("escalatedAt", row.get("escalated_at") == null ? null : String.valueOf(row.get("escalated_at")));
        return view;
    }
}
