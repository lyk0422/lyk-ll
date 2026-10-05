package com.example.starter.epidemic.repository;

import com.example.starter.epidemic.domain.AuditAction;
import com.example.starter.epidemic.domain.AuditTrailPo;
import com.example.starter.epidemic.domain.CardPo;
import com.example.starter.epidemic.domain.CardStatus;
import com.example.starter.epidemic.domain.CardVersionPo;
import com.example.starter.epidemic.domain.DiseaseCategory;
import com.example.starter.epidemic.domain.DiseasePo;
import com.example.starter.epidemic.domain.ExposureSitePo;
import com.example.starter.epidemic.domain.IdempotencyPo;
import com.example.starter.epidemic.domain.InstitutionPo;
import com.example.starter.epidemic.domain.SpecialCorrectionPo;
import com.example.starter.epidemic.domain.SpecialStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 报告卡相关数据访问：卡片、版本、轨迹、特殊订正、幂等与目录。
 *
 * <p>所有写操作均在服务层事务内执行；{@code FOR UPDATE} 与带状态条件的 UPDATE 共同保证
 * “初审通过/撤回”等并发动作只有一个成为终态。</p>
 */
@Repository
public class CardRepository {

    private final JdbcTemplate jdbc;

    public CardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 目录 ----------------

    private static final RowMapper<DiseasePo> DISEASE_MAPPER = (rs, n) ->
            new DiseasePo(rs.getString("code"), rs.getString("name"),
                    DiseaseCategory.valueOf(rs.getString("category")));

    private static final RowMapper<InstitutionPo> INSTITUTION_MAPPER = (rs, n) ->
            new InstitutionPo(rs.getString("code"), rs.getString("name"));

    public Optional<DiseasePo> findDisease(String code) {
        return jdbc.query("SELECT code, name, category FROM disease WHERE code = ?",
                DISEASE_MAPPER, code).stream().findFirst();
    }

    public Optional<InstitutionPo> findInstitution(String code) {
        return jdbc.query("SELECT code, name FROM institution WHERE code = ?",
                INSTITUTION_MAPPER, code).stream().findFirst();
    }

    // ---------------- 暴露场所库 ----------------

    private static final RowMapper<ExposureSitePo> SITE_MAPPER = (rs, n) ->
            new ExposureSitePo(rs.getString("code"), rs.getString("name"),
                    rs.getString("address_keyword"));

    public Optional<ExposureSitePo> findExposureSite(String code) {
        return jdbc.query("SELECT code, name, address_keyword FROM exposure_site WHERE code = ?",
                SITE_MAPPER, code).stream().findFirst();
    }

    /** 全部暴露场所（用于按地址关键词自动匹配，数据量小，全量读入内存匹配）。 */
    public List<ExposureSitePo> findAllExposureSites() {
        return jdbc.query("SELECT code, name, address_keyword FROM exposure_site ORDER BY code",
                SITE_MAPPER);
    }

    // ---------------- 卡片主表 ----------------

    private static final RowMapper<CardPo> CARD_MAPPER = (rs, n) -> {
        CardPo po = new CardPo();
        po.setCardNo(rs.getString("card_no"));
        po.setPatientId(rs.getString("patient_id"));
        po.setPatientName(rs.getString("patient_name"));
        po.setDiseaseCode(rs.getString("disease_code"));
        po.setOnsetDate(rs.getDate("onset_date").toLocalDate());
        po.setInstitutionCode(rs.getString("institution_code"));
        po.setCurrentVersion(rs.getInt("current_version"));
        po.setStatus(CardStatus.valueOf(rs.getString("status")));
        po.setDedupKey(rs.getString("dedup_key"));
        po.setExposureSiteCode(rs.getString("exposure_site_code"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        po.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return po;
    };

    public void insertCard(CardPo card) {
        jdbc.update("""
                INSERT INTO card (card_no, patient_id, patient_name, disease_code, onset_date,
                    institution_code, current_version, status, dedup_key, exposure_site_code,
                    created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                card.getCardNo(), card.getPatientId(), card.getPatientName(), card.getDiseaseCode(),
                card.getOnsetDate(), card.getInstitutionCode(), card.getCurrentVersion(),
                card.getStatus().name(), card.getDedupKey(), card.getExposureSiteCode(),
                Timestamp.from(card.getCreatedAt()), Timestamp.from(card.getUpdatedAt()));
    }

    public Optional<CardPo> findCard(String cardNo) {
        return jdbc.query(CARD_SELECT + " WHERE card_no = ?", CARD_MAPPER, cardNo)
                .stream().findFirst();
    }

    /** 按去重指纹查找有效卡。 */
    public Optional<CardPo> findCardByDedupKey(String dedupKey) {
        return jdbc.query(CARD_SELECT + " WHERE dedup_key = ?", CARD_MAPPER, dedupKey)
                .stream().findFirst();
    }

    /** 主键行级加锁（事务内调用）。 */
    public Optional<CardPo> findCardForUpdate(String cardNo) {
        return jdbc.query(CARD_SELECT + " WHERE card_no = ? FOR UPDATE", CARD_MAPPER, cardNo)
                .stream().findFirst();
    }

    private static final String CARD_SELECT = """
            SELECT card_no, patient_id, patient_name, disease_code, onset_date,
                   institution_code, current_version, status, dedup_key, exposure_site_code,
                   created_at, updated_at
            FROM card
            """;

    /**
     * 条件状态流转：仅当当前状态为 expectedStatus 时更新，返回影响行数（0 表示并发竞态失败）。
     * 可同时更新最新版本、病种与去重指纹（参数为 null 时保持原值）。
     */
    public int compareAndUpdateCard(String cardNo, CardStatus expectedStatus, CardStatus newStatus,
                                    Integer newVersion, String newDiseaseCode, String newDedupKey,
                                    Instant updatedAt) {
        StringBuilder sql = new StringBuilder("UPDATE card SET status = ?, updated_at = ?");
        if (newVersion != null) {
            sql.append(", current_version = ?");
        }
        if (newDiseaseCode != null) {
            sql.append(", disease_code = ?");
        }
        if (newDedupKey != null) {
            sql.append(", dedup_key = ?");
        }
        sql.append(" WHERE card_no = ? AND status = ?");

        List<Object> args = new java.util.ArrayList<>();
        args.add(newStatus.name());
        args.add(Timestamp.from(updatedAt));
        if (newVersion != null) {
            args.add(newVersion);
        }
        if (newDiseaseCode != null) {
            args.add(newDiseaseCode);
        }
        if (newDedupKey != null) {
            args.add(newDedupKey);
        }
        args.add(cardNo);
        args.add(expectedStatus.name());
        return jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * 审核通过前撤回：仅当状态为 PENDING_REVIEW 时置为 WITHDRAWN 并释放去重指纹，
     * 返回影响行数（0 表示并发竞态失败，卡片已被审核）。
     */
    public int withdrawCard(String cardNo, Instant updatedAt) {
        return jdbc.update("""
                UPDATE card SET status = 'WITHDRAWN', dedup_key = NULL, updated_at = ?
                WHERE card_no = ? AND status = 'PENDING_REVIEW'
                """, Timestamp.from(updatedAt), cardNo);
    }

    /**
     * 特殊订正生效后更新当前版本信息（卡片保持 ARCHIVED 只读终态）。
     */
    public int updateCardVersionInfo(String cardNo, int newVersion, String newDiseaseCode,
                                     String newDedupKey, Instant updatedAt) {
        return jdbc.update("""
                UPDATE card SET current_version = ?, disease_code = ?, dedup_key = ?, updated_at = ?
                WHERE card_no = ? AND status = 'ARCHIVED'
                """, newVersion, newDiseaseCode, newDedupKey, Timestamp.from(updatedAt), cardNo);
    }

    // ---------------- 版本 ----------------

    private static final RowMapper<CardVersionPo> VERSION_MAPPER = (rs, n) -> new CardVersionPo(
            rs.getLong("id"),
            rs.getString("card_no"),
            rs.getInt("version_no"),
            rs.getString("change_type"),
            rs.getString("disease_code"),
            rs.getString("disease_name"),
            rs.getString("category"),
            rs.getString("case_type"),
            rs.getString("patient_id"),
            rs.getString("patient_name"),
            (Integer) rs.getObject("age"),
            rs.getString("gender"),
            rs.getDate("onset_date").toLocalDate(),
            rs.getTimestamp("diagnosis_time").toInstant(),
            rs.getString("institution_code"),
            rs.getString("reporter"),
            rs.getString("address"),
            rs.getString("phone"),
            rs.getBoolean("late"),
            rs.getString("late_reason"),
            rs.getString("request_id"),
            rs.getTimestamp("submitted_at").toInstant());

    public void insertVersion(CardVersionPo v) {
        jdbc.update("""
                INSERT INTO card_version (card_no, version_no, change_type, disease_code, disease_name,
                    category, case_type, patient_id, patient_name, age, gender, onset_date,
                    diagnosis_time, institution_code, reporter, address, phone, late, late_reason,
                    request_id, submitted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                v.cardNo(), v.versionNo(), v.changeType(), v.diseaseCode(), v.diseaseName(),
                v.category(), v.caseType(), v.patientId(), v.patientName(),
                v.age(), v.gender(), v.onsetDate(),
                Timestamp.from(v.diagnosisTime()), v.institutionCode(), v.reporter(),
                v.address(), v.phone(), v.late(), v.lateReason(),
                v.requestId(), Timestamp.from(v.submittedAt()));
    }

    public Optional<CardVersionPo> findVersion(String cardNo, int versionNo) {
        return jdbc.query(VERSION_SELECT + " WHERE card_no = ? AND version_no = ?",
                VERSION_MAPPER, cardNo, versionNo).stream().findFirst();
    }

    public Optional<CardVersionPo> findLatestVersion(String cardNo) {
        return jdbc.query(VERSION_SELECT + " WHERE card_no = ? ORDER BY version_no DESC LIMIT 1",
                VERSION_MAPPER, cardNo).stream().findFirst();
    }

    /** 事务内追加版本前对最新版本行加锁，串行化同卡版本号分配。 */
    public Optional<CardVersionPo> findLatestVersionForUpdate(String cardNo) {
        return jdbc.query(
                VERSION_SELECT + " WHERE card_no = ? ORDER BY version_no DESC LIMIT 1 FOR UPDATE",
                VERSION_MAPPER, cardNo).stream().findFirst();
    }

    public List<CardVersionPo> findVersions(String cardNo) {
        return jdbc.query(VERSION_SELECT + " WHERE card_no = ? ORDER BY version_no",
                VERSION_MAPPER, cardNo);
    }

    private static final String VERSION_SELECT = """
            SELECT id, card_no, version_no, change_type, disease_code, disease_name, category,
                   case_type, patient_id, patient_name, age, gender, onset_date, diagnosis_time,
                   institution_code, reporter, address, phone, late, late_reason, request_id, submitted_at
            FROM card_version
            """;

    // ---------------- 轨迹 ----------------

    private static final RowMapper<AuditTrailPo> TRAIL_MAPPER = (rs, n) -> new AuditTrailPo(
            rs.getLong("id"),
            rs.getString("card_no"),
            rs.getInt("version_no"),
            AuditAction.valueOf(rs.getString("action")),
            rs.getString("operator"),
            rs.getString("detail"),
            rs.getTimestamp("created_at").toInstant());

    public void insertTrail(String cardNo, int versionNo, AuditAction action, String operator,
                            String detail, Instant createdAt) {
        jdbc.update("""
                INSERT INTO audit_trail (card_no, version_no, action, operator, detail, created_at)
                VALUES (?,?,?,?,?,?)
                """,
                cardNo, versionNo, action.name(), operator, detail, Timestamp.from(createdAt));
    }

    public List<AuditTrailPo> findTrails(String cardNo) {
        return jdbc.query("""
                SELECT id, card_no, version_no, action, operator, detail, created_at
                FROM audit_trail WHERE card_no = ? ORDER BY id
                """, TRAIL_MAPPER, cardNo);
    }

    // ---------------- 特殊订正 ----------------

    private static final RowMapper<SpecialCorrectionPo> SPECIAL_MAPPER = (rs, n) -> {
        SpecialCorrectionPo po = new SpecialCorrectionPo();
        po.setId(rs.getLong("id"));
        po.setCardNo(rs.getString("card_no"));
        po.setRequestId(rs.getString("request_id"));
        po.setDiseaseCode(rs.getString("disease_code"));
        po.setCaseType(rs.getString("case_type"));
        Timestamp diagnosis = rs.getTimestamp("diagnosis_time");
        po.setDiagnosisTime(diagnosis == null ? null : diagnosis.toInstant());
        po.setReason(rs.getString("reason"));
        po.setInitiator(rs.getString("initiator"));
        po.setFirstReviewer(rs.getString("first_reviewer"));
        Timestamp firstAt = rs.getTimestamp("first_at");
        po.setFirstAt(firstAt == null ? null : firstAt.toInstant());
        po.setSecondReviewer(rs.getString("second_reviewer"));
        Timestamp secondAt = rs.getTimestamp("second_at");
        po.setSecondAt(secondAt == null ? null : secondAt.toInstant());
        po.setStatus(SpecialStatus.valueOf(rs.getString("status")));
        po.setEffectiveVersion((Integer) rs.getObject("effective_version"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return po;
    };

    private static final String SPECIAL_SELECT = """
            SELECT id, card_no, request_id, disease_code, case_type, diagnosis_time, reason,
                   initiator, first_reviewer, first_at, second_reviewer, second_at, status,
                   effective_version, created_at
            FROM special_correction
            """;

    public long insertSpecial(SpecialCorrectionPo po) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO special_correction (card_no, request_id, disease_code, case_type,
                        diagnosis_time, reason, initiator, status, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, po.getCardNo());
            ps.setString(2, po.getRequestId());
            ps.setString(3, po.getDiseaseCode());
            ps.setString(4, po.getCaseType());
            if (po.getDiagnosisTime() == null) {
                ps.setNull(5, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(5, Timestamp.from(po.getDiagnosisTime()));
            }
            ps.setString(6, po.getReason());
            ps.setString(7, po.getInitiator());
            ps.setString(8, po.getStatus().name());
            ps.setTimestamp(9, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("特殊订正流程写回失败");
        }
        return key.longValue();
    }

    public Optional<SpecialCorrectionPo> findSpecialForUpdate(long id) {
        return jdbc.query(SPECIAL_SELECT + " WHERE id = ? FOR UPDATE", SPECIAL_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<SpecialCorrectionPo> findSpecialByRequestId(String requestId) {
        return jdbc.query(SPECIAL_SELECT + " WHERE request_id = ?", SPECIAL_MAPPER, requestId)
                .stream().findFirst();
    }

    /** 第一名审核员确认（仅当尚无第一确认人时生效），返回影响行数。 */
    public int updateSpecialFirst(long id, String reviewer, Instant at) {
        return jdbc.update("""
                UPDATE special_correction
                SET first_reviewer = ?, first_at = ?
                WHERE id = ? AND first_reviewer IS NULL
                """, reviewer, Timestamp.from(at), id);
    }

    /** 第二名（不同）审核员确认并置生效，返回影响行数。 */
    public int updateSpecialEffective(long id, String reviewer, Instant at, int effectiveVersion) {
        return jdbc.update("""
                UPDATE special_correction
                SET second_reviewer = ?, second_at = ?, status = 'EFFECTIVE',
                    effective_version = ?
                WHERE id = ? AND status = 'WAITING_SECOND'
                """, reviewer, Timestamp.from(at), effectiveVersion, id);
    }

    // ---------------- 幂等 ----------------

    private static final RowMapper<IdempotencyPo> IDEMPOTENCY_MAPPER = (rs, n) -> new IdempotencyPo(
            rs.getString("request_id"),
            rs.getString("scope"),
            rs.getString("fingerprint"),
            rs.getString("card_no"),
            (Integer) rs.getObject("version_no"),
            rs.getString("response_json"),
            rs.getTimestamp("created_at").toInstant());

    public void insertIdempotency(IdempotencyPo po) {
        jdbc.update("""
                INSERT INTO idempotency_record
                    (request_id, scope, fingerprint, card_no, version_no, response_json, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                po.requestId(), po.scope(), po.fingerprint(), po.cardNo(),
                po.versionNo(), po.responseJson(), Timestamp.from(po.createdAt()));
    }

    public Optional<IdempotencyPo> findIdempotency(String requestId, String scope) {
        return jdbc.query("""
                SELECT request_id, scope, fingerprint, card_no, version_no, response_json, created_at
                FROM idempotency_record WHERE request_id = ? AND scope = ?
                """, IDEMPOTENCY_MAPPER, requestId, scope).stream().findFirst();
    }
}
