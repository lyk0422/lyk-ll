package com.example.starter.damage.store;

import com.example.starter.damage.domain.QualificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 合成供应商、适用工法与版本化资格数据访问（密封询价流程）。
 * 资格以追加版本方式演进，撤销追加 REVOKED 版本，历史行不改写，作为开标/中标证据快照来源。
 */
@Repository
public class SupplierRepository {

    private static final RowMapper<SupplierRow> SUPPLIER_MAPPER = (rs, n) -> new SupplierRow(
            rs.getLong("id"),
            rs.getString("supplier_no"),
            rs.getString("name"));

    private static final RowMapper<QualRow> QUAL_MAPPER = (rs, n) -> {
        Timestamp to = rs.getTimestamp("valid_to");
        return new QualRow(
                rs.getLong("id"),
                rs.getLong("supplier_id"),
                rs.getInt("version"),
                QualificationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("valid_from").toInstant(),
                to == null ? null : to.toInstant());
    };

    private final JdbcTemplate jdbc;

    public SupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 供应商行记录 */
    public record SupplierRow(long id, String supplierNo, String name) {
    }

    /** 资格版本行记录；validTo 为 null 表示长期有效 */
    public record QualRow(
            long id,
            long supplierId,
            int version,
            QualificationStatus status,
            Instant validFrom,
            Instant validTo) {
    }

    /** 合成工法代码是否存在 */
    public boolean methodExists(String code) {
        Integer count = jdbc.queryForObject("select count(*) from repair_method where code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    /** 创建供应商，返回 ID */
    public long insertSupplier(String supplierNo, String name, Instant now) {
        jdbc.update("insert into supplier(supplier_no, name, created_at) values (?,?,?)",
                supplierNo, name, Timestamp.from(now));
        Long id = jdbc.queryForObject("select max(id) from supplier where supplier_no = ?", Long.class, supplierNo);
        return id == null ? 0 : id;
    }

    /** 按编号查询 */
    public Optional<SupplierRow> findByNo(String supplierNo) {
        return jdbc.query("select * from supplier where supplier_no = ?", SUPPLIER_MAPPER, supplierNo)
                .stream().findFirst();
    }

    /** 按 ID 查询 */
    public Optional<SupplierRow> findById(long supplierId) {
        return jdbc.query("select * from supplier where id = ?", SUPPLIER_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询（撤销与开标竞争时串行化资格判定） */
    public Optional<SupplierRow> findByIdForUpdate(long supplierId) {
        return jdbc.query("select * from supplier where id = ? for update", SUPPLIER_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 增加适用工法 */
    public void addMethod(long supplierId, String methodCode) {
        jdbc.update("insert into supplier_method(supplier_id, method_code) values (?,?)", supplierId, methodCode);
    }

    /** 适用工法集合（按代码升序） */
    public List<String> listMethods(long supplierId) {
        return jdbc.queryForList(
                "select method_code from supplier_method where supplier_id = ? order by method_code",
                String.class, supplierId);
    }

    /** 追加资格版本，返回新行 ID */
    public long insertQualification(long supplierId, int version, QualificationStatus status,
                                    Instant validFrom, Instant validTo, String requestId, Instant now) {
        jdbc.update("insert into supplier_qualification"
                        + "(supplier_id, version, status, valid_from, valid_to, request_id, created_at)"
                        + " values (?,?,?,?,?,?,?)",
                supplierId, version, status.name(), Timestamp.from(validFrom),
                validTo == null ? null : Timestamp.from(validTo), requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject(
                "select max(id) from supplier_qualification where supplier_id = ? and version = ?",
                Long.class, supplierId, version);
        return id == null ? 0 : id;
    }

    /** 当前最新资格版本，无则空 */
    public Optional<QualRow> findLatestQualification(long supplierId) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? order by version desc",
                        QUAL_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 按供应商 ID 加锁后取最新资格版本（开标资格判定须持锁，避免与撤销竞争） */
    public Optional<QualRow> findLatestQualificationForUpdate(long supplierId) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? order by version desc for update",
                        QUAL_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 资格版本全量历史（按版本号） */
    public List<QualRow> listQualifications(long supplierId) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? order by version",
                QUAL_MAPPER, supplierId);
    }
}
