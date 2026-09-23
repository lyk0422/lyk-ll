package com.example.starter.rosbridge.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 网关全部持久化状态的 JDBC 访问层。所有 CAS/游标分配语句均在数据库侧完成，
 * 配合事务保证多线程与多客户端下的引用计数、游标单调性和“只完成一次”语义。
 * 仅依赖标准 SQL/H2 MySQL 兼容方言。
 */
@Repository
public class GatewayRepository {

    private final JdbcTemplate jdbc;

    public GatewayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 客户端 ----------------

    private static final RowMapper<ClientRow> CLIENT_MAPPER = (rs, n) -> new ClientRow(
            rs.getString("client_id"),
            rs.getInt("connected") == 1,
            rs.getInt("rate_limit_per_sec"),
            rs.getInt("queue_cap"),
            rs.getLong("connected_at"),
            (Long) rs.getObject("disconnected_at"));

    /** 连接建立/重连：插入或重新激活客户端行。 */
    public void upsertConnectedClient(String clientId, int ratePerSec, int queueCap, long now) {
        jdbc.update("""
                MERGE INTO ws_client
                    (client_id, connected, rate_limit_per_sec, queue_cap, connected_at, disconnected_at)
                KEY (client_id)
                VALUES (?, 1, ?, ?, ?, NULL)
                """, clientId, ratePerSec, queueCap, now);
    }

    public void markDisconnected(String clientId, long now) {
        jdbc.update("UPDATE ws_client SET connected = 0, disconnected_at = ? WHERE client_id = ?",
                now, clientId);
    }

    public ClientRow getClient(String clientId) {
        List<ClientRow> rows = jdbc.query(
                "SELECT * FROM ws_client WHERE client_id = ?", CLIENT_MAPPER, clientId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 共享订阅 ----------------

    private static final RowMapper<SharedSubscriptionRow> SHARED_MAPPER = (rs, n) ->
            new SharedSubscriptionRow(
                    rs.getLong("id"),
                    rs.getString("topic"),
                    rs.getString("msg_type"),
                    rs.getString("qos_key"),
                    rs.getInt("ref_count"),
                    rs.getInt("upstream_active") == 1,
                    rs.getLong("next_cursor"),
                    (Long) rs.getObject("grace_until"),
                    rs.getLong("created_at"));

    public SharedSubscriptionRow findShared(String topic, String type, String qosKey) {
        List<SharedSubscriptionRow> rows = jdbc.query("""
                SELECT * FROM shared_subscription WHERE topic = ? AND msg_type = ? AND qos_key = ?
                """, SHARED_MAPPER, topic, type, qosKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SharedSubscriptionRow findSharedById(long sharedId) {
        List<SharedSubscriptionRow> rows = jdbc.query(
                "SELECT * FROM shared_subscription WHERE id = ?", SHARED_MAPPER, sharedId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SharedSubscriptionRow> listSharedSubs() {
        return jdbc.query("SELECT * FROM shared_subscription", SHARED_MAPPER);
    }

    /** 新建共享订阅（ref_count=0，upstream 未建立，游标从 1 开始）。返回新行。 */
    public SharedSubscriptionRow insertShared(String topic, String type, String qosKey, long now) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO shared_subscription
                        (topic, msg_type, qos_key, ref_count, next_cursor,
                         upstream_active, grace_until, created_at)
                    VALUES (?, ?, ?, 0, 1, 0, NULL, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, topic);
            ps.setString(2, type);
            ps.setString(3, qosKey);
            ps.setLong(4, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return findSharedById(key == null ? -1L : key.longValue());
    }

    /**
     * 客户端订阅时的引用计数获取：ref_count + 1 并清除宽限、保持/建立上游。
     * 返回更新后的行。
     */
    public SharedSubscriptionRow acquireReference(long sharedId) {
        jdbc.update("""
                UPDATE shared_subscription
                SET ref_count = ref_count + 1, grace_until = NULL
                WHERE id = ?
                """, sharedId);
        return findSharedById(sharedId);
    }

    /**
     * 客户端取消/断连时释放一个引用；ref_count - 1，若归零则进入宽限。
     * 仅当 ref_count &gt; 0 时递减，保证幂等。返回更新后的行。
     */
    public SharedSubscriptionRow releaseReference(long sharedId, long graceUntil) {
        jdbc.update("""
                UPDATE shared_subscription
                SET ref_count = ref_count - 1,
                    grace_until = CASE WHEN ref_count - 1 = 0 THEN ? ELSE grace_until END
                WHERE id = ? AND ref_count > 0
                """, graceUntil, sharedId);
        return findSharedById(sharedId);
    }

    /**
     * 宽限期结束且引用仍为 0 时释放上游。CAS：仅当 ref_count=0 时才允许，
     * 宽限期内的重新订阅会让 ref_count&gt;0，从而阻止释放。释放意味着该共享订阅的
     * 上游与留存全部废弃，因此同时把游标复位为 1，供未来重建的全新上游使用。
     * 返回是否实际释放。
     */
    public boolean tryReleaseUpstreamAfterGrace(long sharedId, long now) {
        int updated = jdbc.update("""
                UPDATE shared_subscription
                SET upstream_active = 0, grace_until = NULL, next_cursor = 1
                WHERE id = ? AND ref_count = 0
                  AND grace_until IS NOT NULL AND grace_until <= ?
                """, sharedId, now);
        return updated > 0;
    }

    /** 标记上游已建立；仅在尚未建立时生效，返回是否由本次调用建立（幂等保证）。 */
    public boolean tryMarkUpstreamActive(long sharedId) {
        int updated = jdbc.update("""
                UPDATE shared_subscription SET upstream_active = 1
                WHERE id = ? AND upstream_active = 0
                """, sharedId);
        return updated > 0;
    }

    /**
     * 分配下一个单调游标。采用“读取当前值 + 条件 UPDATE 认领”的乐观 CAS 循环：
     * UPDATE 的行锁与 {@code WHERE next_cursor = ?} 保证并发下每个游标只被一个调用者
     * 认领，冲突方重试。不依赖外部事务边界，直接实例化仓储时也正确。
     */
    public long allocateCursor(long sharedId) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            Long current = jdbc.queryForObject(
                    "SELECT next_cursor FROM shared_subscription WHERE id = ?",
                    Long.class, sharedId);
            if (current == null) {
                throw new IllegalStateException("共享订阅不存在: " + sharedId);
            }
            int updated = jdbc.update("""
                    UPDATE shared_subscription SET next_cursor = ?
                    WHERE id = ? AND next_cursor = ?
                    """, current + 1, sharedId, current);
            if (updated == 1) {
                return current;
            }
            // 并发认领冲突：重读再试
        }
        throw new IllegalStateException("游标分配连续冲突，放弃: " + sharedId);
    }

    // ---------------- 客户端订阅 ----------------

    private static final RowMapper<ClientSubscriptionRow> CS_MAPPER = (rs, n) ->
            new ClientSubscriptionRow(
                    rs.getString("client_id"),
                    rs.getLong("shared_id"),
                    rs.getLong("last_ack_cursor"),
                    rs.getString("status"),
                    rs.getLong("subscribed_at"),
                    (Long) rs.getObject("grace_until"));

    public ClientSubscriptionRow findClientSub(String clientId, long sharedId) {
        List<ClientSubscriptionRow> rows = jdbc.query("""
                SELECT * FROM client_subscription WHERE client_id = ? AND shared_id = ?
                """, CS_MAPPER, clientId, sharedId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ClientSubscriptionRow> listClientSubs(String clientId) {
        return jdbc.query(
                "SELECT * FROM client_subscription WHERE client_id = ?", CS_MAPPER, clientId);
    }

    public List<ClientSubscriptionRow> listClientSubsForShared(long sharedId) {
        return jdbc.query(
                "SELECT * FROM client_subscription WHERE shared_id = ?", CS_MAPPER, sharedId);
    }

    /** 首次订阅：插入 ACTIVE 订阅，游标从 0 开始。 */
    public void insertClientSub(String clientId, long sharedId, long now) {
        jdbc.update("""
                INSERT INTO client_subscription
                    (client_id, shared_id, last_ack_cursor, status, subscribed_at, grace_until)
                VALUES (?, ?, 0, 'ACTIVE', ?, NULL)
                """, clientId, sharedId, now);
    }

    /**
     * 重新激活宽限中的订阅（幂等）：仅 GRACING 行会被更新，已 ACTIVE 的重复订阅不受影响。
     * 返回是否发生了状态迁移。
     */
    public boolean reactivateClientSub(String clientId, long sharedId) {
        int updated = jdbc.update("""
                UPDATE client_subscription SET status = 'ACTIVE', grace_until = NULL
                WHERE client_id = ? AND shared_id = ? AND status = 'GRACING'
                """, clientId, sharedId);
        return updated > 0;
    }

    /** 断连：该客户端全部 ACTIVE 订阅进入宽限。 */
    public void markClientSubsGracing(String clientId, long graceUntil) {
        jdbc.update("""
                UPDATE client_subscription SET status = 'GRACING', grace_until = ?
                WHERE client_id = ? AND status = 'ACTIVE'
                """, graceUntil, clientId);
    }

    /** 主动取消：删除订阅关系（幂等）。 */
    public int deleteClientSub(String clientId, long sharedId) {
        return jdbc.update(
                "DELETE FROM client_subscription WHERE client_id = ? AND shared_id = ?",
                clientId, sharedId);
    }

    /** 宽限期结束释放上游时，清理该共享订阅下仍处于 GRACING 的关系（客户端需重新订阅）。 */
    public int deleteGracingSubsForShared(long sharedId) {
        return jdbc.update(
                "DELETE FROM client_subscription WHERE shared_id = ? AND status = 'GRACING'",
                sharedId);
    }

    /** 保存确认位置：仅接受比已记录游标更大的确认，保证单调、拒绝回退。 */
    public boolean updateAck(String clientId, long sharedId, long cursor) {
        int updated = jdbc.update("""
                UPDATE client_subscription SET last_ack_cursor = ?
                WHERE client_id = ? AND shared_id = ? AND last_ack_cursor < ?
                """, cursor, clientId, sharedId, cursor);
        return updated > 0;
    }

    // ---------------- 消息留存 ----------------

    private static final RowMapper<TopicMessageRow> MSG_MAPPER = (rs, n) -> new TopicMessageRow(
            rs.getLong("shared_id"), rs.getLong("cursor"),
            rs.getString("payload"), rs.getLong("created_at"));

    public void insertMessage(long sharedId, long cursor, String payloadJson, long now) {
        jdbc.update("""
                INSERT INTO topic_message (shared_id, cursor, payload, created_at)
                VALUES (?, ?, ?, ?)
                """, sharedId, cursor, payloadJson, now);
    }

    /** 取严格大于 afterCursor 的留存消息，按游标升序——重连续传的数据源。 */
    public List<TopicMessageRow> messagesAfter(long sharedId, long afterCursor) {
        return jdbc.query("""
                SELECT * FROM topic_message
                WHERE shared_id = ? AND cursor > ?
                ORDER BY cursor ASC
                """, MSG_MAPPER, sharedId, afterCursor);
    }

    public Long maxCursor(long sharedId) {
        return jdbc.queryForObject(
                "SELECT MAX(cursor) FROM topic_message WHERE shared_id = ?",
                Long.class, sharedId);
    }

    /** 只保留最近 retain 条消息。 */
    public void pruneMessages(long sharedId, int retain) {
        jdbc.update("""
                DELETE FROM topic_message
                WHERE shared_id = ?
                  AND cursor NOT IN (
                        SELECT cursor FROM (
                            SELECT cursor FROM topic_message
                            WHERE shared_id = ? ORDER BY cursor DESC LIMIT ?
                        ) kept)
                """, sharedId, sharedId, retain);
    }

    public void deleteMessages(long sharedId) {
        jdbc.update("DELETE FROM topic_message WHERE shared_id = ?", sharedId);
    }

    // ---------------- Service 调用 ----------------

    private static final RowMapper<ServiceCallRow> SVC_MAPPER = (rs, n) -> new ServiceCallRow(
            rs.getString("client_id"), rs.getString("request_id"),
            rs.getString("service_name"), rs.getString("status"),
            rs.getLong("deadline"), rs.getString("result_json"),
            rs.getLong("created_at"), (Long) rs.getObject("completed_at"));

    /**
     * 登记一次 PENDING 调用。同一 (clientId,requestId) 已存在时返回旧行（调用幂等）。
     */
    public ServiceCallRow insertServiceCallIfAbsent(String clientId, String requestId,
                                                    String service, long deadline, long now) {
        ServiceCallRow existing = getServiceCall(clientId, requestId);
        if (existing != null) {
            return existing;
        }
        jdbc.update("""
                INSERT INTO service_call
                    (client_id, request_id, service_name, status, deadline,
                     result_json, created_at, completed_at)
                VALUES (?, ?, ?, 'PENDING', ?, NULL, ?, NULL)
                """, clientId, requestId, service, deadline, now);
        return getServiceCall(clientId, requestId);
    }

    public ServiceCallRow getServiceCall(String clientId, String requestId) {
        List<ServiceCallRow> rows = jdbc.query("""
                SELECT * FROM service_call WHERE client_id = ? AND request_id = ?
                """, SVC_MAPPER, clientId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * CAS 完成调用：仅 PENDING 行可迁移到终态。超时与迟到结果竞争时只有一方成功，
     * 成功返回 true。
     */
    public boolean completeServiceCall(String clientId, String requestId,
                                       String status, String resultJson, long now) {
        int updated = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    UPDATE service_call
                    SET status = ?, result_json = ?, completed_at = ?
                    WHERE client_id = ? AND request_id = ? AND status = 'PENDING'
                    """);
            ps.setString(1, status);
            if (resultJson == null) {
                ps.setNull(2, Types.CLOB);
            } else {
                ps.setString(2, resultJson);
            }
            ps.setLong(3, now);
            ps.setString(4, clientId);
            ps.setString(5, requestId);
            return ps;
        });
        return updated > 0;
    }

    /** 已过 deadline 且仍 PENDING 的调用——超时扫描器使用。 */
    public List<ServiceCallRow> findExpiredPendingServiceCalls(long now) {
        return jdbc.query("""
                SELECT * FROM service_call WHERE status = 'PENDING' AND deadline <= ?
                """, SVC_MAPPER, now);
    }

    // ---------------- Action goal ----------------

    private static final RowMapper<ActionGoalRow> GOAL_MAPPER = (rs, n) -> new ActionGoalRow(
            rs.getString("client_id"), rs.getString("goal_id"),
            rs.getString("action_name"), rs.getString("status"),
            rs.getString("result_json"),
            rs.getLong("created_at"), (Long) rs.getObject("completed_at"));

    public ActionGoalRow insertGoalIfAbsent(String clientId, String goalId,
                                            String action, long now) {
        ActionGoalRow existing = getGoal(clientId, goalId);
        if (existing != null) {
            return existing;
        }
        jdbc.update("""
                INSERT INTO action_goal
                    (client_id, goal_id, action_name, status, result_json,
                     created_at, completed_at)
                VALUES (?, ?, ?, 'ACTIVE', NULL, ?, NULL)
                """, clientId, goalId, action, now);
        return getGoal(clientId, goalId);
    }

    public ActionGoalRow getGoal(String clientId, String goalId) {
        List<ActionGoalRow> rows = jdbc.query("""
                SELECT * FROM action_goal WHERE client_id = ? AND goal_id = ?
                """, GOAL_MAPPER, clientId, goalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * CAS 状态迁移：仅 ACTIVE goal 可进入终态（取消成功 / 成功 / 异常终止）。
     * 取消与最终结果并发时只有一方胜出，保证二者幂等。返回是否迁移成功。
     */
    public boolean casFinishGoal(String clientId, String goalId,
                                 String terminalStatus, String resultJson, long now) {
        int updated = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    UPDATE action_goal
                    SET status = ?, result_json = ?, completed_at = ?
                    WHERE client_id = ? AND goal_id = ? AND status = 'ACTIVE'
                    """);
            ps.setString(1, terminalStatus);
            if (resultJson == null) {
                ps.setNull(2, Types.CLOB);
            } else {
                ps.setString(2, resultJson);
            }
            ps.setLong(3, now);
            ps.setString(4, clientId);
            ps.setString(5, goalId);
            return ps;
        });
        return updated > 0;
    }

    /** 仅供测试/清理：清空全部状态。 */
    public void clearAll() {
        jdbc.update("DELETE FROM action_goal");
        jdbc.update("DELETE FROM service_call");
        jdbc.update("DELETE FROM topic_message");
        jdbc.update("DELETE FROM client_subscription");
        jdbc.update("DELETE FROM shared_subscription");
        jdbc.update("DELETE FROM ws_client");
    }
}
