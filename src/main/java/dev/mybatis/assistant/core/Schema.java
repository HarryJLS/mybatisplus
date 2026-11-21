package dev.mybatis.assistant.core;

import java.util.*;
import javax.lang.model.SourceVersion;

public final class Schema {
    private Schema() {}
    public record Column(String name, String sqlType, boolean primaryKey, boolean autoIncrement, boolean nullable) {
        public String property() { return camel(name, false); }
        public String javaType() { return javaTypeFor(sqlType); }
    }
    public record Table(String name, List<Column> columns) {
        public Table { columns = List.copyOf(columns); }
        public String className() { return camel(name, true); }
    }
    public static String camel(String input, boolean upper) {
        StringBuilder result = new StringBuilder(); boolean cap = upper;
        for (char c : input.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ') { cap = true; continue; }
            result.append(cap ? Character.toUpperCase(c) : result.isEmpty() ? Character.toLowerCase(c) : c);
            cap = false;
        }
        String name = result.toString();
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) name = "_" + name;
        return name;
    }
    public static String snake(String input) {
        return input.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
    public static String javaTypeFor(String sqlType) {
        String type = sqlType.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "").trim();
        return switch (type) {
            case "bigint", "bigserial", "int8" -> "Long";
            case "int", "integer", "smallint", "tinyint", "serial", "int2", "int4" -> "Integer";
            case "decimal", "numeric", "number", "money" -> "java.math.BigDecimal";
            case "float", "real" -> "Float";
            case "double" -> "Double";
            case "bool", "boolean", "bit" -> "Boolean";
            case "date" -> "java.time.LocalDate";
            case "datetime", "timestamp", "timestamptz" -> "java.time.LocalDateTime";
            case "time" -> "java.time.LocalTime";
            case "blob", "binary", "varbinary", "bytea" -> "byte[]";
            default -> "String";
        };
    }
    public static String quote(String identifier, boolean mysql) {
        String q = mysql ? "`" : "\""; return q + identifier.replace(q, q+q) + q;
    }
    public static String xml(String input) { return input.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
}
