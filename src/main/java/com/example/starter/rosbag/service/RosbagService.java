package com.example.starter.rosbag.service;

import com.example.starter.rosbag.dto.*;
import com.example.starter.rosbag.exception.BatchConflictException;
import com.example.starter.rosbag.exception.SequenceRegressionException;
import com.example.starter.rosbag.model.Message;
import com.example.starter.rosbag.model.Segment;
import com.example.starter.rosbag.repository.MessageRepository;
import com.example.starter.rosbag.repository.ReplayRepository;
import com.example.starter.rosbag.repository.SegmentRepository;
import com.example.starter.rosbag.time.TimeSource;
import com.example.starter.rosbag.util.Checksums;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * rosbag 风格时序存储核心服务。
 *
 * <p>写入：按 Topic 取 OPEN 分段（行锁串行化同 Topic 追加），段满自动封段并开新段；
 * (topic, batch_no) 唯一约束保证同参重试不重复写入；Topic 内 seq 必须严格递增。
 *
 * <p>查询：先用分段边界粗筛命中段，再经 rb_message_index 精过滤，禁止全量消息入内存。
 *
 * <p>恢复：单事务内锁定全部分段，按段号/seq 顺序逐消息复验负载校验与连续性，
 * 保留最长可验证完整前缀，把首个坏点及其后的消息搬入隔离表，逐段重算边界与段级校验，
 * 删除并整体重建索引，最后恢复一个 OPEN 分段承接后续写入。
 */
@Service
public class RosbagService {

    private final SegmentRepository segmentRepository;
    private final MessageRepository messageRepository;
    private final ReplayRepository replayRepository;
    private final TimeSource timeSource;
    private final int segmentMaxMessages;

    public RosbagService(SegmentRepository segmentRepository,
                         MessageRepository messageRepository,
                         ReplayRepository replayRepository,
                         TimeSource timeSource,
                         @Value("${rosbag.segment-max-messages:1000}") int segmentMaxMessages) {
        this.segmentRepository = segmentRepository;
        this.messageRepository = messageRepository;
        this.replayRepository = replayRepository;
        this.timeSource = timeSource;
        this.segmentMaxMessages = segmentMaxMessages;
    }

    /**
     * 追加一条消息；同批次号同参数重试直接返回首次结果，不重复写入。
     */
    @Transactional
    public WriteResult append(WriteMessageRequest request) {
        String topic = request.topic();
        long seq = request.seq();
        long timestampNs = request.timestampNs();
        String typeId = request.typeId();
        String payload = request.payload();
        String batchNo = request.batchNo();
        long payloadChecksum = Checksums.crc32(payload);

        // 先查批次幂等（命中时无需竞争分段锁）
        Message existing = messageRepository.findByTopicAndBatch(topic, batchNo);
        if (existing != null) {
            return dedupOrConflict(existing, request, payloadChecksum);
        }

        // OPEN 分段行锁串行化同一 Topic 的所有追加
        Segment open = segmentRepository.findOpenForUpdate(topic);
        if (open == null) {
            open = obtainOpenSegment(topic);
        } else if (open.messageCount() >= segmentMaxMessages) {
            segmentRepository.updateAggregatesAndStatus(open.id(), open.startNs(), open.endNs(),
                    open.startSeq(), open.endSeq(), open.messageCount(), open.segmentChecksum(),
                    Segment.SEALED);
            open = obtainOpenSegment(topic);
        }

        // 取锁后再次确认批次，防止并发同批次双写
        existing = messageRepository.findByTopicAndBatch(topic, batchNo);
        if (existing != null) {
            return dedupOrConflict(existing, request, payloadChecksum);
        }

        Long maxSeq = messageRepository.findMaxValidSeq(topic);
        if (maxSeq != null && seq <= maxSeq) {
            throw new SequenceRegressionException(topic, seq, maxSeq);
        }

        long messageId;
        try {
            messageId = messageRepository.insert(topic, seq, timestampNs, typeId, payload,
                    payloadChecksum, batchNo, open.id());
        } catch (DuplicateKeyException ex) {
            // 唯一约束兜底：并发同批次或同 seq
            Message raced = messageRepository.findByTopicAndBatch(topic, batchNo);
            if (raced != null) {
                return dedupOrConflict(raced, request, payloadChecksum);
            }
            Long currentMax = messageRepository.findMaxValidSeq(topic);
            long bound = currentMax == null ? Long.MIN_VALUE : currentMax;
            throw new SequenceRegressionException(topic, seq, bound);
        }
        messageRepository.insertIndex(messageId, topic, open.id(), timestampNs, seq);

        long newChecksum = Checksums.foldMessage(open.segmentChecksum(), topic, seq, timestampNs,
                typeId, payloadChecksum);
        long newCount = open.messageCount() + 1;
        long startNs = open.startNs() == null ? timestampNs : Math.min(open.startNs(), timestampNs);
        long endNs = open.endNs() == null ? timestampNs : Math.max(open.endNs(), timestampNs);
        long startSeq = open.startSeq() == null ? seq : Math.min(open.startSeq(), seq);
        long endSeq = open.endSeq() == null ? seq : Math.max(open.endSeq(), seq);
        segmentRepository.updateAggregates(open.id(), startNs, endNs, startSeq, endSeq,
                newCount, newChecksum);

        return new WriteResult(topic, seq, open.id(), open.segmentNo(), batchNo, payloadChecksum, false);
    }

    /**
     * 获取（必要时创建）Topic 当前 OPEN 分段。并发首写/并发封段时，
     * (topic, segment_no) 唯一约束可能被竞态触发，此时重读已存在的 OPEN 段。
     */
    private Segment obtainOpenSegment(String topic) {
        Segment existing = segmentRepository.findOpenForUpdate(topic);
        if (existing != null) {
            return existing;
        }
        try {
            return segmentRepository.createOpen(topic, segmentRepository.maxSegmentNo(topic) + 1);
        } catch (DuplicateKeyException ex) {
            Segment raced = segmentRepository.findOpenForUpdate(topic);
            if (raced == null) {
                // 与并发段号竞争但对方未留下 OPEN 段（理论上不会发生），取最新段
                long maxNo = segmentRepository.maxSegmentNo(topic);
                raced = segmentRepository.createOpen(topic, maxNo + 1);
            }
            return raced;
        }
    }

    private WriteResult dedupOrConflict(Message existing, WriteMessageRequest request, long incomingChecksum) {
        boolean same = existing.seq() == request.seq()
                && existing.timestampNs() == request.timestampNs()
                && existing.typeId().equals(request.typeId())
                && existing.payloadChecksum() == incomingChecksum;
        if (!same) {
            throw new BatchConflictException(request.batchNo());
        }
        Segment segment = segmentRepository.findByIdForUpdate(existing.segmentId());
        long segmentNo = segment == null ? -1L : segment.segmentNo();
        return new WriteResult(existing.topic(), existing.seq(), existing.segmentId(), segmentNo,
                existing.batchNo(), existing.payloadChecksum(), true);
    }

    /**
     * 组合查询：先按分段边界定位候选段，再在索引表做时间/序列精过滤。
     */
    @Transactional(readOnly = true)
    public List<MessageView> query(QueryRequest request) {
        String topic = request.topic();
        List<Segment> candidates = segmentRepository.findCandidateSegments(topic,
                request.fromNs(), request.toNs(), request.fromSeq(), request.toSeq());
        List<Long> segmentIds = candidates.stream().map(Segment::id).toList();
        return messageRepository.queryViaIndex(topic, segmentIds,
                request.fromNs(), request.toNs(), request.fromSeq(), request.toSeq());
    }

    /**
     * 按原顺序（timestamp_ns, seq）回放；重复 replayKey 返回原消息但不重复建记录。
     */
    @Transactional
    public ReplayResult replay(ReplayRequest request) {
        var existingRecord = replayRepository.findByKey(request.replayKey());
        List<MessageView> messages = query(new QueryRequest(request.topic(),
                request.fromNs(), request.toNs(), null, null));
        if (existingRecord != null) {
            return new ReplayResult(request.replayKey(), true,
                    existingRecord.messageCount(), messages);
        }
        try {
            replayRepository.insert(request.replayKey(), request.topic(), request.fromNs(),
                    request.toNs(), messages.size(), timeSource.nowEpochNs());
        } catch (DuplicateKeyException ex) {
            var raced = replayRepository.findByKey(request.replayKey());
            return new ReplayResult(request.replayKey(), true,
                    raced == null ? messages.size() : raced.messageCount(), messages);
        }
        return new ReplayResult(request.replayKey(), false, messages.size(), messages);
    }

    /**
     * 故障注入：破坏指定消息的负载内容（追加一个字符），使其负载校验值不再匹配，
     * 模拟中断后出现的脏写/torn write。返回是否实际命中并修改。
     */
    @Transactional
    public boolean simulateCorruptedMessage(String topic, long seq) {
        return messageRepository.corruptPayload(topic, seq) > 0;
    }

    /**
     * 故障注入：删除指定消息的索引行但保留消息，模拟索引损坏/缺失，查询将漏数据。
     * 返回删除的索引行数。
     */
    @Transactional
    public int simulateCorruptedIndex(String topic, long seq) {
        return messageRepository.deleteIndexRow(topic, seq);
    }

    /**
     * 恢复：同一事务内保留可验证完整前缀、隔离不完整消息并重建索引。
     */
    @Transactional
    public RecoveryResult recover(String topic) {
        // 按段序号加锁，与追加路径的加锁顺序保持一致，避免死锁
        List<Segment> segments = segmentRepository.findByTopicForUpdate(topic);

        // 索引可能已损坏：先整体删除，稍后只按经验证的消息重建
        messageRepository.deleteIndexForTopic(topic);

        // 第一遍：按段顺序、段内 seq 顺序定位首个坏点（负载校验失败或 seq 不连续）
        Long expectedNextSeq = null;
        Long lastGoodSeq = null;
        boolean corruptionFound = false;
        long firstBadSeq = -1L;
        outer:
        for (Segment segment : segments) {
            List<Message> messages = messageRepository.findValidFullBySegment(segment.id());
            for (Message message : messages) {
                boolean checksumOk = Checksums.crc32(message.payload()) == message.payloadChecksum();
                boolean contiguous = expectedNextSeq == null || message.seq() == expectedNextSeq;
                if (!checksumOk || !contiguous) {
                    corruptionFound = true;
                    firstBadSeq = message.seq();
                    break outer;
                }
                expectedNextSeq = message.seq() + 1;
                lastGoodSeq = message.seq();
            }
        }

        // 从首个坏点起（含其后所有段的消息）整体搬入隔离表
        long quarantined = 0L;
        if (corruptionFound) {
            long keepUpTo = lastGoodSeq == null ? Long.MIN_VALUE : lastGoodSeq;
            quarantined = messageRepository.quarantineTail(topic, keepUpTo,
                    timeSource.nowEpochNs(),
                    "首个未通过负载校验/连续性验证的消息 seq=" + firstBadSeq + " 及其后全部消息");
        }

        // 第二遍：用实际保留的有效消息逐段重算边界、消息数与段级校验；
        // 经恢复的段标记 RECOVERED，原本 OPEN 的段最后重新置回 OPEN 承接写入。
        Long openSegmentId = segments.stream()
                .filter(s -> Segment.OPEN.equals(s.status()))
                .map(Segment::id)
                .findFirst()
                .orElse(null);
        long kept = 0L;
        for (Segment segment : segments) {
            List<Message> remaining = messageRepository.findValidFullBySegment(segment.id());
            long checksum = 0L;
            for (Message message : remaining) {
                checksum = Checksums.foldMessage(checksum, message.topic(), message.seq(),
                        message.timestampNs(), message.typeId(), message.payloadChecksum());
            }
            kept += remaining.size();
            String targetStatus;
            if (segment.id().equals(openSegmentId)) {
                targetStatus = Segment.OPEN;
            } else if (corruptionFound) {
                // 发生过截断：非可写段统一标记为经恢复重建
                targetStatus = Segment.RECOVERED;
            } else {
                // 仅索引重建等无损恢复：保留段原状态
                targetStatus = segment.status();
            }
            rewriteSegmentFromMessages(segment, remaining, checksum, targetStatus);
        }

        // 原 OPEN 段不存在（理论上不会发生）则在末尾新开一段
        if (openSegmentId == null) {
            long maxNo = segments.stream().mapToLong(Segment::segmentNo).max().orElse(-1L);
            segmentRepository.createOpen(topic, maxNo + 1);
        }

        // 用保留下来的完整消息重建索引
        long rebuilt = messageRepository.rebuildIndexForTopic(topic);

        List<SegmentView> views = segmentRepository.findByTopic(topic).stream()
                .map(this::toView)
                .toList();
        return new RecoveryResult(topic, segments.size(), kept, quarantined, rebuilt, views);
    }

    /**
     * 用实际保留的消息重写段边界、消息数、段级校验与状态。
     */
    private void rewriteSegmentFromMessages(Segment segment, List<Message> kept, long checksum,
                                            String status) {
        if (kept.isEmpty()) {
            segmentRepository.updateAggregatesAndStatus(segment.id(), null, null, null, null,
                    0L, 0L, status);
            return;
        }
        long startNs = Long.MAX_VALUE;
        long endNs = Long.MIN_VALUE;
        long startSeq = Long.MAX_VALUE;
        long endSeq = Long.MIN_VALUE;
        for (Message message : kept) {
            startNs = Math.min(startNs, message.timestampNs());
            endNs = Math.max(endNs, message.timestampNs());
            startSeq = Math.min(startSeq, message.seq());
            endSeq = Math.max(endSeq, message.seq());
        }
        segmentRepository.updateAggregatesAndStatus(segment.id(), startNs, endNs, startSeq, endSeq,
                kept.size(), checksum, status);
    }

    /**
     * 列出某 Topic 的分段元数据。
     */
    @Transactional(readOnly = true)
    public List<SegmentView> segments(String topic) {
        List<SegmentView> views = new ArrayList<>();
        for (Segment segment : segmentRepository.findByTopic(topic)) {
            views.add(toView(segment));
        }
        return views;
    }

    private SegmentView toView(Segment segment) {
        return new SegmentView(segment.id(), segment.topic(), segment.segmentNo(),
                segment.startNs(), segment.endNs(), segment.startSeq(), segment.endSeq(),
                segment.messageCount(), segment.status(), segment.segmentChecksum());
    }
}
