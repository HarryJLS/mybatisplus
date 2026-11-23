package dev.mybatis.assistant.core;

import java.util.*;

/** Reads SELECT output names, not database semantics. Ambiguous projections stay incomplete. */
public final class SqlProjection {
    private SqlProjection() {}
    public record Result(List<String> columns, boolean complete, String reason) {}
    private record Token(String text, boolean identifier,boolean quoted) {
        Token(String text,boolean identifier){this(text,identifier,false);}
        boolean is(String word) { return text.equalsIgnoreCase(word); }
    }

    public static Result parse(String sql) {
        if (sql.length() > 262144) return new Result(List.of(), false, "SQL 超过 256 KiB");
        List<Token> tokens = tokenize(sql);
        int select = -1, from = -1, depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (depth == 0 && t.is("SELECT") && select < 0) select = i;
            else if (depth == 0 && select >= 0 && (t.is("FROM") || t.is("UNION") || t.is(";"))) { from = i; break; }
            if (t.is("(")) depth++;
            if (t.is(")")) depth--;
            if (depth < 0 || depth > 64) return new Result(List.of(), false, "SQL 括号范围无法解析");
        }
        if (select < 0) return new Result(List.of(), false, "不是可解析的 SELECT");
        if (from < 0) from = tokens.size();
        List<String> names = new ArrayList<>();
        boolean complete = tokens.subList(select+1,from).stream().noneMatch(t->t.is("?dynamic"));
        int begin = select + 1;
        if (begin < from && (tokens.get(begin).is("DISTINCT") || tokens.get(begin).is("ALL"))) begin++;
        depth = 0;
        for (int i = begin, start = begin; i <= from; i++) {
            if (i == from || depth == 0 && tokens.get(i).is(",")) {
                String name = outputName(tokens.subList(start, i));
                if (name == null) complete = false; else names.add(name);
                start = i + 1;
            } else {
                if (tokens.get(i).is("(")) depth++;
                if (tokens.get(i).is(")")) depth--;
            }
        }
        return new Result(List.copyOf(names), complete && !names.isEmpty(), names.isEmpty()?"没有明确的 SELECT 输出列":complete ? "" : "包含 *、动态列或没有别名的表达式");
    }

    private static String outputName(List<Token> ts) {
        if (ts.isEmpty()) return null;
        int n = ts.size();
        Token last = ts.get(n - 1);
        if(last.identifier && !last.quoted && Set.of("END","NULL","TRUE","FALSE").contains(last.text.toUpperCase(Locale.ROOT)))return null;
        if (last.identifier && n >= 3 && ts.get(n - 2).is("AS")) return last.text;
        boolean path = true;
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0 ? !ts.get(i).identifier : !ts.get(i).is(".")) path = false;
        }
        if (path && n % 2 == 1) return last.text;
        if (last.identifier && n > 1) {
            Token before = ts.get(n - 2);
            // Bare aliases follow a complete expression, never an operator or a dotted name.
            if (before.identifier || before.is(")") || before.text.startsWith("'") || before.text.matches("[0-9]+(?:\\.[0-9]+)?") || before.is("?")) return last.text;
        }
        return null;
    }

    private static List<Token> tokenize(String sql) {
        List<Token> ts = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2); i = end < 0 ? sql.length() : end + 2; continue;
            }
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char end = c == '[' ? ']' : c;
                int start = i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < sql.length()) {
                    char ch = sql.charAt(i++);
                    if (ch == end) {
                        if (i < sql.length() && sql.charAt(i) == end) { value.append(end); i++; }
                        else { closed = true; break; }
                    } else if (ch == '\\' && c == '\'' && i < sql.length()) { value.append(sql.charAt(i++)); }
                    else value.append(ch);
                }
                ts.add(new Token(c == '\'' ? sql.substring(start, i) : value.toString(), c != '\'' && closed,c!='\''));
            } else if ((c == '#' || c == '$') && i + 1 < sql.length() && sql.charAt(i + 1) == '{') {
                int end = sql.indexOf('}', i + 2);
                ts.add(new Token(c=='$'?"?dynamic":"?", false)); i = end < 0 ? sql.length() : end + 1;
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < sql.length() && Character.isJavaIdentifierPart(sql.charAt(i))) i++;
                ts.add(new Token(sql.substring(start, i), true));
            } else if(Character.isDigit(c)){
                int start=i++;while(i<sql.length() && (Character.isDigit(sql.charAt(i)) || sql.charAt(i)=='.'))i++;ts.add(new Token(sql.substring(start,i),false));
            } else { ts.add(new Token(String.valueOf(c), false)); i++; }
        }
        return ts;
    }
}
