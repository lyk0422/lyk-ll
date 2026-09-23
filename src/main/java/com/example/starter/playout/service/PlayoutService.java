package com.example.starter.playout.service;

import com.example.starter.playout.PlayoutException;
import com.example.starter.playout.adapter.PlayoutAdapter;
import com.example.starter.playout.adapter.PlayoutCommand;
import com.example.starter.playout.clock.SyntheticClock;
import com.example.starter.playout.domain.AsRunRow;
import com.example.starter.playout.domain.FingerprintRow;
import com.example.starter.playout.domain.LiveInsertRow;
import com.example.starter.playout.domain.PlaylistItemRow;
import com.example.starter.playout.domain.PlaylistVersionRow;
import com.example.starter.playout.repo.PlayoutRepository;
import com.example.starter.playout.web.AckRequest;
import com.example.starter.playout.web.AckResponse;
import com.example.starter.playout.web.AsRunView;
import com.example.starter.playout.web.ErrorBody;
import com.example.starter.playout.web.LiveEndRequest;
import com.example.starter.playout.web.LiveEndResponse;
import com.example.starter.playout.web.LiveStartRequest;
import com.example.starter.playout.web.LiveStartResponse;
import com.example.starter.playout.web.PlaylistItemRequest;
import com.example.starter.playout.web.PlaylistPublishRequest;
import com.example.starter.playout.web.PlaylistPublishResponse;
import com.example.starter.playout.web.TickRequest;
import com.example.starter.playout.web.TickResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 频道播出编排领域服务：版本化播单、直播插播与 as-run 日志。
 *
 * <p>核心约定：</p>
 * <ul>
 *   <li>时间统一 UTC，节目半开时段 [start,end)，端点相接合法；</li>
 *   <li>每次 tick 把时间轴从上次水位线结算到本次时刻，生成频道内单调的 asRunSequence；</li>
 *   <li>任一时刻最多一个实际素材：直播插播优先级高于 0 的播单基线时压过播单素材；</li>
 *   <li>同优先级并发插播按 requestId 字典序确定唯一胜者；</li>
 *   <li>结束插播只恢复结束时刻应播素材，被覆盖部分不补播；</li>
 *   <li>LIVE_START/LIVE_END/ACK 同键同参返回首次冻结结果，同键异参 409；</li>
 *   <li>ACK 只能将对应序号 PENDING 置为 ACKED，不重复改写。</li>
 * </ul>
 */
@Service
public class PlayoutService {

    /** 播单素材的基线优先级；直播优先级严格大于该值才能压过播单素材。 */
    private static final int PLAYLIST_BASE_PRIORITY = 0;

    private final PlayoutRepository repo;
    private final SyntheticClock clock;
    private final PlayoutAdapter adapter;
    private final ObjectMapper mapper;
    private final LiveStartBatchGate batchGate;
    private final TransactionTemplate transactionTemplate;

    public PlayoutService(PlayoutRepository repo, SyntheticClock clock,
                          PlayoutAdapter adapter, ObjectMapper mapper,
                          LiveStartBatchGate batchGate,
                          org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repo = repo;
        this.clock = clock;
        this.adapter = adapter;
        this.mapper = mapper;
        this.batchGate = batchGate;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    // ============================ 播单发布 ============================

    /**
     * 发布播单版本；校验失败整体回滚，不替换频道当前绑定版本。
     */
    @Transactional
    public PlaylistPublishResponse publishPlaylist(PlaylistPublishRequest request) {
        Instant now = clock.instant();
        repo.ensureChannel(request.channel(), now);

        List<PlaylistItemRequest> items = request.items().stream()
                .sorted(Comparator.comparing(PlaylistItemRequest::start))
                .toList();

        validateItems(items);

        if (repo.findVersionByLabel(request.channel(), request.version()).isPresent()) {
            throw new PlayoutException(HttpStatus.CONFLICT,
                    "playlist version already exists: " + request.version());
        }

        long versionId;
        try {
            versionId = repo.insertVersion(request.channel(), request.version(), now);
            int seq = 0;
            for (PlaylistItemRequest item : items) {
                repo.insertItem(versionId, item.itemId(), item.materialDigest(),
                        item.start(), item.end(), seq++);
            }
            // 全部节目项写入成功后才切换当前版本；异常时事务回滚，当前版本不变。
            repo.bindCurrentVersion(request.channel(), versionId, now);
        } catch (DuplicateKeyException e) {
            throw new PlayoutException(HttpStatus.CONFLICT,
                    "playlist version already exists: " + request.version());
        }

        List<PlaylistPublishResponse.ItemView> views = items.stream()
                .map(i -> new PlaylistPublishResponse.ItemView(
                        i.itemId(), i.materialDigest(), i.start(), i.end()))
                .toList();
        return new PlaylistPublishResponse(
                request.channel(), request.version(), versionId, now, views);
    }

    private void validateItems(List<PlaylistItemRequest> items) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        PlaylistItemRequest previous = null;
        for (PlaylistItemRequest item : items) {
            if (!seen.add(item.itemId())) {
                throw new PlayoutException(HttpStatus.BAD_REQUEST,
                        "duplicate itemId: " + item.itemId());
            }
            if (item.materialDigest() == null || item.materialDigest().isBlank()) {
                throw new PlayoutException(HttpStatus.BAD_REQUEST,
                        "materialDigest must not be blank for item " + item.itemId());
            }
            if (!item.start().isBefore(item.end())) {
                throw new PlayoutException(HttpStatus.BAD_REQUEST,
                        "item must have positive duration: " + item.itemId());
            }
            if (!isFinite(item.start()) || !isFinite(item.end())) {
                throw new PlayoutException(HttpStatus.BAD_REQUEST,
                        "item times must be finite: " + item.itemId());
            }
            // 已按开始时间排序：正长度重叠等价于上一项 end > 当前项 start；端点相接（相等）合法。
            if (previous != null && previous.end().isAfter(item.start())) {
                throw new PlayoutException(HttpStatus.BAD_REQUEST,
                        "items overlap with positive duration: "
                                + previous.itemId() + " vs " + item.itemId());
            }
            previous = item;
        }
    }

    private static boolean isFinite(Instant t) {
        return !t.equals(Instant.MIN) && !t.equals(Instant.MAX);
    }

    // ============================ 调度 tick ============================

    /**
     * 调度结算：推进水位线并按绑定播单版本与插播状态生成单调 as-run 记录。
     */
    @Transactional
    public TickResponse tick(TickRequest request) {
        Instant tickTime = request.time() != null ? request.time() : clock.instant();
        Instant now = clock.instant();
        repo.ensureChannel(request.channel(), now);

        Object[] locked = requireLockedChannel(request.channel());
        Long versionId = (Long) locked[0];
        Instant watermark = (Instant) locked[1];

        if (watermark != null && tickTime.isBefore(watermark)) {
            throw new PlayoutException(HttpStatus.CONFLICT,
                    "tick time is before settlement watermark " + watermark);
        }
        List<AsRunView> generated = new ArrayList<>();
        if (watermark == null || tickTime.isAfter(watermark)) {
            generated = settle(request.channel(), versionId,
                    watermark == null ? null : watermark, tickTime);
            if (repo.advanceWatermark(request.channel(), tickTime) == 0) {
                throw new PlayoutException(HttpStatus.CONFLICT,
                        "watermark advanced concurrently; retry the tick");
            }
        }
        return new TickResponse(request.channel(), tickTime, tickTime, generated);
    }

    /**
     * 把 (from, to] 的时间轴结算为 as-run 段；from 为 null 表示首次结算。
     * 同一频道任一时刻最多一个实际素材；插播结束后只恢复当前应播素材，不补播。
     */
    private List<AsRunView> settle(String channel, Long versionId, Instant from, Instant to) {
        List<PlaylistItemRow> items = versionId == null
                ? List.of() : repo.findItemsByVersion(versionId);
        List<LiveInsertRow> lives = repo.findLivesOverlapping(channel, from, to);
        Instant createdAt = clock.instant();
        List<AsRunView> generated = new ArrayList<>();
        List<Long> generatedSeqs = new ArrayList<>();

        if (from == null) {
            // 首次结算：to 之前的历史不补播，只在 to 时刻建立开放段。
            Identity atTo = identityAt(to, items, lives);
            if (!atTo.gap() && repo.findOpenAsRun(channel).isEmpty()) {
                long seq = repo.maxChannelSeq(channel).orElse(0L) + 1;
                insertSegment(channel, versionId, atTo, to, createdAt, seq);
                generatedSeqs.add(seq);
            }
            return refreshViews(channel, generatedSeqs, generated);
        }

        // 收集 (from, to] 内的全部事件时刻；同一时刻多个事件合并，按该时刻净值判定。
        TreeSet<Instant> cuts = new TreeSet<>();
        for (PlaylistItemRow item : items) {
            addCut(cuts, item.startTime(), from, to);
            addCut(cuts, item.endTime(), from, to);
        }
        for (LiveInsertRow live : lives) {
            addCut(cuts, live.startTime(), from, to);
            if (live.endTime() != null) {
                addCut(cuts, live.endTime(), from, to);
            }
        }

        // 纯计算：from 时刻的基线身份 + 每个事件后的净身份，折叠相邻相同身份为游程。
        List<Run> runs = new ArrayList<>();
        Identity current = identityAt(from, items, lives);
        Instant runStart = from;
        for (Instant cut : cuts) {
            Identity after = identityAt(cut, items, lives);
            if (!after.equals(current)) {
                runs.add(new Run(runStart, cut, current));
                current = after;
                runStart = cut;
            }
        }
        runs.add(new Run(runStart, null, current));

        // 与 tick 之前的持久化开放段对账。
        AsRunRow existing = repo.findOpenAsRun(channel).orElse(null);
        Run first = runs.get(0);
        boolean continuedByExisting = existing != null && !first.identity().gap()
                && Identity.matches(existing, first.identity());
        if (existing != null && !continuedByExisting) {
            // 开放段在 from 时刻终止；若其起点不早于 from（零长度，从未真正播出）则物理删除，
            // 已被 ACK 的段保留为零长度闭段。
            if (!existing.startTime().isBefore(from) && "PENDING".equals(existing.ackState())) {
                repo.deleteAsRun(existing.id());
            } else {
                repo.closeAsRun(existing.id(), from);
            }
        }

        long seq = repo.maxChannelSeq(channel).orElse(0L);
        AsRunRow openNow = continuedByExisting ? existing : null;
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            if (i == 0 && continuedByExisting) {
                // 首个游程就是既有开放段的延续，不重复生成；游程结束时关闭它。
                if (run.end() != null) {
                    repo.closeAsRun(openNow.id(), run.end());
                    openNow = null;
                }
                continue;
            }
            if (run.identity().gap()) {
                continue;
            }
            seq++;
            AsRunRow inserted = insertSegment(channel, versionId, run.identity(),
                    run.start(), createdAt, seq);
            generatedSeqs.add(seq);
            openNow = inserted;
            if (run.end() != null) {
                repo.closeAsRun(inserted.id(), run.end());
                openNow = null;
            }
        }
        return refreshViews(channel, generatedSeqs, generated);
    }

    /**
     * 全部段关闭完成后按序号回读最终视图，确保 tick 响应中的 end/ackState 为最终值。
     */
    private List<AsRunView> refreshViews(String channel, List<Long> seqs, List<AsRunView> sink) {
        for (Long seq : seqs) {
            sink.add(toView(repo.findAsRunBySeq(channel, seq).orElseThrow()));
        }
        return sink;
    }

    private AsRunRow insertSegment(String channel, Long versionId, Identity identity,
                                   Instant start, Instant createdAt, long seq) {
        if (identity.live() != null) {
            LiveInsertRow live = identity.live();
            repo.insertAsRun(channel, seq, versionId, "LIVE", null, null,
                    live.requestId(), live.priority(), start, createdAt);
            repo.updateFirstSequence(channel, live.requestId(), seq);
        } else {
            PlaylistItemRow item = identity.item();
            repo.insertAsRun(channel, seq, versionId, "ITEM", item.itemId(),
                    item.materialDigest(), null, null, start, createdAt);
        }
        AsRunRow inserted = repo.findAsRunBySeq(channel, seq).orElseThrow();
        adapter.start(new PlayoutCommand(channel, seq,
                identity.live() != null ? "LIVE:" + identity.live().requestId()
                        : "ITEM:" + identity.item().itemId()));
        return inserted;
    }

    private static void addCut(TreeSet<Instant> cuts, Instant candidate, Instant from, Instant to) {
        if (candidate.isAfter(from) && !candidate.isAfter(to)) {
            cuts.add(candidate);
        }
    }

    /**
     * 计算时刻 t 实际应播素材身份；直播优先级必须严格高于播单基线（0）才压过播单素材。
     */
    private Identity identityAt(Instant t, List<PlaylistItemRow> items, List<LiveInsertRow> lives) {
        PlaylistItemRow item = null;
        for (PlaylistItemRow candidate : items) {
            if (!candidate.startTime().isAfter(t) && candidate.endTime().isAfter(t)) {
                item = candidate;
                break;
            }
        }
        LiveInsertRow winner = null;
        for (LiveInsertRow live : lives) {
            boolean active = !live.startTime().isAfter(t)
                    && (live.endTime() == null || live.endTime().isAfter(t));
            if (active && (winner == null
                    || live.priority() > winner.priority()
                    || (live.priority() == winner.priority()
                        && live.requestId().compareTo(winner.requestId()) < 0))) {
                winner = live;
            }
        }
        // 无播单素材（空档）时直播即为实际素材；有播单素材时必须严格更高优先级才能压过。
        if (winner != null && (item == null || winner.priority() > PLAYLIST_BASE_PRIORITY)) {
            return Identity.live(winner);
        }
        if (item != null) {
            return Identity.item(item);
        }
        return Identity.GAP;
    }

    // ============================ 直播插播开始 ============================

    /**
     * 直播插播开始；同键同参冻结首次结果，同键异参 409，开始时间早于水位线 409。
     *
     * <p>同一频道、同一开始时刻并发到达的请求先在 {@link LiveStartBatchGate} 内会合，
     * 再按优先级降序、requestId 升序依次进入各自独立事务，确保并发裁决确定唯一胜者。</p>
     */
    public ServiceResult<Object> startLive(LiveStartRequest request) {
        // 排序凭据：优先级降序（取负值升序），requestId 字典序升序。
        record StartOrder(int negPriority, String requestId) implements Comparable<StartOrder> {
            @Override
            public int compareTo(StartOrder other) {
                int byPriority = Integer.compare(negPriority, other.negPriority);
                return byPriority != 0 ? byPriority : requestId.compareTo(other.requestId);
            }
        }
        String batchKey = request.channel() + "|" + request.start();
        StartOrder order = new StartOrder(-request.priority(), request.requestId());
        try {
            return batchGate.runBatched(batchKey,
                    Comparator.naturalOrder(), order,
                    () -> transactionTemplate.execute(status -> doStartLive(request)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlayoutException(HttpStatus.SERVICE_UNAVAILABLE,
                    "live start settlement interrupted");
        }
    }

    private ServiceResult<Object> doStartLive(LiveStartRequest request) {
        Instant now = clock.instant();
        repo.ensureChannel(request.channel(), now);
        String fingerprint = fingerprint("START", request.start().toString(),
                Integer.toString(request.priority()));

        Optional<FingerprintRow> frozen =
                repo.findFingerprint(request.channel(), "LIVE_START", request.requestId());
        if (frozen.isPresent()) {
            return resolveFrozen(frozen.get(), fingerprint, LiveStartResponse.class);
        }

        Object[] locked = requireLockedChannel(request.channel());
        frozen = repo.findFingerprint(request.channel(), "LIVE_START", request.requestId());
        if (frozen.isPresent()) {
            return resolveFrozen(frozen.get(), fingerprint, LiveStartResponse.class);
        }
        Instant watermark = (Instant) locked[1];
        if (watermark != null && request.start().isBefore(watermark)) {
            return freeze(request.channel(), request.requestId(), "LIVE_START", fingerprint,
                    HttpStatus.CONFLICT, new ErrorBody(HttpStatus.CONFLICT.value(),
                            HttpStatus.CONFLICT.getReasonPhrase(),
                            "live start time is before settlement watermark " + watermark));
        }

        // 当前素材 = max(开始时刻仍在播出的插播胜者, 覆盖该时刻的播单素材基线0)。
        LiveInsertRow contender = null;
        for (LiveInsertRow open : repo.findOpenLives(request.channel())) {
            if (!open.startTime().isAfter(request.start())
                    && (contender == null
                        || open.priority() > contender.priority()
                        || (open.priority() == contender.priority()
                            && open.requestId().compareTo(contender.requestId()) < 0))) {
                contender = open;
            }
        }
        Long currentVersionId = (Long) locked[0];
        boolean baselineItem = currentVersionId != null
                && repo.findItemAt(currentVersionId, request.start()).isPresent();

        boolean newWins;
        String blockedBy;
        if (contender != null) {
            // 与当前插播竞争：优先级高者胜；同优先级 requestId 字典序小者胜。
            newWins = request.priority() > contender.priority()
                    || (request.priority() == contender.priority()
                        && request.requestId().compareTo(contender.requestId()) < 0);
            blockedBy = contender.requestId();
        } else if (baselineItem) {
            // 与播单素材（基线优先级 0）竞争：只有严格更高优先级才能压过。
            newWins = request.priority() > PLAYLIST_BASE_PRIORITY;
            blockedBy = "playlist material (requires priority > " + PLAYLIST_BASE_PRIORITY + ")";
        } else {
            newWins = true;
            blockedBy = null;
        }
        if (!newWins) {
            return freeze(request.channel(), request.requestId(), "LIVE_START", fingerprint,
                    HttpStatus.CONFLICT, new ErrorBody(HttpStatus.CONFLICT.value(),
                            HttpStatus.CONFLICT.getReasonPhrase(),
                            "blocked by current material with higher or equal precedence: "
                                    + blockedBy));
        }

        if (contender != null) {
            // 更高优先级（或同优先级字典序更优）的新插播压过当前插播；被覆盖部分不补播。
            repo.markLiveEnded(request.channel(), contender.requestId(),
                    request.start(), "PREEMPTED");
        }
        repo.insertLive(request.channel(), request.requestId(), request.priority(),
                request.start(), fingerprint, now);

        LiveStartResponse response = new LiveStartResponse(
                request.channel(), request.requestId(), request.priority(),
                request.start(), null, false);
        return freezeFirst(request.channel(), request.requestId(), "LIVE_START",
                fingerprint, HttpStatus.OK, response);
    }

    // ============================ 直播插播结束 ============================

    /**
     * 直播插播结束；恢复该时刻播单应播素材，不补播被覆盖部分。
     */
    @Transactional
    public ServiceResult<Object> endLive(LiveEndRequest request) {
        Instant now = clock.instant();
        repo.ensureChannel(request.channel(), now);
        Instant end = request.end() != null ? request.end() : now;
        String fingerprint = fingerprint("END", end.toString());

        Optional<FingerprintRow> frozen =
                repo.findFingerprint(request.channel(), "LIVE_END", request.requestId());
        if (frozen.isPresent()) {
            return resolveFrozen(frozen.get(), fingerprint, LiveEndResponse.class);
        }
        requireLockedChannel(request.channel());
        frozen = repo.findFingerprint(request.channel(), "LIVE_END", request.requestId());
        if (frozen.isPresent()) {
            return resolveFrozen(frozen.get(), fingerprint, LiveEndResponse.class);
        }

        LiveInsertRow live = repo.findLive(request.channel(), request.requestId())
                .orElseThrow(() -> new PlayoutException(HttpStatus.NOT_FOUND,
                        "live insert not found: " + request.requestId()));
        if (!end.isAfter(live.startTime())) {
            throw new PlayoutException(HttpStatus.BAD_REQUEST,
                    "live end time must be after its start time");
        }
        Instant watermark = repo.findWatermark(request.channel());
        if (watermark != null && end.isBefore(watermark)) {
            return freeze(request.channel(), request.requestId(), "LIVE_END", fingerprint,
                    HttpStatus.CONFLICT, new ErrorBody(HttpStatus.CONFLICT.value(),
                            HttpStatus.CONFLICT.getReasonPhrase(),
                            "live end time is before settlement watermark " + watermark
                                    + "; covered playlist portion cannot be reconstructed"));
        }

        String endKind;
        Instant effectiveEnd;
        if (live.endTime() == null) {
            repo.markLiveEnded(request.channel(), request.requestId(), end, "ENDED");
            endKind = "ENDED";
            effectiveEnd = end;
        } else {
            // 此前已被更高优先级插播抢占；只回传既有结果，不重新开放。
            endKind = live.endKind();
            effectiveEnd = live.endTime();
        }

        LiveEndResponse response = new LiveEndResponse(
                request.channel(), request.requestId(), effectiveEnd, endKind, false);
        return freezeFirst(request.channel(), request.requestId(), "LIVE_END",
                fingerprint, HttpStatus.OK, response);
    }

    // ============================ 适配器 ACK ============================

    /**
     * 适配器 ACK；可重复或迟到，只能确认对应 asRunSequence。
     */
    @Transactional
    public ServiceResult<Object> ack(AckRequest request) {
        Instant now = clock.instant();
        repo.ensureChannel(request.channel(), now);
        String fingerprint = fingerprint("ACK", Long.toString(request.asRunSequence()));

        Optional<FingerprintRow> frozen =
                repo.findFingerprint(request.channel(), "ACK", request.ackKey());
        if (frozen.isPresent()) {
            return resolveFrozen(frozen.get(), fingerprint, AckResponse.class);
        }
        requireLockedChannel(request.channel());
        frozen = repo.findFingerprint(request.channel(), "ACK", request.ackKey());
        if (frozen.isPresent()) {
            return resolveFrozen(frozen.get(), fingerprint, AckResponse.class);
        }

        AsRunRow row = repo.findAsRunBySeq(request.channel(), request.asRunSequence())
                .orElseThrow(() -> new PlayoutException(HttpStatus.NOT_FOUND,
                        "as-run sequence not found: " + request.asRunSequence()));
        // 仅 PENDING -> ACKED 的一次迁移；重复或迟到 ACK 不改写已确认状态。
        boolean accepted = repo.compareAndSetAck(row.id(), "PENDING", "ACKED") > 0;
        String ackState = accepted ? "ACKED" : row.ackState();

        AckResponse response = new AckResponse(request.channel(), request.ackKey(),
                request.asRunSequence(), accepted, ackState, false);
        return freezeFirst(request.channel(), request.ackKey(), "ACK",
                fingerprint, HttpStatus.OK, response);
    }

    // ============================ as-run 查询 ============================

    @Transactional(readOnly = true)
    public List<AsRunView> queryAsRun(String channel, Instant from, Instant to) {
        return repo.findAsRun(channel, from, to).stream()
                .map(PlayoutService::toView)
                .toList();
    }

    private static AsRunView toView(AsRunRow row) {
        return new AsRunView(row.channelSeq(), row.kind(), row.itemId(),
                row.materialDigest(), row.liveRequestId(), row.priority(),
                row.startTime(), row.endTime(), row.ackState());
    }

    // ============================ 幂等指纹 ============================

    private Object[] requireLockedChannel(String channel) {
        Object[] locked = repo.lockChannel(channel);
        if (locked == null) {
            throw new PlayoutException(HttpStatus.NOT_FOUND, "channel not initialized: " + channel);
        }
        return locked;
    }

    private ServiceResult<Object> resolveFrozen(FingerprintRow row, String currentFingerprint,
                                                Class<?> successType) {
        if (!row.fingerprint().equals(currentFingerprint)) {
            throw new PlayoutException(HttpStatus.CONFLICT,
                    "request key was used with different parameters");
        }
        try {
            Class<?> type = row.statusCode() == HttpStatus.OK.value()
                    ? successType : ErrorBody.class;
            Object body = mapper.readValue(row.frozenResponse(), type);
            // 业务字段保持首次冻结结果，仅把重放元数据标记为 true。
            body = markReplay(body);
            return ServiceResult.replay(body, row.statusCode());
        } catch (Exception e) {
            throw new IllegalStateException("frozen response unreadable", e);
        }
    }

    private static Object markReplay(Object body) {
        return switch (body) {
            case LiveStartResponse r -> new LiveStartResponse(
                    r.channel(), r.requestId(), r.priority(), r.start(),
                    r.firstSequence(), true);
            case LiveEndResponse r -> new LiveEndResponse(
                    r.channel(), r.requestId(), r.end(), r.endKind(), true);
            case AckResponse r -> new AckResponse(
                    r.channel(), r.ackKey(), r.asRunSequence(),
                    r.accepted(), r.ackState(), true);
            default -> body;
        };
    }

    private ServiceResult<Object> freezeFirst(String channel, String key, String action,
                                              String fingerprint, HttpStatus status, Object body) {
        insertFingerprint(channel, key, action, fingerprint, status, body);
        return ServiceResult.first(body, status.value());
    }

    private ServiceResult<Object> freeze(String channel, String key, String action,
                                         String fingerprint, HttpStatus status, ErrorBody body) {
        insertFingerprint(channel, key, action, fingerprint, status, body);
        return ServiceResult.first(body, status.value());
    }

    private void insertFingerprint(String channel, String key, String action, String fingerprint,
                                   HttpStatus status, Object body) {
        try {
            repo.insertFingerprint(channel, key, action, fingerprint, status.value(),
                    writeJson(body), clock.instant());
        } catch (DuplicateKeyException e) {
            // 同键并发首请求竞争：胜者已冻结，调用方按重放/异参规则处理。
            FingerprintRow winner = repo.findFingerprint(channel, action, key)
                    .orElseThrow(() -> e);
            if (!winner.fingerprint().equals(fingerprint)) {
                throw new PlayoutException(HttpStatus.CONFLICT,
                        "request key was used with different parameters");
            }
            throw new PlayoutException(HttpStatus.CONFLICT,
                    "concurrent request with same key is being settled");
        }
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize response", e);
        }
    }

    private static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 身份游程：identity 在 [start, end) 内恒定；end 为 null 表示截至本次 tick 仍开放。
     */
    private record Run(Instant start, Instant end, Identity identity) {
    }

    /**
     * 时间轴上某时刻的实际素材身份；GAP 表示无播单节目也无生效插播。
     */
    private record Identity(String kind, PlaylistItemRow item, LiveInsertRow live) {
        static final Identity GAP = new Identity("GAP", null, null);

        static Identity item(PlaylistItemRow item) {
            return new Identity("ITEM", item, null);
        }

        static Identity live(LiveInsertRow live) {
            return new Identity("LIVE", null, live);
        }

        boolean gap() {
            return "GAP".equals(kind);
        }

        static boolean matches(AsRunRow row, Identity identity) {
            return switch (identity.kind()) {
                case "ITEM" -> "ITEM".equals(row.kind())
                        && identity.item().versionId() == (row.versionId() == null ? -1L : row.versionId())
                        && identity.item().itemId().equals(row.itemId());
                case "LIVE" -> "LIVE".equals(row.kind())
                        && identity.live().requestId().equals(row.liveRequestId());
                default -> false;
            };
        }
    }
}
