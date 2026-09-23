package com.example.starter.compiler.urdf;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * URDF 规范化序列化器。
 *
 * <p>规范化规则（确定性，不含时间戳或绝对临时路径）：
 * <ul>
 *   <li>固定 XML 声明 {@code <?xml version="1.0" encoding="UTF-8"?>}，LF 行尾，2 空格缩进；</li>
 *   <li>元素按文档顺序保留（include 展开顺序确定），属性按名称字典序排列；</li>
 *   <li>属性值按 XML 1.0 规则转义；元素间空白文本一律忽略（URDF 无意义空白）；</li>
 *   <li>无内容元素输出自闭合形式。</li>
 * </ul>
 * 相同的展开结果必然产生相同字节，从而得到稳定 SHA-256。
 */
@Component
public class DefaultUrdfCanonicalizer implements UrdfCanonicalizer {

    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String INDENT = "  ";

    private final DocumentBuilder documentBuilder;

    public DefaultUrdfCanonicalizer() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("XML 处理器初始化失败", e);
        }
    }

    @Override
    public byte[] canonicalize(String expandedXml) {
        Document document;
        try {
            synchronized (documentBuilder) {
                document = documentBuilder.parse(new ByteArrayInputStream(expandedXml.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("规范化输入不是合法 XML: " + e.getMessage(), e);
        }

        StringBuilder out = new StringBuilder(XML_DECLARATION);
        appendElement(document.getDocumentElement(), 0, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendElement(Element element, int depth, StringBuilder out) {
        String indent = INDENT.repeat(depth);
        out.append(indent).append('<').append(element.getTagName());
        for (String attributeName : sortedAttributeNames(element)) {
            String value = element.getAttribute(attributeName);
            out.append(' ').append(attributeName).append("=\"").append(escapeAttribute(value)).append('"');
        }

        List<Node> significantChildren = significantChildren(element);
        if (significantChildren.isEmpty()) {
            String text = element.getTextContent();
            if (text != null && !text.isBlank()) {
                // URDF 元素不含有意义文本；保留非空文本以防有自定义叶子元素，仍确定性输出。
                out.append('>').append(escapeText(text.trim())).append("</").append(element.getTagName()).append(">\n");
            } else {
                out.append("/>\n");
            }
            return;
        }

        out.append(">\n");
        for (Node child : significantChildren) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                appendElement((Element) child, depth + 1, out);
            } else if (child.getNodeType() == Node.TEXT_NODE && !child.getNodeValue().isBlank()) {
                out.append(INDENT.repeat(depth + 1))
                        .append(escapeText(child.getNodeValue().trim()))
                        .append('\n');
            }
        }
        out.append(indent).append("</").append(element.getTagName()).append(">\n");
    }

    private List<Node> significantChildren(Element element) {
        List<Node> children = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                children.add(node);
            } else if (node.getNodeType() == Node.TEXT_NODE && !node.getNodeValue().isBlank()) {
                children.add(node);
            }
        }
        return children;
    }

    private List<String> sortedAttributeNames(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        List<String> names = new ArrayList<>(attributes.getLength());
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            // 命名空间声明不进入规范输出（展开阶段 xacro 声明已移除，这里再兜底过滤）。
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                continue;
            }
            names.add(attribute.getNodeName());
        }
        names.sort(String::compareTo);
        return names;
    }

    private String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace("\"", "&quot;")
                .replace("\t", "&#x9;")
                .replace("\n", "&#xA;")
                .replace("\r", "&#xD;");
    }

    private String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;");
    }
}
