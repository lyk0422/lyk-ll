package com.example.starter.playlist;

import com.example.starter.clock.SyntheticClock;
import com.example.starter.common.ApiException;
import com.example.starter.state.ChannelStateRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 播单发布：校验节目项时段规则后，在同一事务内写入版本并切换频道绑定；
 * 任何校验或写入失败都会整体回滚，不替换当前版本。
 */
@Service
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final ChannelStateRepository channelStateRepository;
    private final SyntheticClock clock;

    public PlaylistService(PlaylistRepository playlistRepository,
                           ChannelStateRepository channelStateRepository,
                           SyntheticClock clock) {
        this.playlistRepository = playlistRepository;
        this.channelStateRepository = channelStateRepository;
        this.clock = clock;
    }

    @Transactional
    public PlaylistPublishResponse publish(PlaylistPublishRequest request) {
        String channelId = request.channelId();
        List<PlaylistItem> items = validateAndSort(request);

        ChannelStateRepository.ChannelState state = channelStateRepository.lockByChannelId(channelId);
        int versionNo = request.versionNo() != null
                ? request.versionNo()
                : (playlistRepository.maxVersionNo(channelId) != null
                    ? playlistRepository.maxVersionNo(channelId) + 1 : 1);
        if (versionNo <= 0) {
            throw ApiException.unprocessable("版本号必须为正整数");
        }
        if (playlistRepository.versionExists(channelId, versionNo)) {
            throw ApiException.conflict("频道 " + channelId + " 的播单版本 " + versionNo + " 已存在");
        }

        long publishedAtMs = clock.nowMs();
        long versionId;
        try {
            versionId = playlistRepository.insertVersion(channelId, versionNo, items.size(), publishedAtMs);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("频道 " + channelId + " 的播单版本 " + versionNo + " 已存在");
        }
        for (int i = 0; i < items.size(); i++) {
            playlistRepository.insertItem(versionId, channelId, items.get(i), i);
        }
        channelStateRepository.updateBinding(channelId, versionId);
        return new PlaylistPublishResponse(channelId, versionNo, items.size(), publishedAtMs, items);
    }

    /**
     * 查询频道当前绑定版本的节目项（供测试与排播核对）。
     */
    public List<PlaylistItem> currentItems(String channelId) {
        ChannelStateRepository.ChannelState state = channelStateRepository.find(channelId);
        if (state == null || state.currentVersionId() == null) {
            return List.of();
        }
        return playlistRepository.itemsOf(state.currentVersionId());
    }

    /**
     * 校验：itemId 版本内唯一、时长为有限正值、半开区间端点相接合法、正长度重叠拒绝。
     */
    private List<PlaylistItem> validateAndSort(PlaylistPublishRequest request) {
        Set<String> seenIds = new HashSet<>();
        List<PlaylistItem> items = new ArrayList<>();
        for (PlaylistPublishRequest.PlaylistItemInput input : request.items()) {
            if (!seenIds.add(input.itemId())) {
                throw ApiException.unprocessable("节目项 itemId 重复: " + input.itemId());
            }
            long start = input.startMs();
            long end = input.endMs();
            if (end <= start) {
                throw ApiException.unprocessable(
                        "节目项 " + input.itemId() + " 时长必须为有限正值（end 必须大于 start）");
            }
            items.add(new PlaylistItem(input.itemId(), input.mediaDigest(), start, end));
        }
        items.sort(Comparator.comparingLong(PlaylistItem::startMs));
        for (int i = 1; i < items.size(); i++) {
            PlaylistItem prev = items.get(i - 1);
            PlaylistItem curr = items.get(i);
            if (curr.startMs() < prev.endMs()) {
                throw ApiException.unprocessable(
                        "节目项 " + curr.itemId() + " 与 " + prev.itemId() + " 存在正长度重叠");
            }
        }
        return items;
    }
}
