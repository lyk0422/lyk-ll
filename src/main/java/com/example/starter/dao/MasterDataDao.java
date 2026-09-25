package com.example.starter.dao;

import com.example.starter.domain.Grade;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 馈线、班组、重要用户等合成主数据访问。
 */
@Repository
public class MasterDataDao {

    private final JdbcTemplate jdbc;

    public MasterDataDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findFeederIdByCode(String feederCode) {
        if (feederCode == null || feederCode.isBlank()) {
            return Optional.empty();
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM feeder WHERE feeder_code = ?", Long.class, feederCode);
        return ids.stream().findFirst();
    }

    public Optional<Long> findCrewIdByCode(String crewCode) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM crew WHERE crew_code = ?", Long.class, crewCode);
        return ids.stream().findFirst();
    }

    public String getCrewCode(long crewId) {
        return jdbc.queryForObject("SELECT crew_code FROM crew WHERE id = ?", String.class, crewId);
    }

    /**
     * 更新馈线运行状态：NORMAL 正常 / FAULT 故障。
     */
    public void updateFeederStatus(Long feederId, String status) {
        if (feederId == null) {
            return;
        }
        jdbc.update("UPDATE feeder SET status = ? WHERE id = ?", status, feederId);
    }

    /**
     * 更新班组状态：IDLE 空闲 / BUSY 执行中（以 crew_execution 为准的冗余快照）。
     */
    public void updateCrewStatus(long crewId, String status) {
        jdbc.update("UPDATE crew SET status = ? WHERE id = ?", status, crewId);
    }

    /**
     * 查馈线编号。
     */
    public Optional<String> findFeederCodeById(Long feederId) {
        if (feederId == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT feeder_code FROM feeder WHERE id = ?", String.class, feederId)
                .stream().findFirst();
    }

    /**
     * 查找馈线下所有重要用户（按等级优先级排序）。
     */
    public List<ImportantUserRow> findUsersByFeeder(Long feederId) {
        if (feederId == null) {
            return List.of();
        }
        return jdbc.query(
                "SELECT id, user_code, user_name, grade FROM important_user WHERE feeder_id = ?",
                (rs, n) -> new ImportantUserRow(
                        rs.getLong("id"),
                        rs.getString("user_code"),
                        rs.getString("user_name"),
                        Grade.valueOf(rs.getString("grade"))),
                feederId);
    }

    /**
     * 馈线上最高保电等级（无用户时为 NORMAL）。
     */
    public Grade topGradeOfFeeder(Long feederId) {
        return findUsersByFeeder(feederId).stream()
                .map(ImportantUserRow::grade)
                .reduce(Grade.NORMAL, Grade::higher);
    }

    /**
     * 重要用户行。
     */
    public record ImportantUserRow(long id, String userCode, String userName, Grade grade) {
    }
}
