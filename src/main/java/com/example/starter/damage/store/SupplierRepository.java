package com.example.starter.damage.store;

import com.example.starter.damage.domain.QualificationStatus;
import com.example.starter.damage.domain.SupplierStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 合成供应商数据访问：供应商主档（乐观版本）、适用工法与资格版本。
 * 资格有效期为 UTC 半开区间 [validFrom, validTo)，恰在 validTo 视为已失效。
 */
@Repository
public class SupplierRepository {

    private static final RowMapper<SupplierRow> SUPPLIER_MAPPER = (rs, n) -> new SupplierRow(
            rs.getLong("id"),
            rs.getString("supplier_code"),
            rs.getString("name"),
            rs.getInt("version"),
            SupplierStatus.valueOf(rs.getString("status")));

    private static final RowMapper<QualRow> QUAL_MAPPER = (rs, n) -> new QualRow(
            rs.getLong("id"),
            rs.getLong("supplier_id"),
            rs.getInt("version"),
            rs.getTimestamp("valid_from").toLocalDateTime(),
            rs.getTimestamp("valid_to").toLocalDateTime(),
            QualificationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toLocalDateTime(),
            rs.getString("revoke_reason"));

    private final JdbcTemplate jdbc;

    public SupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 供应商主档行 */
    public record SupplierRow(long id, String code, String name, int version, SupplierStatus status) {
    }

    /** 资格版本行；revokedAt 非空表示该版本已被撤销 */
    public record QualRow(long id, long supplierId, int version, LocalDateTime validFrom, LocalDateTime validTo,
                          QualificationStatus status, LocalDateTime revokedAt, String revokeReason) {
    }

    /** 登记供应商主档（版本从 1 开始，状态 ACTIVE），返回新 ID */
    public long insertSupplier(String code, String name, LocalDateTime now) {
        jdbc.update("insert into supplier(supplier_code, name, version, status, created_at, updated_at)"
                        + " values (?,?,1,'ACTIVE',?,?)",
                code, name, now, now);
        Long id = jdbc.queryForObject("select id from supplier where supplier_code = ?", Long.class, code);
        return id;
    }

    /** 批量写入适用工法 */
    public void insertMethods(long supplierId, List<String> methods) {
        for (String method : methods) {
            jdbc.update("insert into supplier_method(supplier_id, method_code) values (?,?)", supplierId, method);
        }
    }

    /** 供应商适用工法集合（排序后返回，保证视图稳定） */
    public List<String> listMethods(long supplierId) {
        return jdbc.queryForList(
                "select method_code from supplier_method where supplier_id = ? order by method_code",
                String.class, supplierId);
    }

    /** 新增资格版本（版本号由调用方按供应商版本串行计算） */
    public long insertQualification(long supplierId, int version, LocalDateTime validFrom,
                                    LocalDateTime validTo, LocalDateTime now) {
        jdbc.update("insert into supplier_qualification"
                        + "(supplier_id, version, valid_from, valid_to, status, created_at)"
                        + " values (?,?,?,?,'ACTIVE',?)",
                supplierId, version, validFrom, validTo, now);
        return jdbc.queryForObject(
                "select id from supplier_qualification where supplier_id = ? and version = ?",
                Long.class, supplierId, version);
    }

    /** 按编号查询供应商 */
    public Optional<SupplierRow> findByCode(String code) {
        return jdbc.query("select * from supplier where supplier_code = ?", SUPPLIER_MAPPER, code)
                .stream().findFirst();
    }

    /** 按 ID 查询供应商 */
    public Optional<SupplierRow> findById(long id) {
        return jdbc.query("select * from supplier where id = ?", SUPPLIER_MAPPER, id)
                .stream().findFirst();
    }

    /** 按编号加行锁查询供应商（串行化续期/撤销） */
    public Optional<SupplierRow> findByCodeForUpdate(String code) {
        return jdbc.query("select * from supplier where supplier_code = ? for update", SUPPLIER_MAPPER, code)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询供应商（开标时与资格撤销竞争串行化） */
    public Optional<SupplierRow> findByIdForUpdate(long id) {
        return jdbc.query("select * from supplier where id = ? for update", SUPPLIER_MAPPER, id)
                .stream().findFirst();
    }

    /** 指定资格版本 */
    public Optional<QualRow> findQualification(long supplierId, int version) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? and version = ?",
                        QUAL_MAPPER, supplierId, version)
                .stream().findFirst();
    }

    /** 最近资格版本（版本号最大），无则空 */
    public Optional<QualRow> latestQualification(long supplierId) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? order by version desc limit 1",
                        QUAL_MAPPER, supplierId)
                .stream().findFirst();
    }

    /**
     * 按指定时刻（UTC）判断当时有效的资格版本：status=ACTIVE 且 validFrom &lt;= at &lt; validTo，
     * 取版本号最大者。开标必须用开标时刻调用，不能采用投标时的旧资格。
     */
    public Optional<QualRow> activeQualificationAt(long supplierId, LocalDateTime at) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ?"
                                + " and status = 'ACTIVE' and valid_from <= ? and ? < valid_to"
                                + " order by version desc limit 1",
                        QUAL_MAPPER, supplierId, at, at)
                .stream().findFirst();
    }

    /** 撤销指定资格版本（原地记录撤销时刻与原因；中标凭证另有快照，不受影响） */
    public void revokeQualification(long qualificationId, LocalDateTime revokedAt, String reason) {
        jdbc.update("update supplier_qualification set status = 'REVOKED', revoked_at = ?, revoke_reason = ?"
                        + " where id = ?",
                revokedAt, reason, qualificationId);
    }

    /**
     * 乐观递增供应商版本；仅当当前版本等于 expectedVersion 时生效。
     *
     * @return 受影响行数，0 表示版本已被并发修改
     */
    public int bumpVersionIfMatch(long supplierId, int expectedVersion, LocalDateTime now) {
        return jdbc.update("update supplier set version = version + 1, updated_at = ?"
                        + " where id = ? and version = ?",
                now, supplierId, expectedVersion);
    }

    /** 将供应商置为已撤销（成功撤销后状态翻转，版本递增由 {@link #bumpVersionIfMatch} 完成） */
    public void markRevoked(long supplierId, LocalDateTime now) {
        jdbc.update("update supplier set status = 'REVOKED', updated_at = ? where id = ?", now, supplierId);
    }
}
