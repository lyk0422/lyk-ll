package com.example.starter.damage.store;

import com.example.starter.damage.dto.SealedViews.BidLineView;
import com.example.starter.damage.dto.SealedViews.BidView;
import com.example.starter.damage.dto.SealedViews.InquiryItemView;
import com.example.starter.damage.dto.SealedViews.InquiryView;
import com.example.starter.damage.dto.SealedViews.OpeningBidView;
import com.example.starter.damage.dto.SealedViews.QualificationView;
import com.example.starter.damage.dto.SealedViews.SupplierView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 密封报价与供应商资格数据访问：
 * 版本行只追加，开标冻结证据不回写；所有查询按版本/序号稳定排序，可复算。
 */
@Repository
public class SealedBidRepository {

    private final JdbcTemplate jdbc;

    public SealedBidRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 供应商行 */
    public record SupplierRow(long id, String supplierNo, String name) {
    }

    /** 资格版本行 */
    public record QualificationRow(
            long id, long supplierId, int version,
            LocalDateTime validFrom, LocalDateTime validUntil,
            String status, LocalDateTime revokedAt) {
    }

    /** 询价版本行 */
    public record InquiryRow(
            long id, long caseId, int version, LocalDateTime deadline,
            String status, LocalDateTime openedAt, Long approvalId) {
    }

    /** 报价行（含供应商编号） */
    public record BidRow(
            long id, long inquiryId, long supplierId, String supplierNo,
            int revision, long totalCents, String status,
            String result, String exclusionReason, Long qualificationId) {
    }

    /** 开标行 */
    public record OpeningRow(
            long id, long inquiryId, Long winnerBidId, Long winnerSupplierId,
            Long totalCents, String status, Long approvalId,
            LocalDateTime openedAt, String requestId) {
    }

    /** 原案残损项（询价快照来源） */
    public record CaseItemRow(long id, String iiclCode, String component) {
    }

    private static final RowMapper<SupplierRow> SUPPLIER_MAPPER = (rs, n) -> new SupplierRow(
            rs.getLong("id"), rs.getString("supplier_no"), rs.getString("name"));

    private static final RowMapper<QualificationRow> QUAL_MAPPER = (rs, n) -> new QualificationRow(
            rs.getLong("id"), rs.getLong("supplier_id"), rs.getInt("version"),
            toLdt(rs.getTimestamp("valid_from")), toLdt(rs.getTimestamp("valid_until")),
            rs.getString("status"), toLdt(rs.getTimestamp("revoked_at")));

    private static final RowMapper<InquiryRow> INQUIRY_MAPPER = (rs, n) -> new InquiryRow(
            rs.getLong("id"), rs.getLong("case_id"), rs.getInt("version"),
            toLdt(rs.getTimestamp("deadline")), rs.getString("status"),
            toLdt(rs.getTimestamp("opened_at")),
            rs.getObject("approval_id", Long.class));

    private static final RowMapper<BidRow> BID_MAPPER = (rs, n) -> new BidRow(
            rs.getLong("id"), rs.getLong("inquiry_id"), rs.getLong("supplier_id"),
            rs.getString("supplier_no"), rs.getInt("revision"), rs.getLong("total_cents"),
            rs.getString("status"), rs.getString("result"), rs.getString("exclusion_reason"),
            rs.getObject("qualification_id", Long.class));

    private static final RowMapper<OpeningRow> OPENING_MAPPER = (rs, n) -> new OpeningRow(
            rs.getLong("id"), rs.getLong("inquiry_id"),
            rs.getObject("winner_bid_id", Long.class),
            rs.getObject("winner_supplier_id", Long.class),
            rs.getObject("total_cents", Long.class),
            rs.getString("status"),
            rs.getObject("approval_id", Long.class),
            toLdt(rs.getTimestamp("opened_at")),
            rs.getString("request_id"));

    private static final RowMapper<CaseItemRow> CASE_ITEM_MAPPER = (rs, n) -> new CaseItemRow(
            rs.getLong("id"), rs.getString("iicl_code"), rs.getString("component"));

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    // ==================== 供应商与资格 ====================

    /** 按供应商编号查询 */
    public Optional<SupplierRow> findSupplierByNo(String supplierNo) {
        return jdbc.query("select * from supplier where supplier_no = ?", SUPPLIER_MAPPER, supplierNo)
                .stream().findFirst();
    }

    /** 按主键加行锁查询供应商（资格续期/撤销串行化） */
    public Optional<SupplierRow> lockSupplier(long supplierId) {
        return jdbc.query("select * from supplier where id = ? for update", SUPPLIER_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 按主键查询供应商 */
    public Optional<SupplierRow> findSupplierById(long supplierId) {
        return jdbc.query("select * from supplier where id = ?", SUPPLIER_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 合成工法代码是否存在 */
    public boolean repairMethodExists(String code) {
        Integer count = jdbc.queryForObject("select count(*) from repair_method where code = ?",
                Integer.class, code);
        return count != null && count > 0;
    }

    /** 已转批复的中标供应商案件 ID 列表（撤销时登记风险事件用） */
    public List<Long> listConvertedWinningCaseIds(long supplierId) {
        return jdbc.queryForList(
                "select distinct i.case_id from bid_opening o join inquiry i on i.id = o.inquiry_id"
                        + " where o.winner_supplier_id = ? and o.approval_id is not null",
                Long.class, supplierId);
    }

    /** 按主键查询资格版本 */
    public Optional<QualificationRow> findQualification(long qualificationId) {
        return jdbc.query("select * from supplier_qualification where id = ?", QUAL_MAPPER, qualificationId)
                .stream().findFirst();
    }

    /** 供应商最新资格版本（版本号最大），无则空 */
    public Optional<QualificationRow> findLatestQualification(long supplierId) {
        return jdbc.query("select * from supplier_qualification where supplier_id = ? order by version desc limit 1",
                        QUAL_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 加行锁查询最新资格版本（撤销/开标串行化） */
    public Optional<QualificationRow> findLatestQualificationForUpdate(long supplierId) {
        return jdbc.query(
                        "select * from supplier_qualification where supplier_id = ? order by version desc limit 1 for update",
                        QUAL_MAPPER, supplierId)
                .stream().findFirst();
    }

    /** 资格版本适用工法（代码升序） */
    public List<String> qualificationMethods(long qualificationId) {
        return jdbc.queryForList(
                "select method_code from qualification_method where qualification_id = ? order by method_code",
                String.class, qualificationId);
    }

    /** 追加资格版本及其工法集合，返回新行 ID */
    public long insertQualification(long supplierId, int version, LocalDateTime validFrom,
                                    LocalDateTime validUntil, String requestId, LocalDateTime now,
                                    List<String> methods) {
        jdbc.update("insert into supplier_qualification"
                        + "(supplier_id, version, valid_from, valid_until, status, request_id, created_at)"
                        + " values (?,?,?,?, 'ACTIVE', ?,?)",
                supplierId, version, Timestamp.valueOf(validFrom), Timestamp.valueOf(validUntil),
                requestId, Timestamp.valueOf(now));
        Long id = jdbc.queryForObject(
                "select max(id) from supplier_qualification where supplier_id = ? and version = ?",
                Long.class, supplierId, version);
        long qualificationId = id == null ? 0 : id;
        for (String method : methods) {
            jdbc.update("insert into qualification_method(qualification_id, method_code) values (?,?)",
                    qualificationId, method);
        }
        return qualificationId;
    }

    /** 撤销资格版本（状态置 REVOKED，不删除、不改有效期证据） */
    public void revokeQualification(long qualificationId, LocalDateTime now) {
        jdbc.update("update supplier_qualification set status = 'REVOKED', revoked_at = ? where id = ?",
                Timestamp.valueOf(now), qualificationId);
    }

    /** 该供应商是否存在已转批复（CONVERTED）的中标开标，用于区分撤销与风险事件 */
    public boolean existsConvertedWinning(long supplierId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from bid_opening o join inquiry i on i.id = o.inquiry_id "
                        + "where o.winner_supplier_id = ? and o.approval_id is not null",
                Integer.class, supplierId);
        return count != null && count > 0;
    }

    // ==================== 询价版本 ====================

    /** 按案件与版本号查询询价 */
    public Optional<InquiryRow> findInquiry(long caseId, int version) {
        return jdbc.query("select * from inquiry where case_id = ? and version = ?",
                        INQUIRY_MAPPER, caseId, version)
                .stream().findFirst();
    }

    /** 按主键查询询价 */
    public Optional<InquiryRow> findInquiryById(long inquiryId) {
        return jdbc.query("select * from inquiry where id = ?", INQUIRY_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 按案件与版本号加行锁查询询价（投标/开标/复核串行化） */
    public Optional<InquiryRow> findInquiryForUpdate(long caseId, int version) {
        return jdbc.query("select * from inquiry where case_id = ? and version = ? for update",
                        INQUIRY_MAPPER, caseId, version)
                .stream().findFirst();
    }

    /** 加行锁查询案件最新询价版本，无则空 */
    public Optional<InquiryRow> findLatestInquiryForUpdate(long caseId) {
        return jdbc.query("select * from inquiry where case_id = ? order by version desc limit 1 for update",
                        INQUIRY_MAPPER, caseId)
                .stream().findFirst();
    }

    /** 原案残损项必须属于该案件，且已检验固定 */
    public Optional<CaseItemRow> findCaseItem(long caseId, long itemId) {
        return jdbc.query("select id, iicl_code, component from damage_item where id = ? and case_id = ?",
                        CASE_ITEM_MAPPER, itemId, caseId)
                .stream().findFirst();
    }

    /** 询价固定残损项（按序号）及每项允许工法 */
    public List<InquiryItemView> listInquiryItems(long inquiryId) {
        List<InquiryItemView> items = jdbc.query(
                "select id, item_seq, case_item_id, iicl_code, component from inquiry_item"
                        + " where inquiry_id = ? order by item_seq",
                (rs, n) -> new InquiryItemView(
                        rs.getInt("item_seq"), rs.getLong("case_item_id"),
                        rs.getString("iicl_code"), rs.getString("component"),
                        listItemMethods(rs.getLong("id"))),
                inquiryId);
        return items;
    }

    private List<String> listItemMethods(long inquiryItemId) {
        return jdbc.queryForList(
                "select method_code from inquiry_item_method where inquiry_item_id = ? order by method_code",
                String.class, inquiryItemId);
    }

    /** 发布询价版本：写入版本头、固定项快照与允许工法，返回询价 ID */
    public long insertInquiry(long caseId, int version, LocalDateTime deadline,
                              String requestId, LocalDateTime now, List<InquiryItemView> items) {
        jdbc.update("insert into inquiry(case_id, version, deadline, status, request_id, created_at)"
                        + " values (?,?,?, 'OPEN', ?,?)",
                caseId, version, Timestamp.valueOf(deadline), requestId, Timestamp.valueOf(now));
        Long id = jdbc.queryForObject("select max(id) from inquiry where case_id = ? and version = ?",
                Long.class, caseId, version);
        long inquiryId = id == null ? 0 : id;
        for (InquiryItemView item : items) {
            jdbc.update("insert into inquiry_item(inquiry_id, item_seq, case_item_id, iicl_code, component)"
                            + " values (?,?,?,?,?)",
                    inquiryId, item.itemSeq(), item.caseItemId(), item.iiclCode(), item.component());
            Long itemId = jdbc.queryForObject(
                    "select max(id) from inquiry_item where inquiry_id = ? and item_seq = ?",
                    Long.class, inquiryId, item.itemSeq());
            long inquiryItemId = itemId == null ? 0 : itemId;
            for (String method : item.allowedMethods()) {
                jdbc.update("insert into inquiry_item_method(inquiry_item_id, method_code) values (?,?)",
                        inquiryItemId, method);
            }
        }
        return inquiryId;
    }

    /** 开标冻结询价版本 */
    public void markInquiryOpened(long inquiryId, LocalDateTime now) {
        jdbc.update("update inquiry set status = 'OPENED', opened_at = ? where id = ?",
                Timestamp.valueOf(now), inquiryId);
    }

    /** 双人复核通过，询价转 CONVERTED 并挂接修理批复 */
    public void markInquiryConverted(long inquiryId, long approvalId) {
        jdbc.update("update inquiry set status = 'CONVERTED', approval_id = ? where id = ?",
                approvalId, inquiryId);
    }

    // ==================== 报价 ====================

    /** 加行锁查询某供应商在某询价版本上的当前报价 */
    public Optional<BidRow> findCurrentBidForUpdate(long inquiryId, long supplierId) {
        return jdbc.query("select b.*, s.supplier_no from bid b join supplier s on s.id = b.supplier_id"
                        + " where b.inquiry_id = ? and b.supplier_id = ? and b.status = 'CURRENT' for update",
                        BID_MAPPER, inquiryId, supplierId)
                .stream().findFirst();
    }

    /** 按主键查询报价 */
    public Optional<BidRow> findBidById(long bidId) {
        return jdbc.query("select b.*, s.supplier_no from bid b join supplier s on s.id = b.supplier_id"
                        + " where b.id = ?", BID_MAPPER, bidId)
                .stream().findFirst();
    }

    /** 加行锁锁定询价版本下全部当前报价（开标串行化） */
    public List<BidRow> listCurrentBidsForUpdate(long inquiryId) {
        return jdbc.query("select b.*, s.supplier_no from bid b join supplier s on s.id = b.supplier_id"
                        + " where b.inquiry_id = ? and b.status = 'CURRENT' for update",
                BID_MAPPER, inquiryId);
    }

    /** 某供应商在某询价版本上的全部报价修订（修订号升序） */
    public List<BidRow> listBidRevisions(long inquiryId, long supplierId) {
        return jdbc.query("select b.*, s.supplier_no from bid b join supplier s on s.id = b.supplier_id"
                        + " where b.inquiry_id = ? and b.supplier_id = ? order by b.revision",
                BID_MAPPER, inquiryId, supplierId);
    }

    /** 报价分项（按询价项序号） */
    public List<BidLineView> listBidItems(long bidId) {
        return jdbc.query(
                "select ii.item_seq, bi.method_code, bi.price_cents from bid_item bi"
                        + " join inquiry_item ii on ii.id = bi.inquiry_item_id"
                        + " where bi.bid_id = ? order by ii.item_seq",
                (rs, n) -> new BidLineView(
                        rs.getInt("item_seq"), rs.getString("method_code"), rs.getLong("price_cents")),
                bidId);
    }

    /** 当前报价修订置 SUPERSEDED（成功修订时调用） */
    public void supersedeCurrentBid(long bidId) {
        jdbc.update("update bid set status = 'SUPERSEDED' where id = ? and status = 'CURRENT'", bidId);
    }

    /** 写入新报价修订及分项快照，返回报价 ID */
    public long insertBid(long inquiryId, long supplierId, int revision, long totalCents,
                          String requestId, LocalDateTime now, List<BidLineView> lines) {
        jdbc.update("insert into bid(inquiry_id, supplier_id, revision, total_cents, status, request_id, created_at)"
                        + " values (?,?,?,?,'CURRENT',?,?)",
                inquiryId, supplierId, revision, totalCents, requestId, Timestamp.valueOf(now));
        Long id = jdbc.queryForObject(
                "select max(id) from bid where inquiry_id = ? and supplier_id = ? and revision = ?",
                Long.class, inquiryId, supplierId, revision);
        long bidId = id == null ? 0 : id;
        for (BidLineView line : lines) {
            Long inquiryItemId = jdbc.queryForObject(
                    "select id from inquiry_item where inquiry_id = ? and item_seq = ?",
                    Long.class, inquiryId, line.itemSeq());
            jdbc.update("insert into bid_item(bid_id, inquiry_item_id, method_code, price_cents) values (?,?,?,?)",
                    bidId, inquiryItemId, line.methodCode(), line.priceCents());
        }
        return bidId;
    }

    /** 开标冻结当前报价并写入中标/落标/排除结论与所采用资格版本 */
    public void freezeBid(long bidId, String result, String exclusionReason, Long qualificationId) {
        jdbc.update("update bid set status = 'FROZEN', result = ?, exclusion_reason = ?, qualification_id = ?"
                        + " where id = ?",
                result, exclusionReason, qualificationId, bidId);
    }

    /** 询价版本下全部报价（含历史修订，按供应商编号、修订号） */
    public List<BidRow> listBids(long inquiryId) {
        return jdbc.query("select b.*, s.supplier_no from bid b join supplier s on s.id = b.supplier_id"
                        + " where b.inquiry_id = ? order by s.supplier_no, b.revision",
                BID_MAPPER, inquiryId);
    }

    // ==================== 开标与复核 ====================

    /** 写入首次开标结果 */
    public long insertOpening(long inquiryId, Long winnerBidId, Long winnerSupplierId,
                              Long totalCents, String status, LocalDateTime now, String requestId) {
        jdbc.update("insert into bid_opening"
                        + "(inquiry_id, winner_bid_id, winner_supplier_id, total_cents, status, opened_at, request_id)"
                        + " values (?,?,?,?,?,?,?)",
                inquiryId, winnerBidId, winnerSupplierId, totalCents, status,
                Timestamp.valueOf(now), requestId);
        Long id = jdbc.queryForObject("select max(id) from bid_opening where inquiry_id = ?",
                Long.class, inquiryId);
        return id == null ? 0 : id;
    }

    /** 查询开标结果（每询价版本唯一一行） */
    public Optional<OpeningRow> findOpening(long inquiryId) {
        return jdbc.query("select * from bid_opening where inquiry_id = ?", OPENING_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 加行锁查询开标结果（复核串行化） */
    public Optional<OpeningRow> findOpeningForUpdate(long inquiryId) {
        return jdbc.query("select * from bid_opening where inquiry_id = ? for update",
                        OPENING_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 记录排除行及原因 */
    public void insertExclusion(long openingId, long bidId, long supplierId, String reason) {
        jdbc.update("insert into bid_exclusion(opening_id, bid_id, supplier_id, reason) values (?,?,?,?)",
                openingId, bidId, supplierId, reason);
    }

    /** 开标清单：中标行优先，其余按总价、供应商编号升序（Java 层排序，避免 H2 UNION 排序限制） */
    public List<OpeningBidView> listOpeningBids(long openingId) {
        List<OpeningBidView> rows = jdbc.query(
                "select b.id, s.supplier_no, b.revision, b.total_cents, b.result, b.exclusion_reason "
                        + "from bid_opening o "
                        + "join bid b on b.inquiry_id = o.inquiry_id and b.status = 'FROZEN' "
                        + "join supplier s on s.id = b.supplier_id "
                        + "where o.id = ?",
                (rs, n) -> new OpeningBidView(
                        rs.getLong("id"), rs.getString("supplier_no"), rs.getInt("revision"),
                        rs.getLong("total_cents"), rs.getString("result"), rs.getString("exclusion_reason")),
                openingId);
        rows.sort(Comparator.comparingInt((OpeningBidView v) -> "WINNER".equals(v.result()) ? 0 : 1)
                .thenComparing(OpeningBidView::totalCents)
                .thenComparing(OpeningBidView::supplierNo));
        return rows;
    }

    /** 复核人列表（按复核先后） */
    public List<String> listReviewers(long openingId) {
        return jdbc.queryForList(
                "select reviewer from bid_review where opening_id = ? order by id",
                String.class, openingId);
    }

    /** 该复核人是否已复核 */
    public boolean reviewExists(long openingId, String reviewer) {
        Integer count = jdbc.queryForObject(
                "select count(*) from bid_review where opening_id = ? and reviewer = ?",
                Integer.class, openingId, reviewer);
        return count != null && count > 0;
    }

    /** 写入复核记录（uk_review_reviewer 保证同人不重复） */
    public void insertReview(long openingId, String reviewer, String requestId, LocalDateTime now) {
        jdbc.update("insert into bid_review(opening_id, reviewer, request_id, created_at) values (?,?,?,?)",
                openingId, reviewer, requestId, Timestamp.valueOf(now));
    }

    /** 开标结果挂接修理批复 */
    public void markOpeningConverted(long openingId, long approvalId) {
        jdbc.update("update bid_opening set approval_id = ? where id = ?", approvalId, openingId);
    }

    // ==================== 视图装配 ====================

    /** 供应商视图（含最新资格版本，无资格时 currentQualification=null） */
    public SupplierView toSupplierView(SupplierRow supplier) {
        Optional<QualificationRow> qual = findLatestQualification(supplier.id());
        return new SupplierView(supplier.supplierNo(), supplier.name(),
                qual.map(q -> toQualificationView(supplier.supplierNo(), q)).orElse(null));
    }

    /** 资格版本视图 */
    public QualificationView toQualificationView(String supplierNo, QualificationRow row) {
        return new QualificationView(row.id(), supplierNo, row.version(),
                row.validFrom(), row.validUntil(), row.status(), row.revokedAt(),
                qualificationMethods(row.id()));
    }

    /** 询价版本视图（含固定项与允许工法） */
    public InquiryView toInquiryView(InquiryRow row) {
        return new InquiryView(row.id(), row.caseId(), row.version(), row.deadline(), row.status(),
                row.openedAt(), row.approvalId(), listInquiryItems(row.id()));
    }

    /** 单个报价视图（含分项） */
    public BidView toBidView(BidRow row) {
        return new BidView(row.id(), row.inquiryId(), row.supplierNo(), row.revision(), row.totalCents(),
                row.status(), row.result(), row.exclusionReason(), row.qualificationId(),
                listBidItems(row.id()));
    }
}
