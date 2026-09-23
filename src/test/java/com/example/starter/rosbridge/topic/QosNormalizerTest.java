package com.example.starter.rosbridge.topic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link QosNormalizer} 单元测试：字段顺序不同的等价 QoS 必须规范化为同一摘要。
 */
class QosNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QosNormalizer normalizer = new QosNormalizer(mapper);

    @Test
    void keyOrderDoesNotAffectCanonicalHash() throws Exception {
        QosNormalizer.NormalizedQos a = normalizer.normalize(
                node("{\"depth\":10,\"reliability\":\"reliable\"}"));
        QosNormalizer.NormalizedQos b = normalizer.normalize(
                node("{\"reliability\":\"reliable\",\"depth\":10}"));
        assertEquals(a.hash(), b.hash(), "键顺序不同的等价 QoS 摘要必须一致");
        assertEquals(a.canonicalJson(), b.canonicalJson());
    }

    @Test
    void differentQosValuesHaveDifferentHash() throws Exception {
        QosNormalizer.NormalizedQos deep10 = normalizer.normalize(node("{\"depth\":10}"));
        QosNormalizer.NormalizedQos deep1 = normalizer.normalize(node("{\"depth\":1}"));
        assertNotEquals(deep10.hash(), deep1.hash());
    }

    @Test
    void nullQosNormalizesToEmptyObject() {
        QosNormalizer.NormalizedQos q = normalizer.normalize(null);
        assertEquals("{}", q.canonicalJson());
    }

    private JsonNode node(String json) throws Exception {
        return mapper.readTree(json);
    }
}
