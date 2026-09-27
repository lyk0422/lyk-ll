package com.example.starter.damage.store;

import com.example.starter.damage.domain.BidStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 密封报价、开标清单与中标凭证数据访问。
 * 一供应商一询价版本仅一个 CURRENT 报价，修订以新行保留全部历史。
 */
@Repository
public class BidRepository {

    private static final RowMapper<BidRow> BID_MAPPER = (rs, n) -> new BidRow(
            rs.getLong("id"),
            rs.getLong("inquiry_id"),
            rs.getLong("supplier_id"),
            rs.getInt("revision"),
            rs.getLong("total_amount_cents"),
            BidStatus.valueOf(rs.getString("status")),
            rs.getString("exclude_reason"));

    private static final RowMapper<BidItemRow> BID_ITEM_MAPPER = (rs, n) -> new BidItemRow(
            rs.getLong("id"),
            rs.getLong("bid_id"),
            rs.getLong("inquiry_item_id"),
            rs.getString("method_code"),
            rs.getLong("amount_cents"));

    private static final RowMapper<OpeningRow> OPENING_MAPPER = (rs, n) -> new OpeningRow(
            rs.getLong("id"),
            rs.getLong("inquiry_id"),
            rs.getTimestamp("opened_at").toLocalDateTime(),
            (Long) rs.getObject("winner_bid_id"),
            rs.getString("winner_supplier_code"));

    private static final RowMapper<OpeningLineRow> LINE_MAPPER = (rs, n) -> new OpeningLineRow(
            rs.getLong("id"),
            rs.getLong("opening_id"),
            rs.getLong("bid_id"),
            rs.getLong("supplier_id"),
            rs.getString("supplier_code"),
            rs.getInt("revision"),
            rs.getLong("total_amount_cents"),
            rs.getBoolean("qualified"),
            (Integer) rs.getObject("qualification_version"),
            rs.getString("exclude_reason"),
            (Integer) rs.getObject("rank_no"));

    private static final RowMapper<AwardRow> AWARD_MAPPER = (rs, n) -> new AwardRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getLong("inquiry_id"),
            rs.getLong("opening_id"),
            rs.getLong("winner_bid_id"),
            rs.getLong("supplier_id"),
            rs.getString("supplier_code"),
            rs.getInt("qualification_version"),
            rs.getTimestamp("qual_valid_from").toLocalDateTime(),
            rs.getTimestamp("qual_valid_to").toLocalDateTime(),
            rs.getString("reviewer1"),
            rs.getString("reviewer2"),
            rs.getLong("approval_id"),
            rs.getString("request_id"));

    private final JdbcTemplate jdbc;

    public BidRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 报价版本行 */
    public record BidRow(long id, long inquiryId, long supplierId, int revision, long totalAmountCents,
                        BidStatus status, String excludeReason) {
    }

    /** 报价逐项明细行 */
    public record BidItemRow(long id, long bidId, long inquiryItemId, String methodCode, long amountCents) {
    }

    /** 开标结果行；winnerBidId 为 null 表示零有效报价 */
    public record OpeningRow(long id, long inquiryId, LocalDateTime openedAt, Long winnerBidId,
                             String winnerSupplierCode) {
    }

    /** 开标清单行（全部当前报价冻结留痕） */
    public record OpeningLineRow(long id, long openingId, long bidId, long supplierId, String supplierCode,
                                 int revision, long totalAmountCents, boolean qualified,
                                 Integer qualificationVersion, String excludeReason, Integer rankNo) {
    }

    /** 中标转批复凭证行（冻结资格版本快照） */
    public record AwardRow(long id, long caseId, long inquiryId, long openingId, long winnerBidId,
                           long supplierId, String supplierCode, int qualificationVersion,
                           LocalDateTime qualValidFrom, LocalDateTime qualValidTo,
                           String reviewer1, String reviewer2, long approvalId, String requestId) {
    }

    /** 将供应商在本询价的当前报价置 SUPERSEDED（修订前调用） */
    public void supersedeCurrent(long inquiryId, long supplierId) {
        jdbc.update("update bid set status = 'SUPERSEDED' where inquiry_id = ? and supplier_id = ?"
                        + " and status = 'CURRENT'",
                inquiryId, supplierId);
    }

    /** 将整个询价版本上全部当前报价置 SUPERSEDED（新版本发布取代旧 OPEN 版本时调用） */
    public void supersedeAllCurrent(long inquiryId) {
        jdbc.update("update bid set status = 'SUPERSEDED' where inquiry_id = ? and status = 'CURRENT'",
                inquiryId);
    }

    /** 新增报价行（状态由调用方指定，正常投标为 CURRENT） */
    public long insertBid(long inquiryId, long supplierId, int revision, long totalAmountCents,
                          BidStatus status, String requestId, LocalDateTime now) {
        jdbc.update("insert into bid(inquiry_id, supplier_id, revision, total_amount_cents, status, request_id, created_at)"
                        + " values (?,?,?,?,?,?,?)",
                inquiryId, supplierId, revision, totalAmountCents, status.name(), requestId, now);
        return jdbc.queryForObject("select id from bid where inquiry_id = ? and supplier_id = ? and revision = ?",
                Long.class, inquiryId, supplierId, revision);
    }

    /** 写入报价逐项明细 */
    public void insertBidItem(long bidId, long inquiryItemId, String methodCode, long amountCents) {
        jdbc.update("insert into bid_item(bid_id, inquiry_item_id, method_code, amount_cents) values (?,?,?,?)",
                bidId, inquiryItemId, methodCode, amountCents);
    }

    /** 供应商在本询价版本的当前报价，无则空 */
    public Optional<BidRow> findCurrentBid(long inquiryId, long supplierId) {
        return jdbc.query("select * from bid where inquiry_id = ? and supplier_id = ? and status = 'CURRENT'",
                        BID_MAPPER, inquiryId, supplierId)
                .stream().findFirst();
    }

    /** 按 ID 加行锁查询报价 */
    public Optional<BidRow> findBidByIdForUpdate(long bidId) {
        return jdbc.query("select * from bid where id = ? for update", BID_MAPPER, bidId)
                .stream().findFirst();
    }

    /** 按 ID 查询报价（只读场景） */
    public Optional<BidRow> findBidById(long bidId) {
        return jdbc.query("select * from bid where id = ?", BID_MAPPER, bidId)
                .stream().findFirst();
    }

    /** 报价明细（按询价项序号排序） */
    public List<BidItemRow> listBidItems(long bidId) {
        return jdbc.query("select bi.* from bid_item bi join inquiry_item ii on bi.inquiry_item_id = ii.id"
                        + " where bi.bid_id = ? order by ii.seq_no",
                BID_ITEM_MAPPER, bidId);
    }

    /** 开标时冻结全部当前报价：按状态与排除原因原地更新 */
    public int freezeCurrentBids(long inquiryId, long supplierId, BidStatus status, String excludeReason) {
        return jdbc.update("update bid set status = ?, exclude_reason = ?"
                        + " where inquiry_id = ? and supplier_id = ? and status = 'CURRENT'",
                status.name(), excludeReason, inquiryId, supplierId);
    }

    /** 本询价全部报价（按修订顺序，供查询与历史复算） */
    public List<BidRow> listBids(long inquiryId) {
        return jdbc.query("select * from bid where inquiry_id = ? order by supplier_id, revision",
                BID_MAPPER, inquiryId);
    }

    /** 插入开标结果；同一询价重复插入由唯一索引仲裁 */
    public long insertOpening(long inquiryId, LocalDateTime openedAt, Long winnerBidId,
                              String winnerSupplierCode, String requestId, LocalDateTime now) {
        jdbc.update("insert into bid_opening(inquiry_id, opened_at, winner_bid_id, winner_supplier_code,"
                        + " request_id, created_at) values (?,?,?,?,?,?)",
                inquiryId, openedAt, winnerBidId, winnerSupplierCode, requestId, now);
        return jdbc.queryForObject("select id from bid_opening where inquiry_id = ?", Long.class, inquiryId);
    }

    /** 查询开标结果（重复开标直接返回首次） */
    public Optional<OpeningRow> findOpening(long inquiryId) {
        return jdbc.query("select * from bid_opening where inquiry_id = ?", OPENING_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 加行锁查询开标结果（双人复核前使用） */
    public Optional<OpeningRow> findOpeningForUpdate(long inquiryId) {
        return jdbc.query("select * from bid_opening where inquiry_id = ? for update", OPENING_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 写入开标清单单行（含开标时刻判定有效的资格版本号，被排除项传 null） */
    public long insertOpeningLine(long openingId, long bidId, long supplierId, String supplierCode, int revision,
                                  long totalAmountCents, boolean qualified, Integer qualificationVersion,
                                  String excludeReason, Integer rankNo) {
        jdbc.update("insert into bid_opening_line(opening_id, bid_id, supplier_id, supplier_code, revision,"
                        + " total_amount_cents, qualified, qualification_version, exclude_reason, rank_no)"
                        + " values (?,?,?,?,?,?,?,?,?,?)",
                openingId, bidId, supplierId, supplierCode, revision, totalAmountCents,
                qualified, qualificationVersion, excludeReason, rankNo);
        return jdbc.queryForObject("select max(id) from bid_opening_line where opening_id = ?",
                Long.class, openingId);
    }

    /** 按报价 ID 查询其所在开标清单行（双人复核时取开标冻结的资格版本号） */
    public Optional<OpeningLineRow> findOpeningLineByBid(long bidId) {
        return jdbc.query("select * from bid_opening_line where bid_id = ?", LINE_MAPPER, bidId)
                .stream().findFirst();
    }

    /** 开标清单（按排名输出，被排除项排在末尾） */
    public List<OpeningLineRow> listOpeningLines(long openingId) {
        return jdbc.query("select * from bid_opening_line where opening_id = ?"
                        + " order by case when rank_no is null then 1 else 0 end, rank_no, supplier_code",
                LINE_MAPPER, openingId);
    }

    /** 插入中标转批复凭证 */
    public long insertAward(AwardRow row, LocalDateTime now) {
        jdbc.update("insert into repair_award(case_id, inquiry_id, opening_id, winner_bid_id, supplier_id,"
                        + " supplier_code, qualification_version, qual_valid_from, qual_valid_to, reviewer1,"
                        + " reviewer2, approval_id, request_id, created_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.caseId(), row.inquiryId(), row.openingId(), row.winnerBidId(), row.supplierId(),
                row.supplierCode(), row.qualificationVersion(), row.qualValidFrom(), row.qualValidTo(),
                row.reviewer1(), row.reviewer2(), row.approvalId(), row.requestId(), now);
        return jdbc.queryForObject("select id from repair_award where case_id = ?", Long.class, row.caseId());
    }

    /** 按案件查询中标凭证 */
    public Optional<AwardRow> findAwardByCase(long caseId) {
        return jdbc.query("select * from repair_award where case_id = ?", AWARD_MAPPER, caseId)
                .stream().findFirst();
    }

    /** 按询价查询中标凭证 */
    public Optional<AwardRow> findAwardByInquiry(long inquiryId) {
        return jdbc.query("select * from repair_award where inquiry_id = ?", AWARD_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 按供应商查询全部中标凭证（批复后撤销资格时定位风险事件案件） */
    public List<AwardRow> findAwardsBySupplier(long supplierId) {
        return jdbc.query("select * from repair_award where supplier_id = ? order by id",
                AWARD_MAPPER, supplierId);
    }
}
