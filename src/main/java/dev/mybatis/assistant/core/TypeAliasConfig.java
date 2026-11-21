package dev.mybatis.assistant.core;

import java.util.*;
import java.util.regex.*;
import org.w3c.dom.*;

/** Extracts only literal configuration. Spring placeholders and package globs stay unresolved. */
public final class TypeAliasConfig {
    private TypeAliasConfig() {}
    public record Config(Map<String,String> aliases, List<String> packages) {}
    public static Config parse(String text, String filename) {
        Map<String,String> aliases = new LinkedHashMap<>();
        Set<String> packages = new LinkedHashSet<>();
        if (text.length() > 1048576) return new Config(Map.of(), List.of());
        if (filename.endsWith(".xml")) {
            if (!text.contains("<configuration")) return new Config(Map.of(), List.of());
            try {
                Element root = SqlRenderer.parseXml(text).getDocumentElement();
                if (!root.getTagName().equals("configuration")) return new Config(Map.of(), List.of());
                NodeList groups = root.getElementsByTagName("typeAliases");
                for (int i = 0; i < groups.getLength(); i++) {
                    for (Node n = groups.item(i).getFirstChild(); n != null; n = n.getNextSibling()) {
                        if (!(n instanceof Element e)) continue;
                        if (e.getTagName().equals("package")) addPackages(packages, e.getAttribute("name"));
                        if (e.getTagName().equals("typeAlias")) {
                            String type = e.getAttribute("type"), alias = e.getAttribute("alias");
                            if (alias.isBlank()) alias = "#implicit:"+type;
                            if (!type.isBlank() && !type.contains("${")) aliases.put(alias.toLowerCase(Locale.ROOT), type);
                        }
                    }
                }
            } catch (Exception ignored) { /* Incomplete config is re-indexed when edited. */ }
        } else if (filename.endsWith(".properties")) {
            try {
                Properties p = new Properties(); p.load(new java.io.StringReader(text));
                for (String key : List.of("mybatis.type-aliases-package", "mybatis-plus.type-aliases-package", "mybatis.typeAliasesPackage", "mybatis-plus.typeAliasesPackage")) addPackages(packages, p.getProperty(key, ""));
            } catch (java.io.IOException ignored) {}
        } else {
            String section = "";
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (!Character.isWhitespace(line.charAt(0))) {
                    section = line.strip().replace(":", "");
                    if (!section.equals("mybatis") && !section.equals("mybatis-plus")) section = "";
                } else if (!section.isEmpty()) {
                    Matcher m = Pattern.compile("^\\s+(?:type-aliases-package|typeAliasesPackage)\\s*:\\s*(.+?)\\s*(?:#.*)?$").matcher(line);
                    if (m.matches()) addPackages(packages, m.group(1));
                }
            }
        }
        return new Config(Map.copyOf(aliases), List.copyOf(packages));
    }
    private static void addPackages(Set<String> target, String value) {
        value = value.trim();
        if (value.length() > 1 && (value.charAt(0) == '\'' || value.charAt(0) == '"')) value = value.substring(1, value.length() - 1);
        for (String item : value.split("[,;\\s]+")) if (item.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")) target.add(item);
    }
}
