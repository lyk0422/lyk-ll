package com.example.starter.bag.service;

import com.example.starter.bag.config.BagProperties;
import com.example.starter.bag.model.SegmentStatus;
import com.example.starter.bag.repository.MessageIndexRepository;
import com.example.starter.bag.repository.MessageRepository;
import com.example.starter.bag.repository.MessageRepository.MessageRow;
import com.example.starter.bag.repository.PlayRepository;
import com.example.starter.bag.repository.PlayRepository.PlayRow;
import com.example.starter.bag.repository.SegmentRepository;
import com.example.starter.bag.repository.SegmentRepository.SegmentRow;
import com.example.starter.bag.web.BagExceptionHandler.ConflictException;
import com.example.starter.bag.web.dto.CorruptSegmentRequest;
import com.example.starter.bag.web.dto.CorruptSegmentRequest.FaultMode;
import com.example.starter.bag.web.dto.MessageView;
import com.example.starter.bag.web.dto.PlayRequest;
import com.example.starter.bag.web.dto.PlayResult;
import com.example.starter.bag.web.dto.RecoveryResult;
import com.example.starter.bag.web.dto.SegmentRecoveryReport;
import com.example.starter.bag.web.dto.SegmentView;
import com.example.starter.bag.web.dto.WriteMessageRequest;
import com.example.starter.bag.web.dto.WriteResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * rosbag 风格消息存储核心服务。
 *
 * <p>写入：Topic 级串行（bag_topic_lock 行锁），同批次同参重试幂等，序列号单调不倒退，
 * 消息按 {@code bag.segment.max-size} 逻辑分段追加，消息与二级索引及段边界在同一事务落库。
 *
 * <p>查询：先用分段边界（粗索引）裁剪命中分段，再经 bag_msg_index 二级索引过滤时间/序列范围，
 * 不会把全部消息读入内存。
 *
 * <p>恢复：在单个事务内按段级链式校验定位可验证完整前缀，隔离未链入的中断尾消息，重建索引并重写边界。
 */
@Service
public class RosbagStorageService {

    private final BagProperties properties;
    private final SegmentRepository segments;
    private final MessageRepository messages;
    private final MessageIndexRepository indexRepository;
    private final PlayRepository plays;
    private final NamedParameterJdbcTemplate jdbc;

    public RosbagStorageService(BagProperties properties,
                                SegmentRepository segments,
                                MessageRepository messages,
                                MessageIndexRepository indexRepository,
                                PlayRepository plays,
                                NamedParameterJdbcTemplate jdbc) {
        this.properties = properties;
        this.segments = segments;
        this.messages = messages;
        this.indexRepository = indexRepository;
        this.plays = plays;
        this.jdbc = jdbc;
    }

    /**
     * 追加单条消息。
     *
     * @throws ConflictException 序列号倒退、同批次参数不一致或段处于待恢复状态等业务冲突
     */
    @Transactional
    public WriteResult write(WriteMessageRequest request) {
        byte[] payload = decodePayload(request.payloadBase64());
        String payloadChecksum = Checksums.sha256(payload);

        lockTopic(request.topic());

        // 同批次同参重试：命中既有消息直接返回，不重复写入；即使段待恢复也允许幂等读取
        MessageRow existing = messages.findByBatchKey(request.batchNo(), request.topic(), request.seq());
        if (existing != null) {
            verifySameParams(existing, request, payloadChecksum);
            SegmentRow seg = segments.findById(existing.segmentId());
            return new WriteResult(existing.id(), existing.segmentId(), seg.segIndex(), true);
        }

        if (segments.existsDamaged(request.topic())) {
            throw new ConflictException("Topic 存在待恢复的受损分段，必须先调用恢复接口再写入：topic="
                    + request.topic());
        }

        Long maxSeq = messages.findMaxCommittedSeq(request.topic());
        if (maxSeq != null && request.seq() <= maxSeq) {
            throw new ConflictException("Topic 内序列号不得倒退：topic=" + request.topic()
                    + "，请求 seq=" + request.seq() + "，当前最大 seq=" + maxSeq);
        }

        SegmentRow target = segments.findLatestForUpdate(request.topic());
        if (target == null || target.status() != SegmentStatus.OPEN
                || target.messageCount() >= target.maxSize()) {
            int nextIndex = segments.nextSegIndex(request.topic());
            long newId = segments.insert(request.topic(), nextIndex, properties.maxSize());
            target = segments.findByIdForUpdate(newId);
        }

        String previous = target.checksum() == null ? "" : target.checksum();
        String chain = Checksums.chain(previous, request.topic(), request.seq(),
                request.timestampNs(), request.typeId(), payloadChecksum);

        messages.insertCommitted(request.topic(), request.timestampNs(), request.seq(),
                request.typeId(), payload, payloadChecksum, chain, request.batchNo(), target.id());
        long messageId = requireId(messages.findByBatchKey(
                request.batchNo(), request.topic(), request.seq()));
        indexRepository.insert(messageId, request.topic(), request.timestampNs(),
                request.seq(), target.id());

        long newCount = target.messageCount() + 1;
        if (newCount == 1) {
            segments.initBoundaries(target.id(), request.timestampNs(), request.seq(), chain);
        } else {
            segments.extendBoundaries(target.id(), request.timestampNs(), request.seq(), newCount, chain);
        }
        if (newCount >= target.maxSize()) {
            segments.updateStatus(target.id(), SegmentStatus.SEALED);
        }
        return new WriteResult(messageId, target.id(), target.segIndex(), false);
    }

    /**
     * 组合范围查询。先定位相交分段，再走二级索引，结果按序列号原序返回；
     * DAMAGED 段不参与查询，必须先恢复。
     */
    @Transactional(readOnly = true)
    public List<MessageView> query(String topic, Long startNs, Long endNs, Long startSeq, Long endSeq) {
        validateRange(startNs, endNs, startSeq, endSeq);
        List<Long> candidateIds = segments.findCandidateSegmentIds(topic, startNs, endNs, startSeq, endSeq);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        return messages.queryViaIndex(candidateIds, topic, startNs, endNs, startSeq, endSeq)
                .stream().map(RosbagStorageService::toView).toList();
    }

    /** 查看 Topic 下分段及边界、消息数、状态与段级校验。 */
    @Transactional(readOnly = true)
    public List<SegmentView> segments(String topic) {
        return segments.findByTopic(topic).stream()
                .map(s -> new SegmentView(s.id(), s.topic(), s.segIndex(), s.startNs(), s.endNs(),
                        s.startSeq(), s.endSeq(), s.messageCount(), s.maxSize(), s.status(), s.checksum()))
                .toList();
    }

    /**
     * 测试入口：模拟写入中断或索引损坏。
     *
     * @throws NoSuchElementException 分段不存在
     * @throws ConflictException      目标段不是 Topic 最新段（不允许向历史段注入尾消息）
     */
    @Transactional
    public void corrupt(CorruptSegmentRequest request) {
        SegmentRow target = segments.findByTopicAndIndex(request.topic(), request.segIndex());
        if (target == null) {
            throw new NoSuchElementException("分段不存在：topic=" + request.topic()
                    + "，segIndex=" + request.segIndex());
        }
        SegmentRow latest = segments.findLatestForUpdate(request.topic());
        if (latest == null || latest.id() != target.id()) {
            throw new ConflictException("仅允许对 Topic 最新分段注入故障：topic=" + request.topic()
                    + "，segIndex=" + request.segIndex());
        }

        switch (request.mode()) {
            case DROP_INDEX -> {
                indexRepository.deleteBySegmentId(target.id());
                segments.updateStatus(target.id(), SegmentStatus.DAMAGED);
            }
            case MARK_DAMAGED -> segments.updateStatus(target.id(), SegmentStatus.DAMAGED);
            case INCOMPLETE_TAIL -> {
                int tailSize = request.tailSize() == null ? 1 : request.tailSize();
                long baseSeq = target.endSeq() == null ? -1 : target.endSeq();
                long baseNs = target.endNs() == null ? 0L : target.endNs();
                for (int i = 1; i <= tailSize; i++) {
                    long seq = baseSeq + i;
                    long ts = baseNs + i;
                    byte[] payload = ("corrupt-tail-" + seq).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    messages.insertIncompleteTail(request.topic(), ts, seq, "corrupt/IncompleteTail",
                            payload, Checksums.sha256(payload),
                            "corrupt:" + target.id() + ":" + seq, target.id());
                    // 故意不写索引、不推进边界、不更新段级链校验，模拟写入中断
                }
                segments.updateStatus(target.id(), SegmentStatus.DAMAGED);
            }
        }
    }

    /**
     * 恢复全部 DAMAGED 分段。对每个段：
     * 锁定段与消息 → 用段级链式校验找出可验证完整前缀 → 同事务隔离其余尾消息 →
     * 删除并重建二级索引 → 按前缀重写段边界与状态。
     */
    @Transactional
    public RecoveryResult recover() {
        List<SegmentRow> damaged = segments.findDamagedForUpdate();
        List<SegmentRecoveryReport> reports = new ArrayList<>();
        for (SegmentRow seg : damaged) {
            SegmentRow locked = segments.findByIdForUpdate(seg.id());
            List<MessageRow> all = messages.findAllBySegmentForUpdate(locked.id());

            // 逐条验证完整前缀：负载校验值一致 + 链锚点可重算复现；
            // 遇到无锚点的中断尾消息或任何一处不匹配即停止，其后全部视为不完整消息。
            String running = "";
            int kept = 0;
            for (MessageRow row : all) {
                if (row.chainValue() == null) {
                    break;
                }
                if (!Objects.equals(Checksums.sha256(row.payload()), row.payloadChecksum())) {
                    break;
                }
                running = Checksums.chain(running, row.topic(), row.seq(), row.timestampNs(),
                        row.typeId(), row.payloadChecksum());
                if (!Objects.equals(running, row.chainValue())) {
                    break;
                }
                kept++;
            }
            boolean prefixVerified = kept > 0;

            List<Long> tailIds = all.subList(kept, all.size()).stream().map(MessageRow::id).toList();
            long quarantined = messages.quarantineByIds(tailIds);
            long rebuilt = indexRepository.rebuildForSegment(locked.id());

            Long startNs = null;
            Long endNs = null;
            Long startSeq = null;
            Long endSeq = null;
            String finalChecksum = null;
            if (kept > 0) {
                // 序列号按序追加，首/尾即最小/最大；纳秒时间戳不要求随序列号单调，按前缀聚合 min/max
                long minNs = Long.MAX_VALUE;
                long maxNs = Long.MIN_VALUE;
                for (int i = 0; i < kept; i++) {
                    MessageRow row = all.get(i);
                    minNs = Math.min(minNs, row.timestampNs());
                    maxNs = Math.max(maxNs, row.timestampNs());
                }
                MessageRow first = all.get(0);
                MessageRow last = all.get(kept - 1);
                startNs = minNs;
                endNs = maxNs;
                startSeq = first.seq();
                endSeq = last.seq();
                finalChecksum = last.chainValue();
            }
            // 末段且未写满才重新开放追加；非末段或已满一律封段
            boolean isLastSegment = locked.segIndex() == segments.maxSegIndex(locked.topic());
            SegmentStatus finalStatus =
                    (kept >= locked.maxSize() || !isLastSegment) ? SegmentStatus.SEALED : SegmentStatus.OPEN;
            segments.rewriteAfterRecovery(locked.id(), startNs, endNs, startSeq, endSeq,
                    kept, finalChecksum, finalStatus);

            reports.add(new SegmentRecoveryReport(locked.topic(), locked.segIndex(), kept,
                    quarantined, rebuilt, prefixVerified, finalStatus.name()));
        }
        return new RecoveryResult(reports);
    }

    /**
     * 按原顺序回放命中消息。相同 playKey 的并发或重复请求只创建一条 bag_play 记录：
     * 重复请求必须与首次参数一致，并按首次记录的范围回放，保证重复回放结果与首次同源。
     */
    @Transactional
    public PlayResult play(PlayRequest request) {
        validateRange(request.startNs(), request.endNs(), request.startSeq(), request.endSeq());

        PlayRow existing = plays.findByKey(request.playKey());
        if (existing != null) {
            verifySamePlayRequest(existing, request);
            return buildPlayResult(existing, true);
        }

        List<MessageRow> rows = queryRows(request.topic(), request.startNs(), request.endNs(),
                request.startSeq(), request.endSeq());
        String replayChecksum = chainOf(rows);

        PlayRow candidate = new PlayRow(0, request.playKey(), request.topic(),
                request.startNs(), request.endNs(), request.startSeq(), request.endSeq(),
                rows.size(), replayChecksum);
        boolean created = plays.tryInsert(candidate);
        PlayRow saved = requirePlay(plays.findByKey(request.playKey()));
        if (!created) {
            // 并发情况下首条记录由其他事务创建：按既有记录参数回放，拒绝参数不一致的请求
            verifySamePlayRequest(saved, request);
            return buildPlayResult(saved, true);
        }
        plays.insertItems(saved.id(), rows);
        return new PlayResult(saved.id(), saved.playKey(), saved.topic(), saved.messageCount(),
                saved.replayChecksum(), false, rows.stream().map(RosbagStorageService::toView).toList());
    }

    /** 重复回放：读取首次保存的不可变消息快照，顺序与内容与首次完全一致。 */
    private PlayResult buildPlayResult(PlayRow record, boolean duplicated) {
        List<MessageRow> rows = plays.findItems(record.id());
        return new PlayResult(record.id(), record.playKey(), record.topic(), record.messageCount(),
                record.replayChecksum(), duplicated,
                rows.stream().map(RosbagStorageService::toView).toList());
    }

    private List<MessageRow> queryRows(String topic, Long startNs, Long endNs, Long startSeq, Long endSeq) {
        List<Long> candidateIds = segments.findCandidateSegmentIds(topic, startNs, endNs, startSeq, endSeq);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        return messages.queryViaIndex(candidateIds, topic, startNs, endNs, startSeq, endSeq);
    }

    private static String chainOf(List<MessageRow> rows) {
        String replayChecksum = "";
        for (MessageRow row : rows) {
            replayChecksum = Checksums.chain(replayChecksum, row.topic(), row.seq(),
                    row.timestampNs(), row.typeId(), row.payloadChecksum());
        }
        return replayChecksum;
    }

    private void verifySamePlayRequest(PlayRow saved, PlayRequest request) {
        if (!Objects.equals(saved.topic(), request.topic())
                || !Objects.equals(saved.startNs(), request.startNs())
                || !Objects.equals(saved.endNs(), request.endNs())
                || !Objects.equals(saved.startSeq(), request.startSeq())
                || !Objects.equals(saved.endSeq(), request.endSeq())) {
            throw new ConflictException("回放键 " + request.playKey()
                    + " 已存在但查询参数不一致，拒绝重复回放");
        }
    }

    // ---- 内部辅助 ------------------------------------------------------

    private void lockTopic(String topic) {
        try {
            jdbc.update("INSERT INTO bag_topic_lock (topic) VALUES (:topic)",
                    new MapSqlParameterSource("topic", topic));
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入下行加锁
        }
        jdbc.queryForObject("SELECT topic FROM bag_topic_lock WHERE topic = :topic FOR UPDATE",
                new MapSqlParameterSource("topic", topic), String.class);
    }

    private void verifySameParams(MessageRow existing, WriteMessageRequest request, String payloadChecksum) {
        if (existing.timestampNs() != request.timestampNs()
                || !Objects.equals(existing.typeId(), request.typeId())
                || !Objects.equals(existing.payloadChecksum(), payloadChecksum)) {
            throw new ConflictException("批次号 " + request.batchNo()
                    + " 已存在但消息参数（时间戳/类型/负载）不一致，拒绝重试写入");
        }
    }

    private void validateRange(Long startNs, Long endNs, Long startSeq, Long endSeq) {
        if (startNs != null && endNs != null && startNs > endNs) {
            throw new IllegalArgumentException("时间范围非法：startNs 不能大于 endNs");
        }
        if (startSeq != null && endSeq != null && startSeq > endSeq) {
            throw new IllegalArgumentException("序列号范围非法：startSeq 不能大于 endSeq");
        }
    }

    private static byte[] decodePayload(String payloadBase64) {
        if (payloadBase64 == null || payloadBase64.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("payloadBase64 不是合法的 Base64 内容", e);
        }
    }

    private static MessageView toView(MessageRow row) {
        return new MessageView(row.id(), row.topic(), row.timestampNs(), row.seq(), row.typeId(),
                Base64.getEncoder().encodeToString(row.payload() == null ? new byte[0] : row.payload()),
                row.payloadChecksum(), row.batchNo(), row.segmentId());
    }

    private static long requireId(MessageRow row) {
        if (row == null) {
            throw new IllegalStateException("消息写入后回查失败");
        }
        return row.id();
    }

    private static PlayRow requirePlay(PlayRow row) {
        if (row == null) {
            throw new IllegalStateException("回放记录写入后回查失败");
        }
        return row;
    }
}
