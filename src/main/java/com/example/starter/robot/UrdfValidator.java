package com.example.starter.robot;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 展开后 URDF 结构与数值校验器。
 *
 * <p>校验项：根节点为 robot；Link/Joint 名称非空且各自唯一；Joint type 合法；
 * parent/child 引用存在且互异；恰有一个根 Link；关节图无环、无多父；
 * 坐标与量纲数值均为有限数；惯量主对角项 ixx/iyy/izz 严格为正；
 * visual/collision 的 origin 坐标分量完整、geometry 结构完整。
 */
@Component
public class UrdfValidator {

    private static final Set<String> JOINT_TYPES = Set.of(
            "revolute", "continuous", "prismatic", "fixed", "floating", "planar");
    private static final Set<String> GEOMETRY_TYPES = Set.of("box", "cylinder", "sphere", "mesh");

    private final DocumentBuilder documentBuilder;

    public UrdfValidator() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 解析器初始化失败", e);
        }
    }

    /**
     * 校验 URDF，不合法时抛出 {@link CompileException}（INVALID_URDF）。
     *
     * @param urdf 展开后的 URDF 文本
     */
    public void validate(String urdf) {
        Document doc;
        try {
            synchronized (documentBuilder) {
                doc = documentBuilder.parse(new ByteArrayInputStream(urdf.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (SAXException | IOException e) {
            throw invalid("展开结果不是合法 XML: " + e.getMessage());
        }

        Element root = doc.getDocumentElement();
        if (!"robot".equals(root.getLocalName() != null ? root.getLocalName() : root.getTagName())) {
            throw invalid("根元素必须是 <robot>，实际为 <" + root.getTagName() + ">");
        }
        String robotName = root.getAttribute("name");
        if (robotName.isBlank()) {
            throw invalid("<robot> 必须声明非空 name 属性");
        }

        List<Element> links = new ArrayList<>();
        List<Element> joints = new ArrayList<>();
        for (Element child : childElements(root)) {
            String name = child.getLocalName() != null ? child.getLocalName() : child.getTagName();
            if ("link".equals(name)) {
                links.add(child);
            } else if ("joint".equals(name)) {
                joints.add(child);
            }
        }
        if (links.isEmpty()) {
            throw invalid("robot 至少需要一个 link");
        }

        validateNames(links, "link");
        validateNames(joints, "joint");

        Set<String> linkNames = new HashSet<>();
        for (Element link : links) {
            linkNames.add(link.getAttribute("name"));
        }

        Map<String, String> jointParent = new HashMap<>();
        Map<String, List<String>> childrenByParent = new HashMap<>();
        Set<String> childLinks = new HashSet<>();

        for (Element joint : joints) {
            String jointName = joint.getAttribute("name");
            String type = joint.getAttribute("type");
            if (!JOINT_TYPES.contains(type)) {
                throw invalid("joint '" + jointName + "' 的 type 非法: " + type);
            }

            List<Element> parents = childrenNamed(joint, "parent");
            List<Element> childs = childrenNamed(joint, "child");
            if (parents.size() != 1 || childs.size() != 1) {
                throw invalid("joint '" + jointName + "' 必须恰有一个 parent 和一个 child");
            }
            String parentName = requiredRef(parents.get(0), jointName, "parent");
            String childName = requiredRef(childs.get(0), jointName, "child");
            if (!linkNames.contains(parentName)) {
                throw invalid("joint '" + jointName + "' 的 parent link 不存在: " + parentName);
            }
            if (!linkNames.contains(childName)) {
                throw invalid("joint '" + jointName + "' 的 child link 不存在: " + childName);
            }
            if (parentName.equals(childName)) {
                throw invalid("joint '" + jointName + "' 的 parent 与 child 不能相同: " + parentName);
            }
            if (!childLinks.add(childName)) {
                throw invalid("link '" + childName + "' 被多个 joint 作为 child，关节图存在多父或环");
            }
            jointParent.put(childName, parentName);
            childrenByParent.computeIfAbsent(parentName, k -> new ArrayList<>()).add(childName);

            validateJointNumeric(joint, jointName, type);
        }

        List<String> roots = new ArrayList<>();
        for (String linkName : linkNames) {
            if (!childLinks.contains(linkName)) {
                roots.add(linkName);
            }
        }
        if (roots.size() != 1) {
            throw invalid("必须恰有一个根 link，实际有 " + roots.size() + " 个: " + roots);
        }

        detectCycle(linkNames, jointParent, childrenByParent);

        for (Element link : links) {
            validateLink(link);
        }
    }

    private void validateNames(List<Element> elements, String kind) {
        Set<String> seen = new HashSet<>();
        for (Element element : elements) {
            String name = element.getAttribute("name");
            if (name.isBlank()) {
                throw invalid(kind + " 必须声明非空 name 属性");
            }
            if (!seen.add(name)) {
                throw invalid("存在重复的 " + kind + " 名称: " + name);
            }
        }
    }

    private String requiredRef(Element refElement, String jointName, String kind) {
        String link = refElement.getAttribute("link");
        if (link.isBlank()) {
            throw invalid("joint '" + jointName + "' 的 " + kind + " 缺少 link 引用");
        }
        return link;
    }

    private void validateJointNumeric(Element joint, String jointName, String type) {
        Element origin = firstChildNamed(joint, "origin");
        if (origin != null) {
            validateVector(origin, "xyz", 3, jointName, "joint origin", false);
            validateVector(origin, "rpy", 3, jointName, "joint origin", false);
        }
        Element axis = firstChildNamed(joint, "axis");
        if (axis != null) {
            validateAxis(axis, jointName);
        }
        Element limit = firstChildNamed(joint, "limit");
        if (limit != null) {
            double effort = requiredFinite(limit, "effort", jointName, "joint limit");
            double velocity = requiredFinite(limit, "velocity", jointName, "joint limit");
            if (effort < 0 || velocity < 0) {
                throw invalid("joint '" + jointName + "' limit 的 effort/velocity 不能为负");
            }
            optionalFinite(limit, "lower", jointName, "joint limit");
            optionalFinite(limit, "upper", jointName, "joint limit");
        } else if ("revolute".equals(type) || "prismatic".equals(type)) {
            throw invalid("joint '" + jointName + "'（" + type + "）必须声明 <limit>");
        }
        Element dynamics = firstChildNamed(joint, "dynamics");
        if (dynamics != null) {
            optionalFinite(dynamics, "damping", jointName, "joint dynamics");
            optionalFinite(dynamics, "friction", jointName, "joint dynamics");
        }
        Element mimic = firstChildNamed(joint, "mimic");
        if (mimic != null && mimic.getAttribute("joint").isBlank()) {
            throw invalid("joint '" + jointName + "' 的 mimic 缺少 joint 引用");
        }
    }

    private void validateLink(Element link) {
        String linkName = link.getAttribute("name");

        Element inertial = firstChildNamed(link, "inertial");
        if (inertial != null) {
            Element inertialOrigin = firstChildNamed(inertial, "origin");
            if (inertialOrigin != null) {
                validateVector(inertialOrigin, "xyz", 3, linkName, "inertial origin", false);
                validateVector(inertialOrigin, "rpy", 3, linkName, "inertial origin", false);
            }
            Element mass = firstChildNamed(inertial, "mass");
            if (mass == null) {
                throw invalid("link '" + linkName + "' 的 inertial 缺少 <mass>");
            }
            double massValue = requiredFinite(mass, "value", linkName, "inertial mass");
            if (massValue < 0) {
                throw invalid("link '" + linkName + "' 的 mass 不能为负");
            }
            Element inertia = firstChildNamed(inertial, "inertia");
            if (inertia == null) {
                throw invalid("link '" + linkName + "' 的 inertial 缺少 <inertia>");
            }
            for (String component : List.of("ixx", "ixy", "ixz", "iyy", "iyz", "izz")) {
                requiredFinite(inertia, component, linkName, "inertia");
            }
            double ixx = parseDouble(inertia.getAttribute("ixx"));
            double iyy = parseDouble(inertia.getAttribute("iyy"));
            double izz = parseDouble(inertia.getAttribute("izz"));
            if (!(ixx > 0 && iyy > 0 && izz > 0)) {
                throw invalid("link '" + linkName + "' 的惯量主对角项必须为正: ixx=" + ixx
                        + ", iyy=" + iyy + ", izz=" + izz);
            }
        }

        for (String kind : List.of("visual", "collision")) {
            for (Element element : childrenNamed(link, kind)) {
                validateVisualOrCollision(element, linkName, kind);
            }
        }
    }

    private void validateVisualOrCollision(Element element, String linkName, String kind) {
        String descriptor = "link '" + linkName + "' 的 " + kind
                + (element.getAttribute("name").isBlank() ? "" : " '" + element.getAttribute("name") + "'");
        Element origin = firstChildNamed(element, "origin");
        if (origin != null) {
            validateVector(origin, "xyz", 3, descriptor, "origin", false);
            validateVector(origin, "rpy", 3, descriptor, "origin", false);
        }
        Element geometry = firstChildNamed(element, "geometry");
        if (geometry == null) {
            throw invalid(descriptor + " 缺少 <geometry>，碰撞/视觉几何引用不完整");
        }
        List<Element> geometryChildren = childElements(geometry);
        if (geometryChildren.size() != 1) {
            throw invalid(descriptor + " 的 geometry 必须恰含一种几何形状");
        }
        Element shape = geometryChildren.get(0);
        String shapeName = shape.getLocalName() != null ? shape.getLocalName() : shape.getTagName();
        if (!GEOMETRY_TYPES.contains(shapeName)) {
            throw invalid(descriptor + " 的 geometry 类型非法: " + shapeName);
        }
        switch (shapeName) {
            case "box" -> validateVector(shape, "size", 3, descriptor, "box size", true);
            case "cylinder" -> {
                double radius = requiredFinite(shape, "radius", descriptor, "cylinder");
                double length = requiredFinite(shape, "length", descriptor, "cylinder");
                if (!(radius > 0 && length > 0)) {
                    throw invalid(descriptor + " cylinder 的 radius/length 必须为正");
                }
            }
            case "sphere" -> {
                double radius = requiredFinite(shape, "radius", descriptor, "sphere");
                if (!(radius > 0)) {
                    throw invalid(descriptor + " sphere 的 radius 必须为正");
                }
            }
            case "mesh" -> {
                if (shape.getAttribute("filename").isBlank()) {
                    throw invalid(descriptor + " mesh 缺少 filename 引用");
                }
                if (shape.hasAttribute("scale")) {
                    validateVector(shape, "scale", 3, descriptor, "mesh scale", false);
                }
            }
            default -> throw invalid(descriptor + " 不支持的 geometry: " + shapeName);
        }
    }

    /** 自根 link 出发做可达性遍历，并检测是否存在回到已访问节点的边。 */
    private void detectCycle(Set<String> linkNames, Map<String, String> jointParent,
                             Map<String, List<String>> childrenByParent) {
        String root = linkNames.stream()
                .filter(name -> !jointParent.containsKey(name))
                .findFirst()
                .orElseThrow(() -> invalid("关节图中找不到根 link"));
        Set<String> visited = new HashSet<>();
        walk(root, childrenByParent, visited, new HashSet<>());
        if (visited.size() != linkNames.size()) {
            List<String> unreachable = linkNames.stream().filter(n -> !visited.contains(n)).sorted().toList();
            throw invalid("存在与根 link 不连通的关节环: " + unreachable);
        }
    }

    private void walk(String link, Map<String, List<String>> childrenByParent,
                      Set<String> visited, Set<String> onPath) {
        if (!onPath.add(link)) {
            throw invalid("关节图存在环，重复到达 link: " + link);
        }
        visited.add(link);
        for (String child : childrenByParent.getOrDefault(link, List.of())) {
            walk(child, childrenByParent, visited, onPath);
        }
        onPath.remove(link);
    }

    /** 关节轴：三个有限分量且不能为零向量（允许 0 与负分量，如 0 0 1）。 */
    private void validateAxis(Element axis, String jointName) {
        String raw = axis.getAttribute("xyz");
        if (raw.isBlank()) {
            throw invalid("joint '" + jointName + "' 的 axis 缺少 xyz 属性");
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != 3) {
            throw invalid("joint '" + jointName + "' 的 axis xyz 需要 3 个分量，实际 "
                    + parts.length + ": " + raw);
        }
        double squared = 0;
        for (String part : parts) {
            double value = parseDoubleOrThrow(part, "joint '" + jointName + "' 的 axis xyz");
            squared += value * value;
        }
        if (!(squared > 0)) {
            throw invalid("joint '" + jointName + "' 的 axis 不能为零向量: " + raw);
        }
    }

    private void validateVector(Element element, String attr, int components, String owner,
                                String field, boolean strictlyPositive) {
        String raw = element.getAttribute(attr);
        if (raw.isBlank()) {
            throw invalid(owner + " 的 " + field + " 缺少 " + attr + " 属性");
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != components) {
            throw invalid(owner + " 的 " + field + " " + attr + " 需要 " + components + " 个分量，实际 "
                    + parts.length + ": " + raw);
        }
        for (String part : parts) {
            double value = parseDoubleOrThrow(part, owner + " 的 " + field + " " + attr);
            if (!Double.isFinite(value)) {
                throw invalid(owner + " 的 " + field + " " + attr + " 含非有限数值: " + raw);
            }
            if (strictlyPositive && !(value > 0)) {
                throw invalid(owner + " 的 " + field + " " + attr + " 各分量必须为正: " + raw);
            }
        }
    }

    private double requiredFinite(Element element, String attr, String owner, String field) {
        String raw = element.getAttribute(attr);
        if (raw.isBlank()) {
            throw invalid(owner + " 的 " + field + " 缺少 " + attr + " 属性");
        }
        return parseDoubleOrThrow(raw, owner + " 的 " + field + " " + attr);
    }

    private void optionalFinite(Element element, String attr, String owner, String field) {
        String raw = element.getAttribute(attr);
        if (!raw.isBlank()) {
            parseDoubleOrThrow(raw, owner + " 的 " + field + " " + attr);
        }
    }

    private double parseDoubleOrThrow(String raw, String descriptor) {
        double value = parseDouble(raw);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw invalid(descriptor + " 不是有限数值: " + raw);
        }
        return value;
    }

    private double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid("无法解析数值: " + raw);
        }
    }

    private CompileException invalid(String message) {
        return new CompileException(ErrorCode.INVALID_URDF, message);
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) nodes.item(i);
                result.add(element);
            }
        }
        return result;
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        return childElements(parent).stream()
                .filter(e -> name.equals(e.getLocalName() != null ? e.getLocalName() : e.getTagName()))
                .toList();
    }

    private static Element firstChildNamed(Element parent, String name) {
        List<Element> found = childrenNamed(parent, name);
        return found.isEmpty() ? null : found.get(0);
    }
}
