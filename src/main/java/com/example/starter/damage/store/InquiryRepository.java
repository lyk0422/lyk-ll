package com.example.starter.damage.store;

import com.example.starter.damage.dto.Views.BidOpeningEntryView;
import com.example.starter.damage.dto.Views.BidOpeningView;
import com.example.starter.damage.dto.Views.InquiryItemView;
import com.example.starter.damage.dto.Views.InquiryView;
import com.example.starter.damage.dto.Views.QuotationLineView;
import com.example.starter.damage.dto.Views.QuotationView;
import com.example.starter.damage.dto.Views.ReviewView;
import com.example.starter.damage.dto.Views.RiskEventView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 密封询价数据访问：询价版本、固定项、报价版本、开标清单与定标复核。
 * 一供应商一询价版本只一个当前报价（uk_quotation_current 唯一索引仲裁）；
 * 一询价版本只开标一次（uk_bid_opening_inquiry 唯一索引仲裁）。
 */
@Repository
public class InquiryRepository {

    private static final RowMapper<InquiryView> INQUIRY_MAPPER = (rs, n) -> new InquiryView(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getInt("version"),
            rs.getTimestamp("deadline").toLocalDateTime(),
            rs.getString("status"));

    private static final RowMapper<QuotationRow> QUOTATION_MAPPER = (rs, n) -> new QuotationRow(
            rs.getLong("id"),
            rs.getLong("inquiry_id"),
            rs.getLong("supplier_id"),
            rs.getInt("version"),
            rs.getLong("total_amount"),
            rs.getString("status"),
            rs.getBoolean("frozen"));

    private static final RowMapper<BidOpeningEntryView> ENTRY_MAPPER = (rs, n) -> new BidOpeningEntryView(
            rs.getLong("quotation_id"),
            rs.getLong("supplier_id"),
            rs.getString("supplier_no"),
            rs.getLong("total_amount"),
            rs.getBoolean("included"),
            rs.getString("exclusion_reason"),
            rs.getObject("qual_version") == null ? null : rs.getInt("qual_version"),
            rs.getObject("rank_no") == null ? null : rs.getInt("rank_no"));

    private final JdbcTemplate jdbc;

    public InquiryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 报价行记录 */
    public record QuotationRow(
            long id,
            long inquiryId,
            long supplierId,
            int version,
            long totalAmount,
            String status,
            boolean frozen) {
    }

    /** 开标记录行 */
    public record OpeningRow(long id, long inquiryId, Long winnerQuotationId, LocalDateTime openedAt) {
    }

    // ---------- 询价版本 ----------

    /** 发布询价版本：版本号按案件递增；返回询价 ID */
    public long insertInquiry(long caseId, LocalDateTime deadline, String requestId, LocalDateTime now) {
        Integer max = jdbc.queryForObject("select coalesce(max(version), 0) from inquiry where case_id = ?",
                Integer.class, caseId);
        int version = (max == null ? 0 : max) + 1;
        jdbc.update("insert into inquiry(case_id, version, deadline, status, request_id, created_at)"
                        + " values (?,?,?,?,?,?)",
                caseId, version, deadline, "OPEN", requestId, now);
        return jdbc.queryForObject("select max(id) from inquiry where case_id = ?", Long.class, caseId);
    }

    /** 将案件未结询价（OPEN/OPENED）置为 SUPERSEDED */
    public void supersedeOpenInquiries(long caseId) {
        jdbc.update("update inquiry set status = 'SUPERSEDED'"
                + " where case_id = ? and status in ('OPEN','OPENED')", caseId);
    }

    /** 按 ID 查询询价 */
    public Optional<InquiryView> findInquiry(long inquiryId) {
        return jdbc.query("select * from inquiry where id = ?", INQUIRY_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询询价（开标/复核/投标串行化） */
    public Optional<InquiryView> findInquiryForUpdate(long inquiryId) {
        return jdbc.query("select * from inquiry where id = ? for update", INQUIRY_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 案件全部询价版本（按版本号） */
    public List<InquiryView> listInquiries(long caseId) {
        return jdbc.query("select * from inquiry where case_id = ? order by version", INQUIRY_MAPPER, caseId);
    }

    /** 更新询价状态（OPENED/AWARDED） */
    public void updateInquiryStatus(long inquiryId, String status) {
        jdbc.update("update inquiry set status = ? where id = ?", status, inquiryId);
    }

    // ---------- 询价项 ----------

    /** 登记询价项；返回询价项 ID */
    public long insertInquiryItem(long inquiryId, long itemId) {
        jdbc.update("insert into inquiry_item(inquiry_id, item_id) values (?,?)", inquiryId, itemId);
        return jdbc.queryForObject("select max(id) from inquiry_item where inquiry_id = ?",
                Long.class, inquiryId);
    }

    /** 登记询价项允许工法 */
    public void insertInquiryItemMethod(long inquiryItemId, String methodCode) {
        jdbc.update("insert into inquiry_item_method(inquiry_item_id, method_code) values (?,?)",
                inquiryItemId, methodCode);
    }

    /** 询价固定项集合（含允许工法，可复算） */
    public List<InquiryItemView> listInquiryItems(long inquiryId) {
        return jdbc.query("select * from inquiry_item where inquiry_id = ? order by id",
                (rs, n) -> {
                    long id = rs.getLong("id");
                    List<String> methods = jdbc.queryForList(
                            "select method_code from inquiry_item_method where inquiry_item_id = ?"
                                    + " order by method_code", String.class, id);
                    return new InquiryItemView(id, rs.getLong("item_id"), methods);
                }, inquiryId);
    }

    // ---------- 报价 ----------

    /** 新增报价版本；current_key=supplier_id 保证一供应商一询价一当前报价；返回报价 ID */
    public long insertQuotation(long inquiryId, long supplierId, int version, long totalAmount,
                                String requestId, LocalDateTime now) {
        jdbc.update("insert into quotation(inquiry_id, supplier_id, version, total_amount, status,"
                        + " current_key, frozen, request_id, created_at) values (?,?,?,?,?,?,false,?,?)",
                inquiryId, supplierId, version, totalAmount, "CURRENT", supplierId, requestId, now);
        return jdbc.queryForObject("select max(id) from quotation where inquiry_id = ? and supplier_id = ?",
                Long.class, inquiryId, supplierId);
    }

    /** 将供应商当前报价置为 SUPERSEDED 并释放 current_key */
    public void supersedeCurrentQuotation(long inquiryId, long supplierId) {
        jdbc.update("update quotation set status = 'SUPERSEDED', current_key = null"
                + " where inquiry_id = ? and supplier_id = ? and status = 'CURRENT'", inquiryId, supplierId);
    }

    /** 按 ID 查询报价版本，无则 null */
    public QuotationRow findQuotationById(long quotationId) {
        return jdbc.query("select * from quotation where id = ?", QUOTATION_MAPPER, quotationId)
                .stream().findFirst().orElse(null);
    }

    /** 供应商当前报价，无则 null */
    public QuotationRow findCurrentQuotation(long inquiryId, long supplierId) {
        return jdbc.query("select * from quotation where inquiry_id = ? and supplier_id = ?"
                        + " and status = 'CURRENT'", QUOTATION_MAPPER, inquiryId, supplierId)
                .stream().findFirst().orElse(null);
    }

    /** 询价全部当前报价（开标评估对象） */
    public List<QuotationRow> listCurrentQuotations(long inquiryId) {
        return jdbc.query("select * from quotation where inquiry_id = ? and status = 'CURRENT' order by id",
                QUOTATION_MAPPER, inquiryId);
    }

    /** 供应商报价版本历史（按版本号） */
    public List<QuotationRow> listQuotationHistory(long inquiryId, long supplierId) {
        return jdbc.query("select * from quotation where inquiry_id = ? and supplier_id = ? order by version",
                QUOTATION_MAPPER, inquiryId, supplierId);
    }

    /** 询价全部报价版本（按供应商与版本号，可复算） */
    public List<QuotationRow> listAllQuotations(long inquiryId) {
        return jdbc.query("select * from quotation where inquiry_id = ?"
                + " order by supplier_id, version", QUOTATION_MAPPER, inquiryId);
    }

    /** 登记报价行 */
    public void insertQuotationLine(long quotationId, long itemId, String methodCode, long amountCents) {
        jdbc.update("insert into quotation_line(quotation_id, item_id, method_code, amount_cents)"
                        + " values (?,?,?,?)",
                quotationId, itemId, methodCode, amountCents);
    }

    /** 报价行明细（按登记顺序） */
    public List<QuotationLineView> listQuotationLines(long quotationId) {
        return jdbc.query("select * from quotation_line where quotation_id = ? order by id",
                (rs, n) -> new QuotationLineView(rs.getLong("item_id"),
                        rs.getString("method_code"), rs.getLong("amount_cents")), quotationId);
    }

    /** 冻结询价全部当前报价（开标时调用） */
    public void freezeCurrentQuotations(long inquiryId) {
        jdbc.update("update quotation set frozen = true where inquiry_id = ? and status = 'CURRENT'", inquiryId);
    }

    // ---------- 开标 ----------

    /** 登记开标记录；一询价版本只开标一次；返回开标 ID */
    public long insertOpening(long inquiryId, Long winnerQuotationId, LocalDateTime now) {
        jdbc.update("insert into bid_opening(inquiry_id, winner_quotation_id, opened_at) values (?,?,?)",
                inquiryId, winnerQuotationId, now);
        return jdbc.queryForObject("select max(id) from bid_opening where inquiry_id = ?",
                Long.class, inquiryId);
    }

    /** 询价的开标记录，无则 null */
    public OpeningRow findOpening(long inquiryId) {
        return jdbc.query("select * from bid_opening where inquiry_id = ?",
                (rs, n) -> new OpeningRow(rs.getLong("id"), rs.getLong("inquiry_id"),
                        rs.getObject("winner_quotation_id") == null ? null : rs.getLong("winner_quotation_id"),
                        rs.getTimestamp("opened_at").toLocalDateTime()), inquiryId)
                .stream().findFirst().orElse(null);
    }

    /** 登记开标明细 */
    public void insertOpeningEntry(long openingId, long quotationId, long supplierId, String supplierNo,
                                   long totalAmount, boolean included, String exclusionReason,
                                   Integer qualVersion, Integer rankNo) {
        jdbc.update("insert into bid_opening_entry(opening_id, quotation_id, supplier_id, supplier_no,"
                        + " total_amount, included, exclusion_reason, qual_version, rank_no)"
                        + " values (?,?,?,?,?,?,?,?,?)",
                openingId, quotationId, supplierId, supplierNo, totalAmount, included,
                exclusionReason, qualVersion, rankNo);
    }

    /** 开标明细清单（按名次与被排除项，可复算） */
    public List<BidOpeningEntryView> listOpeningEntries(long openingId) {
        return jdbc.query("select * from bid_opening_entry where opening_id = ?"
                + " order by included desc, rank_no, id", ENTRY_MAPPER, openingId);
    }

    // ---------- 复核与风险事件 ----------

    /** 登记复核；同一复核人重复时抛出 DuplicateKeyException */
    public void insertReview(long inquiryId, String reviewer, LocalDateTime now) {
        jdbc.update("insert into award_review(inquiry_id, reviewer, created_at) values (?,?,?)",
                inquiryId, reviewer, now);
    }

    /** 复核记录（按登记顺序） */
    public List<ReviewView> listReviews(long inquiryId) {
        return jdbc.query("select * from award_review where inquiry_id = ? order by id",
                (rs, n) -> new ReviewView(rs.getLong("id"), rs.getString("reviewer")), inquiryId);
    }

    /** 复核人数 */
    public int countReviews(long inquiryId) {
        Integer count = jdbc.queryForObject("select count(*) from award_review where inquiry_id = ?",
                Integer.class, inquiryId);
        return count == null ? 0 : count;
    }

    /** 案件风险事件（按登记顺序） */
    public List<RiskEventView> listRiskEventsByCase(long caseId) {
        return jdbc.query("select * from risk_event where case_id = ? order by id",
                (rs, n) -> new RiskEventView(rs.getLong("id"),
                        rs.getObject("case_id") == null ? null : rs.getLong("case_id"),
                        rs.getObject("inquiry_id") == null ? null : rs.getLong("inquiry_id"),
                        rs.getLong("supplier_id"), rs.getString("event_type"), rs.getString("detail")),
                caseId);
    }

    /** 报价视图组装（含行明细） */
    public QuotationView toQuotationView(QuotationRow row) {
        return new QuotationView(row.id(), row.inquiryId(), row.supplierId(), row.version(),
                row.totalAmount(), row.status(), row.frozen(), listQuotationLines(row.id()));
    }
}
