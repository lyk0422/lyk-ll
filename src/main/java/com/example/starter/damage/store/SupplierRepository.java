package com.example.starter.damage.store;

import com.example.starter.damage.dto.Views.QualificationView;
import com.example.starter.damage.dto.Views.RiskEventView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 供应商与资格版本数据访问。资格全量保留历史版本，
 * 开标以开标时刻的资格状态评估，不采用提交时旧资格。
 */
@Repository
public class SupplierRepository {

    private static final RowMapper<SupplierRow> SUPPLIER_MAPPER = (rs, n) -> new SupplierRow(
            rs.getLong("id"),
            rs.getString("supplier_no"),
            rs.getString("name"),
            rs.getInt("version"));

    private static final RowMapper<QualificationRow> QUAL_MAPPER = (rs, n) -> new QualificationRow(
            rs.getLong("id"),
            rs.getLong("supplier_id"),
            rs.getInt("version"),
            rs.getTimestamp("valid_from").toLocalDateTime(),
            rs.getTimestamp("valid_until").toLocalDateTime(),
            rs.getString("status"),
            rs.getBoolean("frozen"));

    private final JdbcTemplate jdbc;

    public SupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 供应商行记录 */
    public record SupplierRow(long id, String supplierNo, String name, int version) {
    }

    /** 资格版本行记录 */
    public record QualificationRow(
            long id,
            long supplierId,
            int version,
            LocalDateTime validFrom,
            LocalDateTime validUntil,
            String status,
            boolean frozen) {
    }

    /** 工法代码是否存在 */
    public boolean methodExists(String code) {
        Integer count = jdbc.queryForObject("select count(*) from repair_method where code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    /** 登记供应商：初始版本 1；编号冲突时抛出 DuplicateKeyException */
    public long insertSupplier(String supplierNo, String name, LocalDateTime now) {
        jdbc.update("insert into supplier(supplier_no, name, version, created_at, updated_at) values (?,?,?,?,?)",
                supplierNo, name, 1, now, now);
        return jdbc.queryForObject("select max(id) from supplier where supplier_no = ?", Long.class, supplierNo);
    }

    /** 按 ID 查询供应商 */
    public Optional<SupplierRow> findById(long id) {
        return jdbc.query("select * from supplier where id = ?", SUPPLIER_MAPPER, id)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询供应商（与开标/撤销串行化） */
    public Optional<SupplierRow> findByIdForUpdate(long id) {
        return jdbc.query("select * from supplier where id = ? for update", SUPPLIER_MAPPER, id)
                .stream().findFirst();
    }

    /** 更新供应商当前版本号 */
    public void updateVersion(long id, int version, LocalDateTime now) {
        jdbc.update("update supplier set version = ?, updated_at = ? where id = ?", version, now, id);
    }

    /** 新增资格版本；返回资格版本 ID */
    public long insertQualification(long supplierId, int version, LocalDateTime validFrom,
                                    LocalDateTime validUntil, String status, LocalDateTime now) {
        jdbc.update("insert into supplier_qualification(supplier_id, version, valid_from, valid_until, status, frozen, created_at)"
                        + " values (?,?,?,?,?,false,?)",
                supplierId, version, validFrom, validUntil, status, now);
        return jdbc.queryForObject(
                "select max(id) from supplier_qualification where supplier_id = ?", Long.class, supplierId);
    }

    /** 将供应商当前 ACTIVE 资格置为 SUPERSEDED */
    public void supersedeActiveQualifications(long supplierId) {
        jdbc.update("update supplier_qualification set status = 'SUPERSEDED'"
                + " where supplier_id = ? and status = 'ACTIVE'", supplierId);
    }

    /** 供应商全部资格版本（按版本号，可复算） */
    public List<QualificationRow> listQualifications(long supplierId) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? order by version",
                QUAL_MAPPER, supplierId);
    }

    /** 供应商当前资格版本（版本号等于 supplier.version），无则 null */
    public QualificationRow findCurrentQualification(long supplierId, int version) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? and version = ?",
                        QUAL_MAPPER, supplierId, version)
                .stream().findFirst().orElse(null);
    }

    /** 按供应商与版本号查询资格版本，无则 null */
    public QualificationRow findQualificationByVersion(long supplierId, int version) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? and version = ?",
                        QUAL_MAPPER, supplierId, version)
                .stream().findFirst().orElse(null);
    }

    /** 登记资格版本适用工法 */
    public void insertQualMethod(long qualificationId, String methodCode) {
        jdbc.update("insert into supplier_qual_method(qualification_id, method_code) values (?,?)",
                qualificationId, methodCode);
    }

    /** 资格版本适用工法集合（按代码排序，可复算） */
    public List<String> listQualMethods(long qualificationId) {
        return jdbc.queryForList("select method_code from supplier_qual_method where qualification_id = ?"
                + " order by method_code", String.class, qualificationId);
    }

    /** 冻结资格版本（定标时调用） */
    public void freezeQualification(long qualificationId) {
        jdbc.update("update supplier_qualification set frozen = true where id = ?", qualificationId);
    }

    /** 供应商是否存在已冻结资格版本（定标后撤销只形成风险事件的判定依据） */
    public boolean hasFrozenQualification(long supplierId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from supplier_qualification where supplier_id = ? and frozen = true",
                Integer.class, supplierId);
        return count != null && count > 0;
    }

    /** 记录风险事件 */
    public void insertRiskEvent(Long caseId, Long inquiryId, long supplierId,
                                String eventType, String detail, LocalDateTime now) {
        jdbc.update("insert into risk_event(case_id, inquiry_id, supplier_id, event_type, detail, created_at)"
                        + " values (?,?,?,?,?,?)",
                caseId, inquiryId, supplierId, eventType, detail, now);
    }

    /** 已定标询价关联（残损案 ID 与询价 ID） */
    public record AwardedLink(long caseId, long inquiryId) {
    }

    /** 供应商参与的最近一个已定标询价，用于定标后撤销的风险事件关联 */
    public Optional<AwardedLink> findAwardedInquiryForSupplier(long supplierId) {
        return jdbc.query("select i.case_id, i.id from inquiry i"
                        + " join quotation q on q.inquiry_id = i.id and q.supplier_id = ?"
                        + " where i.status = 'AWARDED' order by i.id desc limit 1",
                (rs, n) -> new AwardedLink(rs.getLong(1), rs.getLong(2)), supplierId)
                .stream().findFirst();
    }

    /** 供应商风险事件（按登记顺序） */
    public List<RiskEventView> listRiskEventsBySupplier(long supplierId) {
        return jdbc.query("select * from risk_event where supplier_id = ? order by id",
                (rs, n) -> new RiskEventView(rs.getLong("id"),
                        rs.getObject("case_id") == null ? null : rs.getLong("case_id"),
                        rs.getObject("inquiry_id") == null ? null : rs.getLong("inquiry_id"),
                        rs.getLong("supplier_id"), rs.getString("event_type"), rs.getString("detail")),
                supplierId);
    }

    /** 资格版本视图组装 */
    public QualificationView toQualificationView(QualificationRow row) {
        return new QualificationView(row.id(), row.version(), listQualMethods(row.id()),
                row.validFrom(), row.validUntil(), row.status(), row.frozen());
    }
}
