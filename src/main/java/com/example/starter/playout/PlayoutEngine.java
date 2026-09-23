package com.example.starter.playout;

import com.example.starter.adapter.PlayoutAdapter;
import com.example.starter.asrun.AsRunRecord;
import com.example.starter.asrun.AsRunRepository;
import com.example.starter.breakmodel.BreakRequestRepository;
import com.example.starter.playlist.PlaylistItem;
import com.example.starter.playlist.PlaylistRepository;
import com.example.starter.state.ChannelStateRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 排播引擎：在给定时刻解析应播素材，并维护 as-run 记录与适配器指令。
 * 所有方法须在持有频道行锁的事务内调用，保证同一频道任一时刻最多一个在播素材。
 */
@Component
public class PlayoutEngine {

    /**
     * 应播素材：kind 为 SCHEDULED 或 BREAK；itemId/breakRequestId 视类型而定。
     */
    public record DesiredMedia(String kind, String itemId, String mediaDigest, String breakRequestId) {

        public boolean sameAs(AsRunRecord record) {
            return record != null
                    && record.kind().equals(kind)
                    && java.util.Objects.equals(record.itemId(), itemId)
                    && java.util.Objects.equals(record.breakRequestId(), breakRequestId)
                    && record.mediaDigest().equals(mediaDigest);
        }
    }

    private final AsRunRepository asRunRepository;
    private final PlaylistRepository playlistRepository;
    private final BreakRequestRepository breakRequestRepository;
    private final PlayoutAdapter playoutAdapter;

    public PlayoutEngine(AsRunRepository asRunRepository,
                         PlaylistRepository playlistRepository,
                         BreakRequestRepository breakRequestRepository,
                         PlayoutAdapter playoutAdapter) {
        this.asRunRepository = asRunRepository;
        this.playlistRepository = playlistRepository;
        this.breakRequestRepository = breakRequestRepository;
        this.playoutAdapter = playoutAdapter;
    }

    /**
     * 解析 atMs 时刻应播素材：进行中的插播优先，其次当前播单版本覆盖该时刻的节目项，否则为空（空档）。
     */
    public DesiredMedia resolveDesired(ChannelStateRepository.ChannelState state, String channelId, long atMs) {
        BreakRequestRepository.BreakRequestRow activeBreak = breakRequestRepository.findActive(channelId);
        if (activeBreak != null && activeBreak.startMs() <= atMs) {
            return new DesiredMedia("BREAK", null, "LIVE:" + activeBreak.requestId(), activeBreak.requestId());
        }
        return resolveScheduled(state, channelId, atMs);
    }

    /**
     * 仅解析排播素材（插播结束后恢复用），不补播被覆盖部分。
     */
    public DesiredMedia resolveScheduled(ChannelStateRepository.ChannelState state, String channelId, long atMs) {
        if (state.currentVersionId() == null) {
            return null;
        }
        PlaylistItem item = playlistRepository.findItemAt(state.currentVersionId(), atMs);
        if (item == null) {
            return null;
        }
        return new DesiredMedia("SCHEDULED", item.itemId(), item.mediaDigest(), null);
    }

    /**
     * 改写 fromMs 时刻起的 as-run：起点不早于 fromMs 的记录标记为被取代，
     * 覆盖 fromMs 的记录在 fromMs 处截断关闭。仅用于未越过水位线的记录（调用方保证）。
     */
    public void rewriteFrom(String channelId, long fromMs) {
        List<AsRunRecord> overlapping = asRunRepository.findOverlappingFrom(channelId, fromMs);
        for (AsRunRecord record : overlapping) {
            if (record.startMs() >= fromMs) {
                asRunRepository.supersede(channelId, record.seq());
                playoutAdapter.offAir(record);
            } else {
                asRunRepository.closeAt(channelId, record.seq(), fromMs);
                playoutAdapter.offAir(record);
            }
        }
    }

    /**
     * 在 endMs 处关闭在播记录并通知适配器。
     */
    public void closeRecord(String channelId, AsRunRecord record, long endMs) {
        asRunRepository.closeAt(channelId, record.seq(), endMs);
        playoutAdapter.offAir(record);
    }

    /**
     * 开启一条新的在播记录，序号取自频道状态并推进。
     */
    public AsRunRecord openRecord(String channelId, long seq, DesiredMedia media, long startMs) {
        AsRunRecord record = new AsRunRecord(channelId, seq, media.kind(), media.itemId(),
                media.mediaDigest(), media.breakRequestId(), startMs, null, "OPEN", false, null);
        asRunRepository.insert(record);
        playoutAdapter.onAir(record);
        return record;
    }
}
