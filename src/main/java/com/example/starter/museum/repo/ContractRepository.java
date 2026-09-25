package com.example.starter.museum.repo;

import com.example.starter.museum.domain.po.LoanContractPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 借展合同仓储。
 * 唯一性：artifact_active_loan 主键约束保证同一文物同一时刻只有一个有效合同；
 * 终态竞争：loan_contract.version 乐观锁保证归还点交与续展只有一个终态生效。
 */
@Repository
public class ContractRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ContractRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Long insert(LoanContractPo po) {
        jdbc.update("""
                INSERT INTO loan_contract
                  (contract_no, artifact_id, borrower, start_date, end_date, original_days,
                   status, showcase_id, renew_count, suspended_at, resumed_at, returned_at,
                   closed_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                po.contractNo, po.artifactId, po.borrower, po.startDate, po.endDate,
                po.originalDays, po.status.name(), po.showcaseId, po.renewCount,
                Sql.ts(po.suspendedAt), Sql.ts(po.resumedAt), Sql.ts(po.returnedAt),
                Sql.ts(po.closedAt), Sql.ts(clock.instant()));
        return jdbc.queryForObject("SELECT id FROM loan_contract WHERE contract_no = ?",
                Long.class, po.contractNo);
    }

    /**
     * 尝试占用文物有效借展名额。插入成功返回 true；
     * 主键冲突（并发申请同一文物）返回 false，由上层转 409。
     */
    public boolean tryOccupyArtifact(Long artifactId, Long contractId) {
        try {
            jdbc.update(
                    "INSERT INTO artifact_active_loan (artifact_id, contract_id, created_at) VALUES (?, ?, ?)",
                    artifactId, contractId, Sql.ts(clock.instant()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void releaseArtifact(Long contractId) {
        jdbc.update("DELETE FROM artifact_active_loan WHERE contract_id = ?", contractId);
    }

    public Optional<LoanContractPo> findByNo(String contractNo) {
        return jdbc.query("SELECT * FROM loan_contract WHERE contract_no = ?",
                RowMappers.CONTRACT, contractNo).stream().findFirst();
    }

    public LoanContractPo getByNo(String contractNo) {
        return findByNo(contractNo)
                .orElseThrow(() -> new IllegalArgumentException("借展合同不存在: " + contractNo));
    }

    public Optional<LoanContractPo> findActiveByShowcase(Long showcaseId) {
        List<LoanContractPo> list = jdbc.query("""
                SELECT * FROM loan_contract
                WHERE showcase_id = ? AND status IN ('APPLIED', 'IN_EXHIBITION', 'SUSPENDED')
                ORDER BY id LIMIT 1
                """, RowMappers.CONTRACT, showcaseId);
        return list.stream().findFirst();
    }

    /** 展柜最近一份合同（含已结案），用于判定读数是否结案后迟到。 */
    public Optional<LoanContractPo> findLatestByShowcase(Long showcaseId) {
        List<LoanContractPo> list = jdbc.query("""
                SELECT * FROM loan_contract WHERE showcase_id = ? ORDER BY id DESC LIMIT 1
                """, RowMappers.CONTRACT, showcaseId);
        return list.stream().findFirst();
    }

    /**
     * 乐观锁全字段更新：WHERE 带版本号，返回受影响行数（0 表示并发方已先行改状态）。
     */
    public int updateWithVersion(LoanContractPo po) {
        return jdbc.update("""
                UPDATE loan_contract SET
                  borrower = ?, end_date = ?, status = ?, showcase_id = ?, renew_count = ?,
                  suspended_at = ?, resumed_at = ?, returned_at = ?, closed_at = ?,
                  version = version + 1
                WHERE id = ? AND version = ?
                """,
                po.borrower, po.endDate, po.status.name(), po.showcaseId, po.renewCount,
                Sql.ts(po.suspendedAt), Sql.ts(po.resumedAt), Sql.ts(po.returnedAt),
                Sql.ts(po.closedAt), po.id, po.version);
    }
}
