package com.example.starter.rosbridge.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * 网关状态持久化访问层（H2 内存库，MODE=MySQL）。
 * 负责连接配置、上游订阅引用计数、客户端游标、消息留痕、Service 调用与 Action goal 状态。
 *
 * <p>所有涉及“读状态—判定—改引用计数”的操作均在同一事务内完成并对关键行加行锁，
 * 保证并发订阅/取消/断连过期下引用计数不丢更新。
 */
@Repository
public class GatewayRepository {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CANCELING = "CANCELING";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public GatewayRepository(JdbcTemplate jdbcTemplate,
                             PlatformTransactionManager transactionManager) {
        this.jdbc = jdbcTemplate;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 客户端行。 */
    public record ClientRow(String clientId, int rateLimitPerSecond, int sendQueueCapacity,
                            long lastConnectAt, Long lastDisconnectAt) {
    }

    /** 上游订阅行。 */
    public record UpstreamRow(long id, String topic, String msgType, String qosHash,
                              String qosJson, int refCount) {
    }

    /** 客户端订阅行（含上游信息）。 */
    public record ClientSubRow(long id, String clientId, long upstreamId, boolean active,
                               long ackCursor, String topic, String msgType, String qosHash,
                               String qosJson, int upstreamRefCount) {
    }

    /** 历史 Topic 消息行。 */
    public record MessageRow(long cursor, long upstreamId, String topic, String msgType,
                             String payload, long createdAt) {
    }

    /** acquireSubscription 的结果。 */
    public record AcquireResult(long upstreamId, boolean upstreamCreated,
                                boolean subscriptionActivated, long startCursor) {
    }

    /** releaseSubscription 的结果。 */
    public record ReleaseResult(long upstreamId, int refCountAfter, boolean deactivated,
                                boolean released) {
    }

    // ---- 客户端 ----

    public void upsertClientOnConnect(String clientId, int rateLimit, int queueCapacity, long now) {
        tx.executeWithoutResult(status -> {
            Long id = jdbc.query("SELECT 1 FROM rb_client WHERE client_id = ?",
                    rs -> rs.next() ? 1L : null, clientId);
            if (id == null) {
                jdbc.update("INSERT INTO rb_client (client_id, rate_limit_per_second, "
                                + "send_queue_capacity, last_connect_at, last_disconnect_at) "
                                + "VALUES (?,?,?,?,NULL)",
                        clientId, rateLimit, queueCapacity, now);
            } else {
                jdbc.update("UPDATE rb_client SET rate_limit_per_second = ?, "
                                + "send_queue_capacity = ?, last_connect_at = ?, "
                                + "last_disconnect_at = NULL WHERE client_id = ?",
                        rateLimit, queueCapacity, now, clientId);
            }
        });
    }

    public void markClientDisconnected(String clientId, long now) {
        jdbc.update("UPDATE rb_client SET last_disconnect_at = ? WHERE client_id = ?",
                now, clientId);
    }

    // ---- 订阅引用计数 ----

    /**
     * 在一个事务内：确保上游订阅行存在、激活客户端订阅并维护引用计数。
     * 重复订阅（客户端已处于 active）返回 subscriptionActivated=false，引用计数不重复增加。
     */
    public AcquireResult acquireSubscription(String clientId, String topic, String msgType,
                                             String qosHash, String qosJson, long now) {
        return withLockRetry(() -> tx.execute(status -> {
            UpstreamRow upstream = jdbc.query(
                    "SELECT * FROM rb_upstream_subscription "
                            + "WHERE topic = ? AND msg_type = ? AND qos_hash = ? FOR UPDATE",
                    upstreamMapper(), topic, msgType, qosHash).stream().findFirst().orElse(null);
            boolean created = false;
            long upstreamId;
            int refCount;
            if (upstream == null) {
                jdbc.update("INSERT INTO rb_upstream_subscription "
                                + "(topic, msg_type, qos_hash, qos_json, ref_count, created_at) "
                                + "VALUES (?,?,?,?," + 0 + ",?)",
                        topic, msgType, qosHash, qosJson, now);
                upstream = jdbc.queryForObject(
                        "SELECT * FROM rb_upstream_subscription "
                                + "WHERE topic = ? AND msg_type = ? AND qos_hash = ?",
                        upstreamMapper(), topic, msgType, qosHash);
                upstreamId = upstream.id();
                refCount = 0;
                created = true;
            } else {
                upstreamId = upstream.id();
                refCount = upstream.refCount();
            }

            // 新订阅从“订阅时刻该上游最新游标”开始，不回放订阅之前的历史消息。
            long startCursor = latestCursor(upstreamId);

            ClientSubRow sub = jdbc.query(
                    "SELECT * FROM rb_client_subscription "
                            + "WHERE client_id = ? AND upstream_id = ? FOR UPDATE",
                    clientSubMapper(), clientId, upstreamId).stream().findFirst().orElse(null);
            boolean activated;
            if (sub == null) {
                jdbc.update("INSERT INTO rb_client_subscription "
                                + "(client_id, upstream_id, active, ack_cursor, created_at) "
                                + "VALUES (?,?," + 1 + ",?,?)",
                        clientId, upstreamId, startCursor, now);
                refCount++;
                activated = true;
            } else if (!sub.active()) {
                // 宽限期结束后重新订阅视为全新订阅，水位对齐当前最新游标。
                jdbc.update("UPDATE rb_client_subscription SET active = " + 1
                        + ", ack_cursor = GREATEST(ack_cursor, ?) WHERE id = ?",
                        startCursor, sub.id());
                refCount++;
                activated = true;
            } else {
                activated = false;
            }
            if (activated) {
                jdbc.update("UPDATE rb_upstream_subscription SET ref_count = ? WHERE id = ?",
                        refCount, upstreamId);
            }

            return new AcquireResult(upstreamId, created, activated, startCursor);
        }));
    }

    /**
     * 显式取消单个客户端订阅：active 置 0、引用计数减 1。
     * 重复取消（不存在或已 inactive）返回 deactivated=false，refCount 不再递减。
     */
    public ReleaseResult releaseSubscription(String clientId, long upstreamId) {
        return withLockRetry(() -> tx.execute(status -> {
            ClientSubRow sub = jdbc.query(
                    "SELECT * FROM rb_client_subscription "
                            + "WHERE client_id = ? AND upstream_id = ? FOR UPDATE",
                    clientSubMapper(), clientId, upstreamId).stream().findFirst().orElse(null);
            Integer refCountObj = jdbc.queryForObject(
                    "SELECT ref_count FROM rb_upstream_subscription WHERE id = ? FOR UPDATE",
                    Integer.class, upstreamId);
            int refCount = refCountObj == null ? 0 : refCountObj;
            if (sub == null || !sub.active()) {
                return new ReleaseResult(upstreamId, refCount, false, refCount == 0);
            }
            int after = Math.max(0, refCount - 1);
            jdbc.update("UPDATE rb_client_subscription SET active = " + 0 + " WHERE id = ?",
                    sub.id());
            jdbc.update("UPDATE rb_upstream_subscription SET ref_count = ? WHERE id = ?",
                    after, upstreamId);
            return new ReleaseResult(upstreamId, after, true, after == 0);
        }));
    }

    /** 断连宽限期过期处理结果。 */
    public record ExpireResult(List<Long> touchedUpstreamIds, List<Long> releasedUpstreamIds) {
    }

    /**
     * 断连宽限期结束：停用该客户端全部仍活跃的订阅并递减引用计数。
     * 返回受影响的全部上游 id（用于摘除路由成员）以及引用计数归零、可释放资源的上游 id。
     */
    public ExpireResult expireClientSubscriptions(String clientId) {
        return withLockRetry(() -> tx.execute(status -> {
            // JOIN 上游表以在事务内锁定其当前 ref_count，避免与并发订阅/取消交错。
            List<ClientSubRow> active = jdbc.query(
                    "SELECT cs.*, u.topic, u.msg_type, u.qos_hash, u.qos_json, u.ref_count "
                            + "FROM rb_client_subscription cs "
                            + "JOIN rb_upstream_subscription u ON u.id = cs.upstream_id "
                            + "WHERE cs.client_id = ? AND cs.active = " + 1 + " FOR UPDATE",
                    clientSubMapper(), clientId);
            List<Long> touched = new ArrayList<>();
            List<Long> released = new ArrayList<>();
            for (ClientSubRow sub : active) {
                jdbc.update("UPDATE rb_client_subscription SET active = " + 0
                        + " WHERE id = ?", sub.id());
                int after = Math.max(0, sub.upstreamRefCount() - 1);
                jdbc.update("UPDATE rb_upstream_subscription SET ref_count = ? WHERE id = ?",
                        after, sub.upstreamId());
                touched.add(sub.upstreamId());
                if (after == 0) {
                    released.add(sub.upstreamId());
                }
            }
            return new ExpireResult(touched, released);
        }));
    }

    /**
     * 执行一段需要行锁/唯一键的事务；遇到锁冲突或唯一键竞争（上游行尚不存在时
     * FOR UPDATE 无法锁定“间隙”）时整事务退避后重试，保证并发下引用计数不丢更新。
     */
    private static <T> T withLockRetry(Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                return action.get();
            } catch (DuplicateKeyException
                     | TransientDataAccessException e) {
                last = e;
                LockSupport.parkNanos((long) (1_000_000L * (attempt % 10 + 1)));
            }
        }
        throw last;
    }

    public List<ClientSubRow> findActiveSubscriptions(String clientId) {
        return jdbc.query(
                "SELECT cs.*, u.topic, u.msg_type, u.qos_hash, u.qos_json, u.ref_count "
                        + "FROM rb_client_subscription cs "
                        + "JOIN rb_upstream_subscription u ON u.id = cs.upstream_id "
                        + "WHERE cs.client_id = ? AND cs.active = " + 1,
                clientSubMapper(), clientId);
    }

    public UpstreamRow findUpstream(long upstreamId) {
        return jdbc.queryForObject("SELECT * FROM rb_upstream_subscription WHERE id = ?",
                upstreamMapper(), upstreamId);
    }

    // ---- 消息与游标 ----

    /** 分配全局单调游标并保存消息，同时裁剪超出保留量的旧消息。 */
    public long appendMessage(long upstreamId, String topic, String msgType,
                              String payload, long now, int retention) {
        return tx.execute(status -> {
            Long next = jdbc.queryForObject(
                    "SELECT next_value FROM rb_cursor_sequence WHERE id = 0 FOR UPDATE",
                    Long.class);
            long cursor = next == null ? 1L : next;
            jdbc.update("INSERT INTO rb_topic_message "
                            + "(cursor_value, upstream_id, topic, msg_type, payload, created_at) "
                            + "VALUES (?,?,?,?,?,?)",
                    cursor, upstreamId, topic, msgType, payload, now);
            jdbc.update("UPDATE rb_cursor_sequence SET next_value = ? WHERE id = 0",
                    cursor + 1);
            if (retention > 0) {
                // 保留最新 retention 条：取倒数第 retention 条游标，删除其之前的旧消息。
                Long retainFrom = jdbc.query(
                        "SELECT cursor_value FROM rb_topic_message WHERE upstream_id = ? "
                                + "ORDER BY cursor_value DESC LIMIT 1 OFFSET ?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        upstreamId, retention);
                if (retainFrom != null) {
                    jdbc.update("DELETE FROM rb_topic_message WHERE upstream_id = ? "
                            + "AND cursor_value < ?", upstreamId, retainFrom);
                }
            }
            return cursor;
        });
    }

    /** 严格按游标升序返回大于 ackCursor 的可用消息，最多 limit 条。 */
    public List<MessageRow> findMessagesAfter(long upstreamId, long ackCursor, int limit) {
        return jdbc.query("SELECT * FROM rb_topic_message WHERE upstream_id = ? "
                        + "AND cursor_value > ? ORDER BY cursor_value ASC LIMIT ?",
                messageMapper(), upstreamId, ackCursor, limit);
    }

    public long latestCursor(long upstreamId) {
        Long v = jdbc.queryForObject(
                "SELECT MAX(cursor_value) FROM rb_topic_message WHERE upstream_id = ?",
                Long.class, upstreamId);
        return v == null ? 0L : v;
    }

    /** 将客户端所有活跃订阅的确认游标推进到至少 cursor（只增不减）。 */
    public void advanceAck(String clientId, long cursor) {
        jdbc.update("UPDATE rb_client_subscription SET ack_cursor = GREATEST(ack_cursor, ?) "
                        + "WHERE client_id = ? AND active = " + 1,
                cursor, clientId);
    }

    // ---- Service 调用 ----

    public record ServiceCallRow(String requestId, String clientId, String serviceName,
                                 String status, String errorCode, String errorMessage,
                                 String resultText, long createdAt, Long completedAt) {
    }

    /** insertServiceCallIfAbsent 的结果：inserted=false 表示 requestId 已存在（重试）。 */
    public record ServiceCallInsert(ServiceCallRow row, boolean inserted) {
    }

    public ServiceCallInsert insertServiceCallIfAbsent(String requestId, String clientId,
                                                       String service, long now) {
        return tx.execute(status -> {
            ServiceCallRow existing = jdbc.query(
                    "SELECT * FROM rb_service_call WHERE request_id = ? FOR UPDATE",
                    serviceCallMapper(), requestId).stream().findFirst().orElse(null);
            if (existing != null) {
                return new ServiceCallInsert(existing, false);
            }
            jdbc.update("INSERT INTO rb_service_call "
                            + "(request_id, client_id, service_name, status, created_at) "
                            + "VALUES (?,?,?," + "'" + STATUS_PENDING + "'" + ",?)",
                    requestId, clientId, service, now);
            ServiceCallRow row = jdbc.queryForObject(
                    "SELECT * FROM rb_service_call WHERE request_id = ?",
                    serviceCallMapper(), requestId);
            return new ServiceCallInsert(row, true);
        });
    }

    public ServiceCallRow findServiceCall(String requestId) {
        return jdbc.query("SELECT * FROM rb_service_call WHERE request_id = ?",
                serviceCallMapper(), requestId).stream().findFirst().orElse(null);
    }

    /** 仅当状态仍为 PENDING 时条件更新为终态；更新成功返回 true（赢得完成权）。 */
    public boolean completeServiceCall(String requestId, String status, String errorCode,
                                       String errorMessage, String resultText, long now) {
        int affected = jdbc.update("UPDATE rb_service_call SET status = ?, error_code = ?, "
                        + "error_message = ?, result_text = ?, completed_at = ? "
                        + "WHERE request_id = ? AND status = '" + STATUS_PENDING + "'",
                status, errorCode, truncate(errorMessage), resultText, now, requestId);
        return affected == 1;
    }

    // ---- Action goal ----

    public record ActionGoalRow(String goalId, String clientId, String actionName,
                                String status, String resultText, long createdAt,
                                Long completedAt) {
    }

    public record GoalInsert(ActionGoalRow row, boolean inserted) {
    }

    public GoalInsert insertGoalIfAbsent(String goalId, String clientId, String action, long now) {
        return tx.execute(status -> {
            ActionGoalRow existing = jdbc.query(
                    "SELECT * FROM rb_action_goal WHERE goal_id = ? FOR UPDATE",
                    actionGoalMapper(), goalId).stream().findFirst().orElse(null);
            if (existing != null) {
                return new GoalInsert(existing, false);
            }
            jdbc.update("INSERT INTO rb_action_goal "
                            + "(goal_id, client_id, action_name, status, created_at) "
                            + "VALUES (?,?,?,'" + STATUS_PENDING + "',?)",
                    goalId, clientId, action, now);
            ActionGoalRow row = jdbc.queryForObject(
                    "SELECT * FROM rb_action_goal WHERE goal_id = ?",
                    actionGoalMapper(), goalId);
            return new GoalInsert(row, true);
        });
    }

    public ActionGoalRow findGoal(String goalId) {
        return jdbc.query("SELECT * FROM rb_action_goal WHERE goal_id = ?",
                actionGoalMapper(), goalId).stream().findFirst().orElse(null);
    }

    /** PENDING -> CANCELING；重复取消或已终态返回 false。 */
    public boolean markGoalCanceling(String goalId, long now) {
        int affected = jdbc.update("UPDATE rb_action_goal SET status = '" + STATUS_CANCELING
                + "' WHERE goal_id = ? AND status = '" + STATUS_PENDING + "'", goalId);
        return affected == 1;
    }

    /** PENDING/CANCELING -> 终态（OK/CANCELED）；仅首个最终结果返回 true。 */
    public boolean completeGoal(String goalId, String finalStatus, String resultText, long now) {
        int affected = jdbc.update("UPDATE rb_action_goal SET status = ?, result_text = ?, "
                        + "completed_at = ? WHERE goal_id = ? AND status IN ('"
                        + STATUS_PENDING + "','" + STATUS_CANCELING + "')",
                finalStatus, resultText, now, goalId);
        return affected == 1;
    }

    // ---- RowMapper ----

    private RowMapper<UpstreamRow> upstreamMapper() {
        return (rs, rowNum) -> new UpstreamRow(
                rs.getLong("id"), rs.getString("topic"), rs.getString("msg_type"),
                rs.getString("qos_hash"), rs.getString("qos_json"), rs.getInt("ref_count"));
    }

    private RowMapper<ClientSubRow> clientSubMapper() {
        return (rs, rowNum) -> new ClientSubRow(
                rs.getLong("id"), rs.getString("client_id"), rs.getLong("upstream_id"),
                rs.getInt("active") == 1, rs.getLong("ack_cursor"),
                tryString(rs, "topic"), tryString(rs, "msg_type"),
                tryString(rs, "qos_hash"), tryString(rs, "qos_json"),
                tryInt(rs, "ref_count"));
    }

    private RowMapper<MessageRow> messageMapper() {
        return (rs, rowNum) -> new MessageRow(
                rs.getLong("cursor_value"), rs.getLong("upstream_id"),
                rs.getString("topic"), rs.getString("msg_type"),
                rs.getString("payload"), rs.getLong("created_at"));
    }

    private RowMapper<ServiceCallRow> serviceCallMapper() {
        return (rs, rowNum) -> new ServiceCallRow(
                rs.getString("request_id"), rs.getString("client_id"),
                rs.getString("service_name"), rs.getString("status"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getString("result_text"), rs.getLong("created_at"),
                nullableLong(rs, "completed_at"));
    }

    private RowMapper<ActionGoalRow> actionGoalMapper() {
        return (rs, rowNum) -> new ActionGoalRow(
                rs.getString("goal_id"), rs.getString("client_id"),
                rs.getString("action_name"), rs.getString("status"),
                rs.getString("result_text"), rs.getLong("created_at"),
                nullableLong(rs, "completed_at"));
    }

    private static String tryString(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private static int tryInt(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getInt(column);
        } catch (SQLException e) {
            return 0;
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
