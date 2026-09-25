package com.example.starter.epidemic.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 报告卡主表仓储。并发终态竞争通过条件 UPDATE 的受影响行数裁决。
 */
@Repository
public class CardRepository {

    private final JdbcTemplate jdbc;

    public CardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "id, card_number, patient_id, patient_name, disease_code, onset_date, "
                    + "dedup_key, current_version, status, created_at, updated_at";

    private static final RowMapper<CardPo> MAPPER = (rs, n) -> {
        CardPo po = new CardPo();
        po.id = rs.getLong("id");
        po.cardNumber = rs.getString("card_number");
        po.patientId = rs.getString("patient_id");
        po.patientName = rs.getString("patient_name");
        po.diseaseCode = rs.getString("disease_code");
        po.onsetDate = rs.getObject("onset_date", LocalDate.class);
        po.dedupKey = rs.getString("dedup_key");
        po.currentVersion = rs.getInt("current_version");
        po.status = rs.getString("status");
        po.createdAt = rs.getObject("created_at", LocalDateTime.class);
        po.updatedAt = rs.getObject("updated_at", LocalDateTime.class);
        return po;
    };

    public Optional<CardPo> findByNumber(String cardNumber) {
        List<CardPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM epidemic_card WHERE card_number = ?",
                MAPPER, cardNumber);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取（须在事务内），用于串行化归档卡特殊订正的生效过程。
     */
    public CardPo findForUpdate(String cardNumber) {
        List<CardPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM epidemic_card WHERE card_number = ? FOR UPDATE",
                MAPPER, cardNumber);
        return list.stream().findFirst().orElse(null);
    }

    /**
     * 按去重三元组查询有效卡（dedup_key 命中）。唯一索引保证至多一行。
     */
    public Optional<CardPo> findActiveDedup(String patientId, String diseaseCode, LocalDate onsetDate) {
        List<CardPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM epidemic_card WHERE dedup_key = ? AND status <> 'WITHDRAWN'",
                MAPPER, com.example.starter.epidemic.service.DedupKeys.of(patientId, diseaseCode, onsetDate));
        return list.stream().findFirst();
    }

    public void insert(CardPo po) {
        jdbc.update("INSERT INTO epidemic_card "
                        + "(card_number, patient_id, patient_name, disease_code, onset_date, dedup_key, "
                        + "current_version, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.cardNumber, po.patientId, po.patientName, po.diseaseCode, po.onsetDate,
                po.dedupKey, po.currentVersion, po.status, po.createdAt, po.updatedAt);
    }

    /**
     * 借助独立发号表生成递增卡号序号。
     */
    public long nextCardSequence() {
        jdbc.update("INSERT INTO card_number_seq (id) VALUES (DEFAULT)");
        return jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM card_number_seq", Long.class);
    }

    /**
     * 条件更新卡片（状态机 + 版本乐观锁 + 去重键迁移），仅当当前状态属于期望状态且版本号未被并发推进时生效。
     * 返回受影响行数：0 表示并发竞争失败。
     */
    public int updateStateIfCurrentStatus(String cardNumber, String newStatus,
                                          String diseaseCode, LocalDate onsetDate, String patientId,
                                          int newVersion, LocalDateTime now,
                                          int expectedCurrentVersion, String... expectedStatuses) {
        String placeholders = String.join(",", Arrays.stream(expectedStatuses).map(s -> "?").toList());
        String sql = "UPDATE epidemic_card SET status = ?, disease_code = ?, onset_date = ?, "
                + "dedup_key = ?, current_version = ?, updated_at = ? "
                + "WHERE card_number = ? AND current_version = ? AND status IN (" + placeholders + ")";
        Object[] args = new Object[expectedStatuses.length + 8];
        int i = 0;
        args[i++] = newStatus;
        args[i++] = diseaseCode;
        args[i++] = onsetDate;
        args[i++] = com.example.starter.epidemic.service.DedupKeys.of(patientId, diseaseCode, onsetDate);
        args[i++] = newVersion;
        args[i++] = now;
        args[i++] = cardNumber;
        args[i++] = expectedCurrentVersion;
        for (String s : expectedStatuses) {
            args[i++] = s;
        }
        return jdbc.update(sql, args);
    }

    /**
     * 仅状态迁移、不改版本指针（初审通过、终审归档）。
     */
    public int updateStatusOnly(String cardNumber, String newStatus, LocalDateTime now,
                                String... expectedStatuses) {
        String placeholders = String.join(",", Arrays.stream(expectedStatuses).map(s -> "?").toList());
        String sql = "UPDATE epidemic_card SET status = ?, updated_at = ? "
                + "WHERE card_number = ? AND status IN (" + placeholders + ")";
        Object[] args = new Object[expectedStatuses.length + 3];
        int i = 0;
        args[i++] = newStatus;
        args[i++] = now;
        args[i++] = cardNumber;
        for (String s : expectedStatuses) {
            args[i++] = s;
        }
        return jdbc.update(sql, args);
    }

    /**
     * 撤回：仅待审核卡可撤回；去重键改写为卡片编号墓碑值，释放患者+病种+发病日期槽位。
     */
    public int withdraw(String cardNumber, LocalDateTime now) {
        return jdbc.update(
                "UPDATE epidemic_card SET status = 'WITHDRAWN', dedup_key = CONCAT('TOMB:', card_number), "
                        + "updated_at = ? WHERE card_number = ? AND status = 'PENDING_REVIEW'",
                now, cardNumber);
    }

    /**
     * 归档卡经特殊订正追加版本：状态保持 ARCHIVED，推进版本、最新病种信息与去重键。
     */
    public int bumpVersionOnArchived(String cardNumber, String diseaseCode, LocalDate onsetDate,
                                     String patientId, int newVersion, LocalDateTime now) {
        return jdbc.update(
                "UPDATE epidemic_card SET disease_code = ?, onset_date = ?, dedup_key = ?, "
                        + "current_version = ?, updated_at = ? "
                        + "WHERE card_number = ? AND status = 'ARCHIVED' AND current_version = ?",
                diseaseCode, onsetDate,
                com.example.starter.epidemic.service.DedupKeys.of(patientId, diseaseCode, onsetDate),
                newVersion, now, cardNumber, newVersion - 1);
    }

    public List<CardPo> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM epidemic_card ORDER BY id", MAPPER);
    }
}
