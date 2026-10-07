package com.example.starter.damage.store;

import com.example.starter.damage.domain.InquiryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 密封询价、报价、开标冻结清单与双重复核数据访问。
 * 金额为正整数分；时刻均为 UTC（timestamp）。
 * 询价乐观锁与报价当前版本均通过“条件更新受影响行数”仲裁并发。
 */
@Repository
public class InquiryRepository {

    private static final RowMapper<InquiryRow> INQUIRY_MAPPER = (rs, n) -> new InquiryRow(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getInt("seq"),
            rs.getInt("version"),
            rs.getTimestamp("deadline").toInstant(),
            InquiryStatus.valueOf(rs.getString("status")),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<InquiryItemRow> INQUIRY_ITEM_MAPPER = (rs, n) -> new InquiryItemRow(
            rs.getLong("id"),
            rs.getLong("inquiry_id"),
            rs.getInt("line_no"),
            rs.getLong("damage_item_id"));

    private static final RowMapper<BidRow> BID_MAPPER = (rs, n) -> new BidRow(
            rs.getLong("id"),
            rs.getLong("inquiry_id"),
            rs.getLong("supplier_id"),
            rs.getInt("current_version"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<BidVersionRow> BID_VERSION_MAPPER = (rs, n) -> new BidVersionRow(
            rs.getLong("id"),
            rs.getLong("bid_id"),
            rs.getInt("version"),
            rs.getLong("total_cents"),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getTimestamp("submitted_at").toInstant());

    private static final RowMapper<BidLineRow> BID_LINE_MAPPER = (rs, n) -> new BidLineRow(
            rs.getLong("id"),
            rs.getLong("bid_version_id"),
            rs.getLong("inquiry_item_id"),
            rs.getInt("line_no"),
            rs.getString("method_code"),
            rs.getLong("amount_cents"));

    private static final RowMapper<OpeningRow> OPENING_MAPPER = (rs, n) -> new OpeningRow(
            rs.getLong("id"),
            rs.getLong("inquiry_id"),
            rs.getInt("inquiry_version"),
            rs.getTimestamp("opened_at").toInstant(),
            rs.getString("status"),
            (Long) rs.getObject("winner_supplier_id"),
            (Long) rs.getObject("winner_bid_version_id"),
            (Long) rs.getObject("winner_total_cents"),
            rs.getString("request_id"));

    private static final RowMapper<OpeningEntryRow> OPENING_ENTRY_MAPPER = (rs, n) -> {
        Integer rank = (Integer) rs.getObject("rank_no");
        return new OpeningEntryRow(
                rs.getLong("id"),
                rs.getLong("opening_id"),
                rs.getLong("supplier_id"),
                rs.getLong("bid_version_id"),
                rs.getLong("total_cents"),
                rs.getInt("qualification_version"),
                rs.getString("qualification_status"),
                rs.getTimestamp("qual_valid_from").toInstant(),
                toInstant(rs.getTimestamp("qual_valid_to")),
                rs.getBoolean("valid"),
                rs.getString("exclude_reason"),
                rank);
    };

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, n) -> new ReviewRow(
            rs.getLong("id"),
            rs.getLong("inquiry_id"),
            rs.getLong("opening_id"),
            rs.getString("reviewer"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public InquiryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 询价版本行 */
    public record InquiryRow(
            long id,
            long caseId,
            int seq,
            int version,
            Instant deadline,
            InquiryStatus status,
            String requestId,
            Instant createdAt) {
    }

    /** 询价残损项行 */
    public record InquiryItemRow(long id, long inquiryId, int lineNo, long damageItemId) {
    }

    /** 报价主表行 */
    public record BidRow(
            long id,
            long inquiryId,
            long supplierId,
            int currentVersion,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 报价版本行 */
    public record BidVersionRow(
            long id,
            long bidId,
            int version,
            long totalCents,
            String status,
            String requestId,
            Instant submittedAt) {
    }

    /** 报价明细行 */
    public record BidLineRow(
            long id,
            long bidVersionId,
            long inquiryItemId,
            int lineNo,
            String methodCode,
            long amountCents) {
    }

    /** 开标记录行 */
    public record OpeningRow(
            long id,
            long inquiryId,
            int inquiryVersion,
            Instant openedAt,
            String status,
            Long winnerSupplierId,
            Long winnerBidVersionId,
            Long winnerTotalCents,
            String requestId) {
    }

    /** 开标清单行（资格快照冻结） */
    public record OpeningEntryRow(
            long id,
            long openingId,
            long supplierId,
            long bidVersionId,
            long totalCents,
            int qualificationVersion,
            String qualificationStatus,
            Instant qualValidFrom,
            Instant qualValidTo,
            boolean valid,
            String excludeReason,
            Integer rankNo) {
    }

    /** 复核记录行 */
    public record ReviewRow(
            long id,
            long inquiryId,
            long openingId,
            String reviewer,
            String requestId,
            Instant createdAt) {
    }

    // ---- 询价版本 ----

    /** 发布询价：version=1、status=OPEN，返回新 ID */
    public long insertInquiry(long caseId, int seq, Instant deadline, String requestId, Instant now) {
        jdbc.update("insert into inquiry(case_id, seq, version, deadline, status, request_id, created_at)"
                        + " values (?,?,?,?,?,?,?)",
                caseId, seq, 1, Timestamp.from(deadline), InquiryStatus.OPEN.name(), requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject(
                "select max(id) from inquiry where case_id = ? and seq = ?", Long.class, caseId, seq);
        return id == null ? 0 : id;
    }

    public Optional<InquiryRow> findInquiryById(long inquiryId) {
        return jdbc.query("select * from inquiry where id = ?", INQUIRY_MAPPER, inquiryId)
                .stream().findFirst();
    }

    public Optional<InquiryRow> findInquiryByIdForUpdate(long inquiryId) {
        return jdbc.query("select * from inquiry where id = ? for update", INQUIRY_MAPPER, inquiryId)
                .stream().findFirst();
    }

    /** 案件下最大询价序号，无则 0 */
    public int maxSeq(long caseId) {
        Integer max = jdbc.queryForObject("select coalesce(max(seq), 0) from inquiry where case_id = ?",
                Integer.class, caseId);
        return max == null ? 0 : max;
    }

    public Optional<InquiryRow> findLatestInquiryByCase(long caseId) {
        return jdbc.query("select * from inquiry where case_id = ? order by seq desc", INQUIRY_MAPPER, caseId)
                .stream().findFirst();
    }

    /**
     * 案件是否存在进行中的密封询价（OPEN 投标中或 OPENED 已开标待复核）。
     * NO_AWARD（零有效报价）与 CONVERTED（已转批复）不算进行中。
     */
    public boolean hasOpenOrOpenedInquiry(long caseId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from inquiry where case_id = ? and status in ('OPEN','OPENED')",
                Integer.class, caseId);
        return count != null && count > 0;
    }

    /** 乐观更新：仅当当前 version=expectedVersion 时写入新状态与新版本，返回受影响行数 */
    public int compareAndSetInquiry(long inquiryId, int expectedVersion, InquiryStatus newStatus, int newVersion) {
        return jdbc.update("update inquiry set status = ?, version = ? where id = ? and version = ?",
                newStatus.name(), newVersion, inquiryId, expectedVersion);
    }

    // ---- 询价行与允许工法 ----

    public long insertInquiryItem(long inquiryId, int lineNo, long damageItemId) {
        jdbc.update("insert into inquiry_item(inquiry_id, line_no, damage_item_id) values (?,?,?)",
                inquiryId, lineNo, damageItemId);
        Long id = jdbc.queryForObject(
                "select max(id) from inquiry_item where inquiry_id = ? and line_no = ?",
                Long.class, inquiryId, lineNo);
        return id == null ? 0 : id;
    }

    public void addInquiryItemMethod(long inquiryItemId, String methodCode) {
        jdbc.update("insert into inquiry_item_method(inquiry_item_id, method_code) values (?,?)",
                inquiryItemId, methodCode);
    }

    public List<InquiryItemRow> listInquiryItems(long inquiryId) {
        return jdbc.query("select * from inquiry_item where inquiry_id = ? order by line_no",
                INQUIRY_ITEM_MAPPER, inquiryId);
    }

    public List<String> listInquiryItemMethods(long inquiryItemId) {
        return jdbc.queryForList(
                "select method_code from inquiry_item_method where inquiry_item_id = ? order by method_code",
                String.class, inquiryItemId);
    }

    // ---- 报价 ----

    public long insertBid(long inquiryId, long supplierId, Instant now) {
        jdbc.update("insert into bid(inquiry_id, supplier_id, current_version, status, created_at, updated_at)"
                        + " values (?,?,?,?,?,?)",
                inquiryId, supplierId, 0, "ACTIVE", Timestamp.from(now), Timestamp.from(now));
        Long id = jdbc.queryForObject("select max(id) from bid where inquiry_id = ? and supplier_id = ?",
                Long.class, inquiryId, supplierId);
        return id == null ? 0 : id;
    }

    public Optional<BidRow> findBidForUpdate(long inquiryId, long supplierId) {
        return jdbc.query("select * from bid where inquiry_id = ? and supplier_id = ? for update",
                        BID_MAPPER, inquiryId, supplierId)
                .stream().findFirst();
    }

    public Optional<BidRow> findBid(long inquiryId, long supplierId) {
        return jdbc.query("select * from bid where inquiry_id = ? and supplier_id = ?",
                        BID_MAPPER, inquiryId, supplierId)
                .stream().findFirst();
    }

    /**
     * 条件推进当前报价版本：仅当 status=ACTIVE 且 current_version=expectedVersion 时生效。
     * 首次提交 expectedVersion=0（此时尚无版本行）；开标冻结后 status=FROZEN，更新返回 0 行。
     */
    public int compareAndSetBidVersion(long bidId, int expectedVersion, int newVersion, Instant now) {
        return jdbc.update("update bid set current_version = ?, status = 'ACTIVE', updated_at = ?"
                        + " where id = ? and current_version = ? and status = 'ACTIVE'",
                newVersion, Timestamp.from(now), bidId, expectedVersion);
    }

    /** 开标冻结该询价下全部报价，冻结后不得修订 */
    public void freezeBids(long inquiryId) {
        jdbc.update("update bid set status = 'FROZEN', updated_at = updated_at where inquiry_id = ?", inquiryId);
    }

    public List<BidRow> listBids(long inquiryId) {
        return jdbc.query("select * from bid where inquiry_id = ? order by id", BID_MAPPER, inquiryId);
    }

    // ---- 报价版本与明细 ----

    public long insertBidVersion(long bidId, int version, long totalCents, String requestId, Instant now) {
        jdbc.update("insert into bid_version(bid_id, version, total_cents, status, request_id, submitted_at)"
                        + " values (?,?,?,?,?,?)",
                bidId, version, totalCents, "CURRENT", requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject("select max(id) from bid_version where bid_id = ? and version = ?",
                Long.class, bidId, version);
        return id == null ? 0 : id;
    }

    public void supersedeBidVersions(long bidId) {
        jdbc.update("update bid_version set status = 'SUPERSEDED' where bid_id = ? and status = 'CURRENT'", bidId);
    }

    public void insertBidLine(long bidVersionId, long inquiryItemId, int lineNo, String methodCode, long amountCents) {
        jdbc.update("insert into bid_line(bid_version_id, inquiry_item_id, line_no, method_code, amount_cents)"
                        + " values (?,?,?,?,?)",
                bidVersionId, inquiryItemId, lineNo, methodCode, amountCents);
    }

    public List<BidVersionRow> listBidVersions(long bidId) {
        return jdbc.query("select * from bid_version where bid_id = ? order by version",
                BID_VERSION_MAPPER, bidId);
    }

    /** 按报价版本 ID 查询（开标清单展示冻结版本号） */
    public Optional<BidVersionRow> findBidVersionById(long bidVersionId) {
        return jdbc.query("select * from bid_version where id = ?", BID_VERSION_MAPPER, bidVersionId)
                .stream().findFirst();
    }

    public List<BidLineRow> listBidLines(long bidVersionId) {
        return jdbc.query("select * from bid_line where bid_version_id = ? order by line_no",
                BID_LINE_MAPPER, bidVersionId);
    }

    // ---- 开标 ----

    /** 插入开标冻结记录；无中标时 winner* 传 null，返回开标 ID */
    public long insertOpening(long inquiryId, int inquiryVersion, Instant openedAt, String status,
                              Long winnerSupplierId, Long winnerBidVersionId, Long winnerTotalCents,
                              String requestId) {
        jdbc.update("insert into bid_opening(inquiry_id, inquiry_version, opened_at, status,"
                        + " winner_supplier_id, winner_bid_version_id, winner_total_cents, request_id)"
                        + " values (?,?,?,?,?,?,?,?)",
                inquiryId, inquiryVersion, Timestamp.from(openedAt), status,
                winnerSupplierId, winnerBidVersionId, winnerTotalCents, requestId);
        Long id = jdbc.queryForObject("select max(id) from bid_opening where inquiry_id = ?", Long.class, inquiryId);
        return id == null ? 0 : id;
    }

    public Optional<OpeningRow> findOpeningByInquiry(long inquiryId) {
        return jdbc.query("select * from bid_opening where inquiry_id = ?", OPENING_MAPPER, inquiryId)
                .stream().findFirst();
    }

    public Optional<OpeningRow> findOpeningByInquiryForUpdate(long inquiryId) {
        return jdbc.query("select * from bid_opening where inquiry_id = ? for update", OPENING_MAPPER, inquiryId)
                .stream().findFirst();
    }

    public Optional<OpeningRow> findOpeningByIdForUpdate(long openingId) {
        return jdbc.query("select * from bid_opening where id = ? for update", OPENING_MAPPER, openingId)
                .stream().findFirst();
    }

    /** 按开标记录 ID 查询（修理变更单溯源原中标证据） */
    public Optional<OpeningRow> findOpeningById(long openingId) {
        return jdbc.query("select * from bid_opening where id = ?", OPENING_MAPPER, openingId)
                .stream().findFirst();
    }

    /** 插入开标清单冻结行（含资格快照与排除原因/排名） */
    public void insertOpeningEntry(long openingId, long supplierId, long bidVersionId, long totalCents,
                                   int qualificationVersion, String qualificationStatus,
                                   Instant qualValidFrom, Instant qualValidTo,
                                   boolean valid, String excludeReason, Integer rankNo) {
        jdbc.update("insert into bid_opening_entry(opening_id, supplier_id, bid_version_id, total_cents,"
                        + " qualification_version, qualification_status, qual_valid_from, qual_valid_to,"
                        + " valid, exclude_reason, rank_no) values (?,?,?,?,?,?,?,?,?,?,?)",
                openingId, supplierId, bidVersionId, totalCents, qualificationVersion, qualificationStatus,
                Timestamp.from(qualValidFrom), qualValidTo == null ? null : Timestamp.from(qualValidTo),
                valid, excludeReason, rankNo);
    }

    public List<OpeningEntryRow> listOpeningEntries(long openingId) {
        return jdbc.query("select * from bid_opening_entry where opening_id = ? order by id",
                OPENING_ENTRY_MAPPER, openingId);
    }

    // ---- 复核 ----

    public void insertReview(long inquiryId, long openingId, String reviewer, String requestId, Instant now) {
        jdbc.update("insert into bid_review(inquiry_id, opening_id, reviewer, request_id, created_at)"
                        + " values (?,?,?,?,?)",
                inquiryId, openingId, reviewer, requestId, Timestamp.from(now));
    }

    public List<ReviewRow> listReviews(long openingId) {
        return jdbc.query("select * from bid_review where opening_id = ? order by id", REVIEW_MAPPER, openingId);
    }

    /** 供应商已中标且开标已转为修理批复的关联（批复后撤销资格生成风险事件用） */
    public record ConvertedWinRow(long caseId, long inquiryId, long openingId) {
    }

    /**
     * 列出该供应商中标、且已产生询价来源修理批复的开标关联。
     * 开标/批复证据为历史快照，撤销资格不回写，仅据此在案件上记录风险事件。
     */
    public List<ConvertedWinRow> listConvertedWinsForSupplier(long supplierId) {
        return jdbc.query("select i.case_id, i.id as inquiry_id, o.id as opening_id"
                        + " from bid_opening o"
                        + " join inquiry i on i.id = o.inquiry_id"
                        + " join repair_approval a on a.opening_id = o.id"
                        + " where o.winner_supplier_id = ?",
                (rs, n) -> new ConvertedWinRow(
                        rs.getLong("case_id"), rs.getLong("inquiry_id"), rs.getLong("opening_id")),
                supplierId);
    }
}
