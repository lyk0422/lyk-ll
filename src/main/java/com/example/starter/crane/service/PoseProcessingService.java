package com.example.starter.crane.service;

import com.example.starter.crane.NotFoundException;
import com.example.starter.crane.api.PoseAcceptance;
import com.example.starter.crane.api.PoseRequest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 位姿上报处理服务，是防碰撞判罚口径的核心。
 *
 * <p>判定口径：</p>
 * <ul>
 *   <li>读数按“塔机＋时间戳”唯一约束去重：重复读数直接幂等返回，不重复触发预警；</li>
 *   <li>时间戳归一化到 1 秒粒度；乱序到达不影响判定——每次写入后按时间戳顺序重放该塔机全部读数归位；</li>
 *   <li>位姿授权窗口为 [start_time, end_time]，<b>end_time 恰结束时刻的位姿点按授权内处理</b>；
 *       作业完成（COMPLETED）释放后，已声明窗口内的历史位姿始终合法，防止按“已释放”状态倒罚
 *       已结束的合法作业——该口径只依赖窗口数据、与释放事务的提交先后无关，保证释放竞态下结论唯一；
 *       作业取消（CANCELLED）仅对 released_at 之前（含）的位姿保留合法性，之后进入即判越限；</li>
 *   <li>越限段：同一重叠区连续未授权采样点构成一个越限事件；采样到区域外（zone 为空）、
 *       进入其他区、或该点已有授权，均视为本段退出；</li>
 *   <li>持续 3 秒未退出（last_ts - start_ts &gt;= 3 秒，1 秒粒度下即第 4 个连续越限采样点）自动下发制动；
 *       uk_brake_warning 唯一约束 + 塔机行锁保证同一越限事件只制动一次，重复触发返回首次指令；</li>
 *   <li>预警/制动事件不可删除；乱序归位导致预警起点前移时，仅修正事件并向 event_correction 追加留痕。</li>
 * </ul>
 */
@Service
public class PoseProcessingService {

    /** 持续未退出达到此时长即制动。 */
    static final long BRAKE_AFTER_SECONDS = 3L;

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_CLEARED = "CLEARED";
    static final String STATUS_BRAKED = "BRAKED";
    static final String STATUS_CLOSED_AFTER_BRAKE = "CLOSED_AFTER_BRAKE";

    private static final String RELEASE_COMPLETED = "COMPLETED";
    private static final String RELEASE_CANCELLED = "CANCELLED";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PoseProcessingService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 接收一条位姿读数并完成越限/制动判定。
     * READ_COMMITTED 下先对塔机行加 FOR UPDATE 行锁：同一塔机的并发上报严格串行，
     * 配合唯一约束形成唯一结论；跨表的释放并发由窗口判罚口径吸收，不依赖两阶段提交。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PoseAcceptance ingest(PoseRequest request) {
        if (request.craneCode() == null || request.craneCode().isBlank() || request.ts() == null) {
            throw new IllegalArgumentException("craneCode 与 ts 不能为空");
        }
        // 先锁塔机行：同一塔机的并发上报在此串行，同时校验塔机存在。
        Integer craneId = jdbc.query(
                "SELECT id FROM tower_crane WHERE crane_code = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt("id") : null, request.craneCode());
        if (craneId == null) {
            throw new NotFoundException("塔机不存在: " + request.craneCode());
        }
        if (request.zoneCode() != null && !request.zoneCode().isBlank()) {
            Integer zoneId = jdbc.query("SELECT id FROM overlap_zone WHERE zone_code = ?",
                    rs -> rs.next() ? rs.getInt("id") : null, request.zoneCode());
            if (zoneId == null) {
                throw new NotFoundException("重叠区不存在: " + request.zoneCode());
            }
        }

        // 1 秒粒度归一化（截断到秒）。
        Instant ts = request.ts().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String zoneCode = request.zoneCode() == null || request.zoneCode().isBlank()
                ? null : request.zoneCode();
        Instant now = clock.instant();

        // 去重落库：唯一约束 uk_pose_crane_ts 拦截重复（塔机＋时间戳）。
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO pose_reading (crane_code, ts, zone_code, slewing_angle_deg, "
                                + "amplitude_m, hook_height_m, received_at) VALUES (?,?,?,?,?,?,?)");
                ps.setString(1, request.craneCode());
                ps.setTimestamp(2, Timestamp.from(ts));
                ps.setString(3, zoneCode);
                ps.setBigDecimal(4, request.slewingAngleDeg());
                ps.setBigDecimal(5, request.amplitudeM());
                ps.setBigDecimal(6, request.hookHeightM());
                ps.setTimestamp(7, Timestamp.from(now));
                return ps;
            });
        } catch (DuplicateKeyException dup) {
            // 重复读数：不重放判定、不生成新事件。
            return PoseAcceptance.duplicateReading();
        }

        // 按时间戳归位重放该塔机全部读数，推导越限段。
        List<Sample> samples = jdbc.query(
                "SELECT ts, zone_code FROM pose_reading WHERE crane_code = ? ORDER BY ts, id",
                (rs, n) -> new Sample(rs.getTimestamp("ts").toInstant(), rs.getString("zone_code")),
                request.craneCode());
        // 一次性读入该塔机全部已批准授权窗口（含已释放），在内存判定，避免逐点查询并保证释放竞态口径。
        List<GrantWindow> grants = jdbc.query(
                "SELECT zone_code, start_time, end_time, status, release_type, released_at "
                        + "FROM zone_authorization WHERE crane_code = ? AND result = 'GRANTED'",
                (rs, n) -> new GrantWindow(
                        rs.getString("zone_code"),
                        rs.getTimestamp("start_time").toInstant(),
                        rs.getTimestamp("end_time").toInstant(),
                        rs.getString("status"),
                        rs.getString("release_type"),
                        rs.getTimestamp("released_at") == null ? null
                                : rs.getTimestamp("released_at").toInstant()),
                request.craneCode());
        List<IntrusionRun> runs = deriveRuns(request.craneCode(), samples, grants);

        return reconcile(request.craneCode(), runs);
    }

    /**
     * 按时间戳顺序归位，切分“连续未授权采样”越限段。
     */
    private List<IntrusionRun> deriveRuns(String craneCode, List<Sample> samples, List<GrantWindow> grants) {
        List<IntrusionRun> runs = new ArrayList<>();
        String runZone = null;
        Instant runStart = null;
        Instant runLast = null;

        for (Sample s : samples) {
            boolean intruding = s.zoneCode() != null
                    && !isAuthorized(grants, s.zoneCode(), s.ts());
            boolean sameRun = intruding && s.zoneCode().equals(runZone);
            if (sameRun) {
                runLast = s.ts();
                continue;
            }
            // 前一段在此采样点退出（无论该点是区外、其他区还是已有授权）。
            if (runZone != null) {
                runs.add(new IntrusionRun(runZone, runStart, runLast, s.ts()));
                runZone = null;
                runStart = null;
                runLast = null;
            }
            if (intruding) {
                runZone = s.zoneCode();
                runStart = s.ts();
                runLast = s.ts();
            }
        }
        if (runZone != null) {
            // 截至当前最后一个采样点仍在未授权区内，尚未观测到退出点。
            runs.add(new IntrusionRun(runZone, runStart, runLast, null));
        }
        return runs;
    }

    /**
     * 位姿点授权判定：
     * 窗口为 [start_time, end_time]（结束时刻含）；完成释放不收回历史窗口；
     * 取消释放仅保留 released_at 之前（含）的合法性；未释放的有效授权全程合法。
     */
    private boolean isAuthorized(List<GrantWindow> grants, String zoneCode, Instant ts) {
        for (GrantWindow g : grants) {
            if (!g.zoneCode().equals(zoneCode) || ts.isBefore(g.startTime()) || ts.isAfter(g.endTime())) {
                continue;
            }
            if (!"RELEASED".equals(g.status())) {
                return true;
            }
            if (RELEASE_COMPLETED.equals(g.releaseType())) {
                return true;
            }
            if (RELEASE_CANCELLED.equals(g.releaseType()) && g.releasedAt() != null
                    && !ts.isAfter(g.releasedAt())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将推导出的越限段与库中既存预警事件对齐：已存在则更新进展，不存在则创建，
     * 达到 3 秒阈值且未制动的下发制动。返回本次新生成的事件编号。
     */
    private PoseAcceptance reconcile(String craneCode, List<IntrusionRun> runs) {
        List<WarningRow> warnings = jdbc.query(
                "SELECT * FROM intrusion_warning WHERE crane_code = ? ORDER BY start_ts, id FOR UPDATE",
                (rs, n) -> mapWarning(rs), craneCode);
        boolean[] paired = new boolean[warnings.size()];
        List<String> newWarningCodes = new ArrayList<>();
        List<String> newBrakeCodes = new ArrayList<>();
        Instant now = clock.instant();

        for (IntrusionRun run : runs) {
            // 1) 同区同起点精确匹配（乱序归位后的稳定身份）。
            WarningRow target = null;
            int targetIdx = -1;
            for (int i = 0; i < warnings.size(); i++) {
                WarningRow w = warnings.get(i);
                if (!paired[i] && w.zoneCode().equals(run.zone())
                        && w.startTs().equals(run.start())) {
                    target = w;
                    targetIdx = i;
                    break;
                }
            }
            // 2) 乱序补点：既存预警起点落在本段内（晚于真实起点），吸收为同一事件并留痕前移。
            if (target == null) {
                for (int i = 0; i < warnings.size(); i++) {
                    WarningRow w = warnings.get(i);
                    if (!paired[i] && w.zoneCode().equals(run.zone())
                            && !w.startTs().isBefore(run.start())
                            && !w.startTs().isAfter(run.last())) {
                        target = w;
                        targetIdx = i;
                        if (!w.startTs().equals(run.start())) {
                            jdbc.update("UPDATE intrusion_warning SET start_ts = ?, updated_at = ? WHERE id = ?",
                                    Timestamp.from(run.start()), Timestamp.from(now), w.id());
                            jdbc.update(
                                    "INSERT INTO event_correction (warning_id, reason, detail, created_at) "
                                            + "VALUES (?,?,?,?)",
                                    w.id(), "OUT_OF_ORDER_REBASE",
                                    "乱序位姿按时间戳归位，预警起点由 " + w.startTs() + " 前移至 " + run.start(),
                                    Timestamp.from(now));
                            target = new WarningRow(w.id(), w.warningCode(), w.craneCode(), w.zoneCode(),
                                    run.start(), w.lastTs(), w.exitTs(), w.status(), w.brakeId());
                        }
                        break;
                    }
                }
            }
            if (target == null) {
                target = createWarning(craneCode, run, now, newBrakeCodes);
                newWarningCodes.add(target.warningCode());
            } else {
                paired[targetIdx] = true;
                target = updateProgress(target, run, now, newBrakeCodes);
            }
        }
        // 未匹配到的既存预警保持原样：事件不可删除（如需更正走修正留痕 API）。
        return PoseAcceptance.fresh(newWarningCodes, newBrakeCodes);
    }

    private WarningRow createWarning(String craneCode, IntrusionRun run,
            Instant now, List<String> newBrakeCodes) {
        String warningCode = warningCode(craneCode, run.zone(), run.start());
        boolean thresholdReached = !Duration.between(run.start(), run.last()).minusSeconds(BRAKE_AFTER_SECONDS).isNegative();
        String status = run.exit() == null
                ? (thresholdReached ? STATUS_BRAKED : STATUS_ACTIVE)
                : (thresholdReached ? STATUS_CLOSED_AFTER_BRAKE : STATUS_CLEARED);
        jdbc.update("INSERT INTO intrusion_warning (warning_code, crane_code, zone_code, start_ts, last_ts, "
                        + "exit_ts, status, brake_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                warningCode, craneCode, run.zone(),
                Timestamp.from(run.start()), Timestamp.from(run.last()),
                run.exit() == null ? null : Timestamp.from(run.exit()),
                status, null, Timestamp.from(now), Timestamp.from(now));
        WarningRow row = jdbc.queryForObject(
                "SELECT * FROM intrusion_warning WHERE warning_code = ?",
                (rs, n) -> mapWarning(rs), warningCode);
        if (thresholdReached) {
            String brakeCode = issueBrake(row, row.exitTs(), now);
            newBrakeCodes.add(brakeCode);
            row = jdbc.queryForObject("SELECT * FROM intrusion_warning WHERE id = ?",
                    (rs, n) -> mapWarning(rs), row.id());
        }
        return row;
    }

    private WarningRow updateProgress(WarningRow w, IntrusionRun run,
            Instant now, List<String> newBrakeCodes) {
        Instant lastTs = w.lastTs();
        Instant exitTs = w.exitTs();
        boolean changed = false;
        if (run.last().isAfter(lastTs)) {
            lastTs = run.last();
            changed = true;
        }
        if (exitTs == null && run.exit() != null) {
            exitTs = run.exit();
            changed = true;
        }
        boolean thresholdReached = !Duration.between(w.startTs(), lastTs).minusSeconds(BRAKE_AFTER_SECONDS).isNegative();

        boolean brakePending = w.brakeId() == null && thresholdReached;
        if (brakePending) {
            // 达到 3 秒阈值：制动一次；唯一约束兜底并发重复触发。
            String brakeCode = issueBrake(w, exitTs, now);
            newBrakeCodes.add(brakeCode);
            changed = true;
        }

        // 回读制动结果后统一持久化进展字段与状态，避免 last_ts/exit_ts 丢失。
        WarningRow fresh = jdbc.queryForObject("SELECT * FROM intrusion_warning WHERE id = ?",
                (rs, n) -> mapWarning(rs), w.id());
        String targetStatus = exitTs == null
                ? (fresh.brakeId() != null ? STATUS_BRAKED : STATUS_ACTIVE)
                : (fresh.brakeId() != null ? STATUS_CLOSED_AFTER_BRAKE : STATUS_CLEARED);
        if (changed || !targetStatus.equals(fresh.status())) {
            jdbc.update("UPDATE intrusion_warning SET last_ts = ?, exit_ts = ?, status = ?, updated_at = ? WHERE id = ?",
                    Timestamp.from(lastTs), exitTs == null ? null : Timestamp.from(exitTs),
                    targetStatus, Timestamp.from(now), w.id());
        }
        return new WarningRow(fresh.id(), fresh.warningCode(), fresh.craneCode(), fresh.zoneCode(),
                fresh.startTs(), lastTs, exitTs, targetStatus, fresh.brakeId());
    }

    /**
     * 下发制动指令；uk_brake_warning 唯一约束保证同一事件只成功一次，
     * 并发落败方回读首次指令（调用方通过事件查询拿到同一 commandCode）。
     *
     * @param exitTs 本次判定观测到的退出时刻（可空），用于确定制动时事件状态
     */
    private String issueBrake(WarningRow w, Instant exitTs, Instant now) {
        String commandCode = "BRK-" + w.warningCode();
        try {
            jdbc.update("INSERT INTO brake_command (command_code, warning_id, crane_code, zone_code, "
                            + "issued_at, created_at) VALUES (?,?,?,?,?,?)",
                    commandCode, w.id(), w.craneCode(), w.zoneCode(),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException already) {
            return jdbc.queryForObject("SELECT command_code FROM brake_command WHERE warning_id = ?",
                    String.class, w.id());
        }
        Long brakeId = jdbc.queryForObject("SELECT id FROM brake_command WHERE warning_id = ?",
                Long.class, w.id());
        String statusAfterBrake = exitTs == null ? STATUS_BRAKED : STATUS_CLOSED_AFTER_BRAKE;
        jdbc.update("UPDATE intrusion_warning SET brake_id = ?, status = ?, updated_at = ? WHERE id = ?",
                brakeId, statusAfterBrake, Timestamp.from(now), w.id());
        return commandCode;
    }

    private static String warningCode(String craneCode, String zoneCode, Instant start) {
        return "W-" + craneCode + "-" + zoneCode + "-" + start.getEpochSecond();
    }

    private WarningRow mapWarning(ResultSet rs) throws SQLException {
        return new WarningRow(
                rs.getLong("id"),
                rs.getString("warning_code"),
                rs.getString("crane_code"),
                rs.getString("zone_code"),
                rs.getTimestamp("start_ts").toInstant(),
                rs.getTimestamp("last_ts").toInstant(),
                rs.getTimestamp("exit_ts") == null ? null : rs.getTimestamp("exit_ts").toInstant(),
                rs.getString("status"),
                rs.getObject("brake_id", Long.class));
    }

    /** 单个位姿采样。 */
    private record Sample(Instant ts, String zoneCode) {
    }

    /** 该塔机一条已批准授权窗口（含释放状态），用于位姿点内存判罚。 */
    private record GrantWindow(String zoneCode, Instant startTime, Instant endTime,
            String status, String releaseType, Instant releasedAt) {
    }

    /** 一个连续越限段：[start, last] 采样点均未授权，exit 为观测到的退出采样时刻（null=未退出）。 */
    private record IntrusionRun(String zone, Instant start, Instant last, Instant exit) {
    }

    /** intrusion_warning 行对象。 */
    private record WarningRow(Long id, String warningCode, String craneCode, String zoneCode,
            Instant startTs, Instant lastTs, Instant exitTs, String status, Long brakeId) {
    }
}
