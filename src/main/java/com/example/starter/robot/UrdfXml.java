package com.example.starter.robot;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性 URDF XML 序列化：固定 LF 换行、两空格缩进、属性按名排序，
 * 不输出时间、绝对路径等环境相关内容，保证相同文档产生相同字节。
 */
final class UrdfXml {

    private static final String INDENT = "  ";

    private UrdfXml() {
    }

    static String serialize(Document document) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writeElement(sb, document.getDocumentElement(), 0);
        return sb.toString();
    }

    private static void writeElement(StringBuilder sb, Element element, int depth) {
        indent(sb, depth);
        sb.append('<').append(element.getTagName());
        List<Attr> attrs = sortedAttributes(element);
        for (Attr attr : attrs) {
            sb.append(' ').append(attr.getName()).append("=\"")
                    .append(escapeAttribute(attr.getValue())).append('"');
        }

        List<Node> childNodes = meaningfulChildren(element);
        boolean onlyText = !childNodes.isEmpty()
                && childNodes.stream().allMatch(n -> n.getNodeType() == Node.TEXT_NODE
                || n.getNodeType() == Node.CDATA_SECTION_NODE);

        if (childNodes.isEmpty()) {
            sb.append("/>\n");
            return;
        }
        sb.append('>');
        if (onlyText) {
            StringBuilder text = new StringBuilder();
            for (Node node : childNodes) {
                text.append(node.getNodeValue());
            }
            sb.append(escapeText(text.toString().trim())).append("</").append(element.getTagName()).append(">\n");
            return;
        }
        sb.append('\n');
        for (Node node : childNodes) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                writeElement(sb, (Element) node, depth + 1);
            } else {
                String value = node.getNodeValue().trim();
                if (!value.isEmpty()) {
                    indent(sb, depth + 1);
                    sb.append(escapeText(value)).append('\n');
                }
            }
        }
        indent(sb, depth);
        sb.append("</").append(element.getTagName()).append(">\n");
    }

    private static List<Attr> sortedAttributes(Element element) {
        NamedNodeMap map = element.getAttributes();
        List<Attr> attrs = new ArrayList<>(map.getLength());
        for (int i = 0; i < map.getLength(); i++) {
            attrs.add((Attr) map.item(i));
        }
        attrs.sort((a, b) -> a.getName().compareTo(b.getName()));
        return attrs;
    }

    private static List<Node> meaningfulChildren(Element element) {
        NodeList nodes = element.getChildNodes();
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add(node);
            } else if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !node.getNodeValue().isBlank()) {
                result.add(node);
            }
        }
        return result;
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append(INDENT.repeat(depth));
    }

    private static String escapeAttribute(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
