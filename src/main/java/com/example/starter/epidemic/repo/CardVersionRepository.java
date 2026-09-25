package com.example.starter.epidemic.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 卡片版本链仓储。历史版本只追加、不修改。
 */
@Repository
public class CardVersionRepository {

    private final JdbcTemplate jdbc;

    public CardVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "id, card_number, version_no, disease_code, diagnosis_type, onset_date, "
                    + "diagnosis_time, submitted_at, deadline, timely, late_reason, "
                    + "facility_code, reporter, change_kind, disease_changed, notes, created_at";

    private static final RowMapper<CardVersionPo> MAPPER = (rs, n) -> {
        CardVersionPo po = new CardVersionPo();
        po.id = rs.getLong("id");
        po.cardNumber = rs.getString("card_number");
        po.versionNo = rs.getInt("version_no");
        po.diseaseCode = rs.getString("disease_code");
        po.diagnosisType = rs.getString("diagnosis_type");
        po.onsetDate = rs.getObject("onset_date", java.time.LocalDate.class);
        po.diagnosisTime = rs.getObject("diagnosis_time", java.time.LocalDateTime.class);
        po.submittedAt = rs.getObject("submitted_at", java.time.LocalDateTime.class);
        po.deadline = rs.getObject("deadline", java.time.LocalDateTime.class);
        po.timely = rs.getInt("timely") == 1;
        po.lateReason = rs.getString("late_reason");
        po.facilityCode = rs.getString("facility_code");
        po.reporter = rs.getString("reporter");
        po.changeKind = rs.getString("change_kind");
        po.diseaseChanged = rs.getInt("disease_changed") == 1;
        po.notes = rs.getString("notes");
        po.createdAt = rs.getObject("created_at", java.time.LocalDateTime.class);
        return po;
    };

    public void insert(CardVersionPo po) {
        jdbc.update("INSERT INTO card_version "
                        + "(card_number, version_no, disease_code, diagnosis_type, onset_date, "
                        + "diagnosis_time, submitted_at, deadline, timely, late_reason, "
                        + "facility_code, reporter, change_kind, disease_changed, notes, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.cardNumber, po.versionNo, po.diseaseCode, po.diagnosisType, po.onsetDate,
                po.diagnosisTime, po.submittedAt, po.deadline, po.timely ? 1 : 0, po.lateReason,
                po.facilityCode, po.reporter, po.changeKind, po.diseaseChanged ? 1 : 0,
                po.notes, po.createdAt);
    }

    public CardVersionPo find(String cardNumber, int versionNo) {
        List<CardVersionPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM card_version WHERE card_number = ? AND version_no = ?",
                MAPPER, cardNumber, versionNo);
        return list.stream().findFirst().orElse(null);
    }

    public CardVersionPo findLatest(String cardNumber) {
        List<CardVersionPo> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM card_version WHERE card_number = ? "
                        + "ORDER BY version_no DESC LIMIT 1",
                MAPPER, cardNumber);
        return list.stream().findFirst().orElse(null);
    }

    public List<CardVersionPo> findAllByCard(String cardNumber) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM card_version WHERE card_number = ? ORDER BY version_no",
                MAPPER, cardNumber);
    }
}
