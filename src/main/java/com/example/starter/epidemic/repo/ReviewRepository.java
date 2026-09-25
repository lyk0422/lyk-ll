package com.example.starter.epidemic.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 审核轨迹仓储。记录只追加；病种变更重起审核时旧初审结论批量置为作废留痕。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "id, card_number, version_no, stage, reviewer, opinion, outcome, voided, created_at";

    private static final RowMapper<ReviewPo> MAPPER = (rs, n) -> {
        ReviewPo po = new ReviewPo();
        po.id = rs.getLong("id");
        po.cardNumber = rs.getString("card_number");
        po.versionNo = rs.getInt("version_no");
        po.stage = rs.getString("stage");
        po.reviewer = rs.getString("reviewer");
        po.opinion = rs.getString("opinion");
        po.outcome = rs.getString("outcome");
        po.voided = rs.getInt("voided") == 1;
        po.createdAt = rs.getObject("created_at", java.time.LocalDateTime.class);
        return po;
    };

    public void insert(ReviewPo po) {
        jdbc.update("INSERT INTO review_record "
                        + "(card_number, version_no, stage, reviewer, opinion, outcome, voided, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.cardNumber, po.versionNo, po.stage, po.reviewer, po.opinion,
                po.outcome, po.voided ? 1 : 0, po.createdAt);
    }

    /**
     * 将该卡所有未作废的初审通过记录作废（病种变更类订正重起审核时调用）。
     */
    public int voidActiveReviewApprovals(String cardNumber) {
        return jdbc.update(
                "UPDATE review_record SET voided = 1 "
                        + "WHERE card_number = ? AND stage = 'REVIEW' AND outcome = 'APPROVED' AND voided = 0",
                cardNumber);
    }

    public List<ReviewPo> findAllByCard(String cardNumber) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM review_record WHERE card_number = ? ORDER BY id",
                MAPPER, cardNumber);
    }

    /**
     * 查询某版本是否存在未作废的初审通过记录。
     */
    public boolean existsActiveReviewApproval(String cardNumber, int versionNo) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM review_record "
                        + "WHERE card_number = ? AND version_no = ? AND stage = 'REVIEW' "
                        + "AND outcome = 'APPROVED' AND voided = 0",
                Integer.class, cardNumber, versionNo);
        return cnt != null && cnt > 0;
    }
}
