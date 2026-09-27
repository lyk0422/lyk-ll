package com.example.starter.service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.QueueEntry;

/**
 * 派单服务：派单幂等、班组同时刻唯一执行单、资源冲突时挂单等待、派单队列复算。
 */
@Service
public class DispatchService {

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;
    private final EscalationService escalation;
    private final TransactionTemplate tx;

    public DispatchService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store,
            EscalationService escalation, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
        this.escalation = escalation;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 派单：同 requestId 同参返回首次结果，同键异参抛 409；
     * 班组空闲则执行中，否则挂单等待。班组行锁保证同时刻唯一执行单。
     */
    public DispatchResponse dispatch(DispatchRequest req) {
        String fingerprint = Fingerprints.of(req.requestId(), req.workOrderId(), req.teamId());
        try {
            return tx.execute(status -> doDispatch(req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> findReplay(req.requestId(), fingerprint)
                    .orElseThrow(() -> e));
        }
    }

    private DispatchResponse doDispatch(DispatchRequest req, String fingerprint) {
        Optional<DispatchResponse> replay = findReplay(req.requestId(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        lockTeam(req.teamId());
        WorkOrderRow order = store.requireById(req.workOrderId());
        if (!"PENDING".equals(order.status()) && !"QUEUED".equals(order.status())) {
            throw new ConflictException("工单状态不可派单: " + order.status());
        }
        int executing = countExecuting(req.teamId());
        String result;
        if (executing == 0) {
            jdbc.update("UPDATE work_order SET status = 'EXECUTING', team_id = ?, executing_team_id = ?,"
                    + " version = version + 1 WHERE id = ?", req.teamId(), req.teamId(), req.workOrderId());
            store.addEvent(req.workOrderId(), "DISPATCHED", "派单至班组" + req.teamId());
            result = "DISPATCHED";
        } else {
            jdbc.update("UPDATE work_order SET status = 'QUEUED', team_id = ?, version = version + 1"
                    + " WHERE id = ?", req.teamId(), req.workOrderId());
            store.addEvent(req.workOrderId(), "QUEUED", "班组" + req.teamId() + "资源冲突, 挂单等待");
            result = "QUEUED";
        }
        jdbc.update("INSERT INTO dispatch_record (request_id, fingerprint, work_order_id, team_id, result, created_at)"
                + " VALUES (?,?,?,?,?,?)",
                req.requestId(), fingerprint, req.workOrderId(), req.teamId(), result,
                Timestamp.valueOf(clock.now()));
        escalation.evaluate(req.workOrderId());
        return new DispatchResponse(req.workOrderId(), req.teamId(), result,
                "DISPATCHED".equals(result) ? "EXECUTING" : "QUEUED", false);
    }

    private Optional<DispatchResponse> findReplay(String requestId, String fingerprint) {
        List<Object[]> rows = jdbc.query(
                "SELECT fingerprint, work_order_id, team_id, result FROM dispatch_record WHERE request_id = ?",
                (rs, rowNum) -> new Object[] {rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)},
                requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        if (!row[0].equals(fingerprint)) {
            throw new ConflictException("同键异参幂等冲突: requestId=" + requestId);
        }
        String result = (String) row[3];
        return Optional.of(new DispatchResponse((Long) row[1], (Long) row[2], result,
                "DISPATCHED".equals(result) ? "EXECUTING" : "QUEUED", true));
    }

    /**
     * 锁定班组行并校验存在性；同事务内后续派单判断以此串行化。
     */
    private void lockTeam(long teamId) {
        List<Long> rows = jdbc.query("SELECT id FROM repair_team WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), teamId);
        if (rows.isEmpty()) {
            throw new NotFoundException("班组不存在: " + teamId);
        }
    }

    private int countExecuting(long teamId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE executing_team_id = ? AND status = 'EXECUTING'",
                Integer.class, teamId);
        return count == null ? 0 : count;
    }

    /**
     * 复算派单队列：待派单与挂单工单按重要等级、影响户数降序、接报先后排序；feederId 为空时查全部。
     */
    public List<QueueEntry> queue(String feederId) {
        String sql = "SELECT id, feeder_id, important_level, affected_users, outage_start_at, status"
                + " FROM work_order WHERE status IN ('PENDING','QUEUED')"
                + (feederId == null ? "" : " AND feeder_id = ?")
                + WorkOrderStore.PRIORITY_ORDER;
        var mapper = (org.springframework.jdbc.core.RowMapper<QueueEntry>) (rs, rowNum) -> new QueueEntry(
                rs.getLong("id"), rs.getString("feeder_id"), rs.getString("important_level"),
                rs.getInt("affected_users"), rs.getTimestamp("outage_start_at").toLocalDateTime(),
                rs.getString("status"));
        return feederId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, feederId);
    }
}
