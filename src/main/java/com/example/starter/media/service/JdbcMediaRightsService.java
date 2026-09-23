package com.example.starter.media.service;

import com.example.starter.media.domain.GrantStatus;
import com.example.starter.media.domain.HalfOpenIntervals;
import com.example.starter.media.domain.LicenseMode;
import com.example.starter.media.domain.ScheduleItemStatus;
import com.example.starter.media.repo.GrantRepository;
import com.example.starter.media.repo.GrantVersionRow;
import com.example.starter.media.repo.IdempotencyRepository;
import com.example.starter.media.repo.IdempotencyRepository.RecordedRequest;
import com.example.starter.media.repo.ScheduleRepository;
import com.example.starter.media.web.ApiException;
import com.example.starter.media.web.dto.CorrectGrantRequest;
import com.example.starter.media.web.dto.CreateGrantRequest;
import com.example.starter.media.web.dto.EvidenceItemView;
import com.example.starter.media.web.dto.GrantView;
import com.example.starter.media.web.dto.PlayReceiptRequest;
import com.example.starter.media.web.dto.PlayabilityCheckRequest;
import com.example.starter.media.web.dto.ReceiptView;
import com.example.starter.media.web.dto.RevokeGrantRequest;
import com.example.starter.media.web.dto.ScheduleItemView;
import com.example.starter.media.web.dto.ScheduleSlotDto;
import com.example.starter.media.web.dto.ScheduleView;
import com.example.starter.media.web.dto.SubmitScheduleRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 媒体版权窗口与播出授权校验的 JDBC 事务实现。
 *
 * <p>所有写请求首先以 requestId 插入幂等占位行（唯一主键使同键并发在数据库层排队），
 * 再进入 内容+地区+平台 键空间行锁；键空间锁串行化同键的授权与排期写事务，
 * 保证窗口独占冲突检测、撤销/排期竞态在 READ_COMMITTED 下仍然线性一致。
 * 业务失败回滚时占位行一并回滚；同键同参回放首次结果，异参返回 409。</p>
 */
@Service
public class JdbcMediaRightsService implements MediaRightsService {

    private static final String OP_CREATE_GRANT = "CREATE_GRANT";
    private static final String OP_CORRECT_GRANT = "CORRECT_GRANT";
    private static final String OP_REVOKE_GRANT = "REVOKE_GRANT";
    private static final String OP_SUBMIT_SCHEDULE = "SUBMIT_SCHEDULE";
    private static final String OP_PLAY_RECEIPT = "PLAY_RECEIPT";

    private final GrantRepository grantRepo;
    private final ScheduleRepository scheduleRepo;
    private final IdempotencyRepository idemRepo;
    private final Fingerprinter fingerprinter;
    private final ApplicationClock clock;
    private final ObjectMapper objectMapper;

    public JdbcMediaRightsService(GrantRepository grantRepo,
                                  ScheduleRepository scheduleRepo,
                                  IdempotencyRepository idemRepo,
                                  Fingerprinter fingerprinter,
                                  ApplicationClock clock,
                                  ObjectMapper objectMapper) {
        this.grantRepo = grantRepo;
        this.scheduleRepo = scheduleRepo;
        this.idemRepo = idemRepo;
        this.fingerprinter = fingerprinter;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ServiceResult<GrantView> createGrant(CreateGrantRequest req) {
        validateWindow(req.windowStart(), req.windowEnd());
        String fp = fingerprinter.fingerprint(OP_CREATE_GRANT, Map.of(
                "assetId", req.assetId(),
                "region", req.region(),
                "platform", req.platform(),
                "windowStart", req.windowStart().toString(),
                "windowEnd", req.windowEnd().toString(),
                "mode", req.mode().name()
        ));
        var replay = acquireOrReplay(req.requestId(), OP_CREATE_GRANT, fp, GrantView.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        grantRepo.lockKey(req.assetId(), req.region(), req.platform());
        assertNoOverlap(req.assetId(), req.region(), req.platform(),
                req.windowStart(), req.windowEnd(), req.mode(), -1L);

        Instant now = clock.now();
        long grantId = grantRepo.createGrant(req.assetId(), req.region(), req.platform(), now);
        grantRepo.addVersion(grantId, 1, req.assetId(), req.region(), req.platform(),
                req.windowStart(), req.windowEnd(), req.mode(), GrantStatus.ACTIVE, req.requestId(), now);
        GrantView view = latestView(grantId);
        return finish(req.requestId(), 201, view);
    }

    @Override
    @Transactional
    public ServiceResult<GrantView> correctGrant(String grantIdStr, CorrectGrantRequest req) {
        long grantId = parseGrantId(grantIdStr);
        validateWindow(req.windowStart(), req.windowEnd());
        String[] key = requireGrantKey(grantId);
        String fp = fingerprinter.fingerprint(OP_CORRECT_GRANT, Map.of(
                "grantId", grantId,
                "windowStart", req.windowStart().toString(),
                "windowEnd", req.windowEnd().toString(),
                "mode", req.mode().name(),
                "expectedVersionNo", req.expectedVersionNo()
        ));
        var replay = acquireOrReplay(req.requestId(), OP_CORRECT_GRANT, fp, GrantView.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        grantRepo.lockKey(key[0], key[1], key[2]);
        GrantVersionRow active = grantRepo.findActiveRowForUpdate(grantId);
        if (active == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_NOT_ACTIVE", "授权当前没有可更正的有效版本");
        }
        if (active.versionNo() != req.expectedVersionNo()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "预期版本 " + req.expectedVersionNo() + " 与当前版本 " + active.versionNo() + " 不一致");
        }
        assertNoOverlap(key[0], key[1], key[2],
                req.windowStart(), req.windowEnd(), req.mode(), grantId);

        Instant now = clock.now();
        grantRepo.markSuperseded(grantId, active.versionNo());
        grantRepo.addVersion(grantId, active.versionNo() + 1, key[0], key[1], key[2],
                req.windowStart(), req.windowEnd(), req.mode(), GrantStatus.ACTIVE, req.requestId(), now);
        GrantView view = latestView(grantId);
        return finish(req.requestId(), 200, view);
    }

    @Override
    @Transactional
    public ServiceResult<GrantView> revokeGrant(String grantIdStr, RevokeGrantRequest req) {
        long grantId = parseGrantId(grantIdStr);
        String[] key = requireGrantKey(grantId);
        String fp = fingerprinter.fingerprint(OP_REVOKE_GRANT, Map.of(
                "grantId", grantId,
                "expectedVersionNo", req.expectedVersionNo() == null ? -1 : req.expectedVersionNo()
        ));
        var replayRevoke = acquireOrReplay(req.requestId(), OP_REVOKE_GRANT, fp, GrantView.class);
        if (replayRevoke.isPresent()) {
            return replayRevoke.get();
        }

        grantRepo.lockKey(key[0], key[1], key[2]);
        GrantVersionRow active = grantRepo.findActiveRowForUpdate(grantId);
        if (active == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GRANT_NOT_ACTIVE", "授权已被撤销或无有效版本");
        }
        if (req.expectedVersionNo() != null && active.versionNo() != req.expectedVersionNo()) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "预期版本 " + req.expectedVersionNo() + " 与当前版本 " + active.versionNo() + " 不一致");
        }

        Instant now = clock.now();
        grantRepo.markRevoked(grantId, active.versionNo(), req.requestId(), now);
        // 仅阻止尚未开始（slot_start > 撤销时刻）且仍为 BOUND 的时段；PLAYED 证据冻结。
        scheduleRepo.markBlockedByVersion(active.rowId(), now, now);

        GrantView view = grantRepo.findHistory(grantId).stream()
                .filter(v -> v.versionNo() == active.versionNo())
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "撤销后读取版本失败"));
        return finish(req.requestId(), 200, view);
    }

    @Override
    public GrantView getGrant(String grantIdStr) {
        long grantId = parseGrantId(grantIdStr);
        GrantView view = latestView(grantId);
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "授权不存在");
        }
        return view;
    }

    @Override
    public List<GrantView> listGrantHistory(String grantIdStr) {
        long grantId = parseGrantId(grantIdStr);
        if (!grantRepo.grantExists(grantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "授权不存在");
        }
        return grantRepo.findHistory(grantId);
    }

    @Override
    @Transactional
    public ServiceResult<ScheduleView> submitSchedule(SubmitScheduleRequest req) {
        for (ScheduleSlotDto slot : req.slots()) {
            validateWindow(slot.slotStart(), slot.slotEnd());
        }
        String fp = fingerprinter.fingerprint(OP_SUBMIT_SCHEDULE, Map.of(
                "scheduleId", req.scheduleId(),
                "slots", req.slots().stream().map(s -> Map.of(
                        "assetId", s.assetId(),
                        "region", s.region(),
                        "platform", s.platform(),
                        "slotStart", s.slotStart().toString(),
                        "slotEnd", s.slotEnd().toString()
                )).toList()
        ));
        var replaySchedule = acquireOrReplay(req.requestId(), OP_SUBMIT_SCHEDULE, fp, ScheduleView.class);
        if (replaySchedule.isPresent()) {
            return replaySchedule.get();
        }

        // 按确定顺序锁定本排期涉及的全部键空间，避免与授权写事务及多键排期死锁。
        List<String[]> keys = req.slots().stream()
                .map(s -> new String[]{s.assetId(), s.region(), s.platform()})
                .distinct()
                .sorted(Comparator.comparing((String[] k) -> k[0])
                        .thenComparing(k -> k[1]).thenComparing(k -> k[2]))
                .toList();
        for (String[] k : keys) {
            grantRepo.lockKey(k[0], k[1], k[2]);
        }

        if (scheduleRepo.scheduleExists(req.scheduleId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_EXISTS", "排期标识已存在");
        }

        Instant now = clock.now();
        List<ScheduleSlotBinding> bindings = new ArrayList<>();
        int itemNo = 0;
        for (ScheduleSlotDto slot : req.slots()) {
            itemNo++;
            GrantVersionRow covering = pickCoveringVersion(slot);
            if (covering == null) {
                // 任一时段无完整覆盖授权：抛错回滚整笔事务，零写入。
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_NOT_COVERED",
                        "第 " + itemNo + " 个时段没有被当时有效的授权版本完整覆盖");
            }
            bindings.add(new ScheduleSlotBinding(itemNo, slot, covering));
        }

        scheduleRepo.insertSchedule(req.scheduleId(), 1, req.requestId(), now);
        List<ScheduleItemView> itemViews = new ArrayList<>();
        for (ScheduleSlotBinding b : bindings) {
            scheduleRepo.insertItem(req.scheduleId(), b.itemNo(),
                    b.slot().assetId(), b.slot().region(), b.slot().platform(),
                    b.slot().slotStart(), b.slot().slotEnd(),
                    b.version(), ScheduleItemStatus.BOUND, req.requestId(), now);
            itemViews.add(new ScheduleItemView(
                    b.itemNo(), b.slot().assetId(), b.slot().region(), b.slot().platform(),
                    b.slot().slotStart(), b.slot().slotEnd(),
                    b.version().rowId(), b.version().versionNo(), ScheduleItemStatus.BOUND, null));
        }
        ScheduleView view = new ScheduleView(req.scheduleId(), 1, "COMMITTED", itemViews);
        return finish(req.requestId(), 201, view);
    }

    @Override
    public ScheduleView getSchedule(String scheduleId) {
        ScheduleView view = scheduleRepo.findSchedule(scheduleId);
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "排期不存在");
        }
        return view;
    }

    @Override
    @Transactional
    public ServiceResult<ReceiptView> markPlayed(PlayReceiptRequest req) {
        String fp = fingerprinter.fingerprint(OP_PLAY_RECEIPT, Map.of(
                "scheduleId", req.scheduleId(),
                "itemNo", req.itemNo()
        ));
        var replayReceipt = acquireOrReplay(req.requestId(), OP_PLAY_RECEIPT, fp, ReceiptView.class);
        if (replayReceipt.isPresent()) {
            return replayReceipt.get();
        }

        if (!scheduleRepo.scheduleExists(req.scheduleId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "排期不存在");
        }
        // 行锁串行化同一时段的并发回执。
        ScheduleItemView item = scheduleRepo.findItemForUpdate(req.scheduleId(), req.itemNo());
        if (item == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "排期时段不存在");
        }
        if (item.status() == ScheduleItemStatus.PLAYED) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_ALREADY_PLAYED", "该时段已完成播出");
        }
        if (item.status() == ScheduleItemStatus.BLOCKED) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_BLOCKED", "授权已撤销且该时段尚未开始，禁止播出");
        }

        Instant playedAt = clock.now();
        scheduleRepo.markPlayed(req.scheduleId(), req.itemNo(), playedAt, playedAt);
        Integer scheduleVersionNo = scheduleRepo.findScheduleVersionNo(req.scheduleId());
        ReceiptView view = new ReceiptView(
                req.scheduleId(), scheduleVersionNo, req.itemNo(),
                item.slotStart(), item.slotEnd(), playedAt,
                item.boundVersionId(), item.boundVersionNo(), ScheduleItemStatus.PLAYED);
        return finish(req.requestId(), 200, view);
    }

    @Override
    public boolean playable(PlayabilityCheckRequest req) {
        return grantRepo.findVersionsByKey(req.assetId(), req.region(), req.platform()).stream()
                .anyMatch(v -> HalfOpenIntervals.contains(v.windowStart(), v.windowEnd(), req.at()));
    }

    @Override
    public List<EvidenceItemView> listScheduleEvidence(String scheduleId) {
        if (!scheduleRepo.scheduleExists(scheduleId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "排期不存在");
        }
        return scheduleRepo.findEvidenceBySchedule(scheduleId);
    }

    @Override
    public List<EvidenceItemView> listGrantEvidence(String grantIdStr) {
        long grantId = parseGrantId(grantIdStr);
        if (!grantRepo.grantExists(grantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "授权不存在");
        }
        return scheduleRepo.findEvidenceByGrant(grantId);
    }

    private record ScheduleSlotBinding(int itemNo, ScheduleSlotDto slot, GrantVersionRow version) {
    }

    private GrantVersionRow pickCoveringVersion(ScheduleSlotDto slot) {
        return grantRepo.findActiveRowsByKey(slot.assetId(), slot.region(), slot.platform()).stream()
                .filter(v -> HalfOpenIntervals.covers(
                        v.windowStart(), v.windowEnd(), slot.slotStart(), slot.slotEnd()))
                .min(Comparator.comparingLong(GrantVersionRow::grantId)
                        .thenComparingInt(GrantVersionRow::versionNo))
                .orElse(null);
    }

    private void assertNoOverlap(String assetId, String region, String platform,
                                 Instant windowStart, Instant windowEnd, LicenseMode mode, long excludeGrantId) {
        for (GrantVersionRow existing : grantRepo.findActiveRowsByKey(assetId, region, platform)) {
            if (existing.grantId() == excludeGrantId) {
                continue;
            }
            boolean anyExclusive = mode == LicenseMode.EXCLUSIVE || existing.mode() == LicenseMode.EXCLUSIVE;
            if (anyExclusive && HalfOpenIntervals.positiveOverlap(
                    existing.windowStart(), existing.windowEnd(), windowStart, windowEnd)) {
                throw new ApiException(HttpStatus.CONFLICT, "GRANT_WINDOW_CONFLICT",
                        "授权窗口与现有有效授权存在正长度交集且任一授权为独占");
            }
        }
    }

    private String[] requireGrantKey(long grantId) {
        String[] key = grantRepo.findGrantKey(grantId);
        if (key == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "授权不存在");
        }
        return key;
    }

    private long parseGrantId(String grantIdStr) {
        try {
            long id = Long.parseLong(grantIdStr);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND", "授权不存在");
        }
    }

    private GrantView latestView(long grantId) {
        List<GrantView> history = grantRepo.findHistory(grantId);
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WINDOW", "半开窗口要求 start < end 且均非空");
        }
    }

    /**
     * 占用幂等键；同 requestId 同参数返回首次结果（非空 Optional），异参抛 409。
     * 占位行的唯一约束还会让并发同键请求在数据库层排队。
     */
    private <T> java.util.Optional<ServiceResult<T>> acquireOrReplay(
            String requestId, String operation, String fingerprint, Class<T> bodyType) {
        if (idemRepo.acquire(requestId, operation, fingerprint, clock.now())) {
            return java.util.Optional.empty();
        }
        RecordedRequest recorded = idemRepo.find(requestId);
        if (recorded != null && recorded.operation().equals(operation)
                && recorded.fingerprint().equals(fingerprint)) {
            try {
                T body = objectMapper.readValue(recorded.responseBody(), bodyType);
                return java.util.Optional.of(ServiceResult.of(body, recorded.statusCode()));
            } catch (Exception ex) {
                throw new IllegalStateException("回放幂等响应失败", ex);
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "requestId 已用于不同参数的请求");
    }

    /**
     * 业务成功后回填首次响应并返回带状态码的结果。
     */
    private <T> ServiceResult<T> finish(String requestId, int statusCode, T body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化幂等响应失败", ex);
        }
        idemRepo.complete(requestId, statusCode, json);
        return ServiceResult.of(body, statusCode);
    }
}
