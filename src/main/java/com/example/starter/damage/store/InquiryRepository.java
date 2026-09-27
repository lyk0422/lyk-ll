package com.example.starter.damage.store;

import com.example.starter.damage.domain.InquiryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 密封询价版本数据访问：询价主表、固定残损项快照与每项允许工法。
 */
@Repository
public class InquiryRepository {

    private static final RowMapper<InquiryRow> INQUIRY_MAPPER = (rs, n) -> new InquiryRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getInt("version"),
            rs.getTimestamp("deadline").toLocalDateTime(),
            InquiryStatus.valueOf(rs.getString("status")));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, n) -> new ItemRow(
            rs.getLong("id"),
            rs.getInt("seq_no"),
            rs.getLong("damage_item_id"),
            rs.getString("iicl_code"),
            rs.getString("component"));

    private final JdbcTemplate jdbc;

    public InquiryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 询价版本行 */
    public record InquiryRow(long id, long caseId, int version, LocalDateTime deadline, InquiryStatus status) {
    }

    /** 询价固定残损项行 */
    public record ItemRow(long id, int seqNo, long damageItemId, String iiclCode, String component) {
    }

    /** 案件最大询价版本号，无则 0 */
    public int maxVersion(long caseId) {
        Integer max = jdbc.queryForObject("select coalesce(max(version),0) from inquiry where case_id = ?",
                Integer.class, caseId);
        return max == null ? 0 : max;
    }

    /** 将案件当前 OPEN 询价置为 SUPERSEDED */
    public void supersedeOpen(long caseId) {
        jdbc.update("update inquiry set status = 'SUPERSEDED' where case_id = ? and status = 'OPEN'", caseId);
    }

    /** 新增询价版本（状态 OPEN），返回新 ID */
    public long insertInquiry(long caseId, int version, LocalDateTime deadline, String requestId, LocalDateTime now) {
        jdbc.update("insert into inquiry(case_id, version, deadline, status, request_id, created_at)"
                        + " values (?,?,?,'OPEN',?,?)",
                caseId, version, deadline, requestId, now);
        return jdbc.queryForObject("select id from inquiry where case_id = ? and version = ?",
                Long.class, caseId, version);
    }

    /** 新增固定残损项快照 */
    public long insertItem(long inquiryId, int seqNo, long damageItemId,
                           String iiclCode, String component) {
        jdbc.update("insert into inquiry_item(inquiry_id, damage_item_id, seq_no, iicl_code, component)"
                        + " values (?,?,?,?,?)",
                inquiryId, damageItemId, seqNo, iiclCode, component);
        return jdbc.queryForObject("select id from inquiry_item where inquiry_id = ? and seq_no = ?",
                Long.class, inquiryId, seqNo);
    }

    /** 新增询价项允许工法 */
    public void insertItemMethod(long inquiryItemId, String methodCode) {
        jdbc.update("insert into inquiry_item_method(inquiry_item_id, method_code) values (?,?)",
                inquiryItemId, methodCode);
    }

    /** 按 ID 查询询价版本 */
    public Optional<InquiryRow> findById(long inquiryId) {
        return jdbc.query("select * from inquiry where id = ?", INQUIRY_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询询价版本（开标/复核串行化） */
    public Optional<InquiryRow> findByIdForUpdate(long inquiryId) {
        return jdbc.query("select * from inquiry where id = ? for update", INQUIRY_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 案件当前 OPEN 询价版本，无则空 */
    public Optional<InquiryRow> findOpenByCase(long caseId) {
        return jdbc.query("select * from inquiry where case_id = ? and status = 'OPEN' order by version desc",
                        INQUIRY_MAPPER, caseId)
                .stream().findFirst();
    }

    /** 更新询价状态 */
    public void updateStatus(long inquiryId, InquiryStatus status) {
        jdbc.update("update inquiry set status = ? where id = ?", status.name(), inquiryId);
    }

    /** 询价固定残损项（按序号） */
    public List<ItemRow> listItems(long inquiryId) {
        return jdbc.query("select * from inquiry_item where inquiry_id = ? order by seq_no",
                ITEM_MAPPER, inquiryId);
    }

    /** 询价项允许工法（排序后稳定输出） */
    public List<String> listItemMethods(long inquiryItemId) {
        return jdbc.queryForList(
                "select method_code from inquiry_item_method where inquiry_item_id = ? order by method_code",
                String.class, inquiryItemId);
    }

    /** 指定询价项是否允许某工法 */
    public boolean itemAllowsMethod(long inquiryItemId, String methodCode) {
        Integer count = jdbc.queryForObject(
                "select count(*) from inquiry_item_method where inquiry_item_id = ? and method_code = ?",
                Integer.class, inquiryItemId, methodCode);
        return count != null && count > 0;
    }
}
