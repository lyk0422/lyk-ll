package com.example.starter.slam.optim;

import com.example.starter.slam.domain.EdgeType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link GraphSnapshot} 的 JSON 编解码：执行前固化快照文本，重试时从同一文本反序列化，
 * 保证重试不重新读图、不引入新数据。
 */
@Component
public class SnapshotCodec {

    private final ObjectMapper objectMapper;

    public SnapshotCodec() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    public String write(GraphSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(new SnapshotJson(
                    snapshot.graphId(),
                    snapshot.graphVersion(),
                    snapshot.frames().stream().map(f -> new FrameJson(
                            f.id(), f.source(), f.version(), f.pose())).toList(),
                    snapshot.edges().stream().map(e -> new EdgeJson(
                            e.id(), e.source(), e.version(), e.type().name(),
                            e.fromFrameId(), e.toFrameId(), e.measurement(), e.information())).toList()));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize graph snapshot", ex);
        }
    }

    public GraphSnapshot read(String json) {
        try {
            SnapshotJson parsed = objectMapper.readValue(json, SnapshotJson.class);
            List<FrameSnapshot> frames = parsed.frames().stream()
                    .map(f -> new FrameSnapshot(f.id(), f.source(), f.version(), f.pose()))
                    .toList();
            List<EdgeSnapshot> edges = parsed.edges().stream()
                    .map(e -> new EdgeSnapshot(e.id(), e.source(), e.version(),
                            EdgeType.valueOf(e.type()), e.fromFrameId(), e.toFrameId(),
                            e.measurement(), e.information()))
                    .toList();
            return new GraphSnapshot(parsed.graphId(), parsed.graphVersion(), frames, edges);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to deserialize graph snapshot", ex);
        }
    }

    private record SnapshotJson(String graphId, long graphVersion,
                                List<FrameJson> frames, List<EdgeJson> edges) {
    }

    private record FrameJson(String id, String source, long version, List<Double> pose) {
    }

    private record EdgeJson(String id, String source, long version, String type,
                            String fromFrameId, String toFrameId,
                            List<Double> measurement, List<Double> information) {
    }
}
