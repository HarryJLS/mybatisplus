package dev.mybatis.assistant.core;

import java.util.*;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;

public final class CodeGenerator {
    private CodeGenerator() {}
    public static Map<String,String> generate(Schema.Table table, String pkg, boolean plus, boolean mysql) {
        if (!SourceVersion.isName(pkg)) throw new IllegalArgumentException("包名无效");
        String cls=table.className(), model=pkg+".model."+cls, mapper=pkg+".mapper."+cls+"Mapper";
        Set<String> names=new HashSet<>();
        for (var c:table.columns()) if (!names.add(c.property())) throw new IllegalArgumentException("字段转换为 Java 名称后冲突: "+c.property());
        StringBuilder entity=new StringBuilder("package "+pkg+".model;\n\n");
        if (plus) entity.append("import com.baomidou.mybatisplus.annotation.*;\n\n@TableName(\"").append(javaString(table.name())).append("\")\n");
        entity.append("public class ").append(cls).append(" {\n");
        for (var c:table.columns()) {
            if (plus) {
                if (c.primaryKey()) entity.append("    @TableId(value = \"").append(javaString(c.name())).append("\", type = IdType.").append(c.autoIncrement()?"AUTO":"INPUT").append(")\n");
                else entity.append("    @TableField(\"").append(javaString(c.name())).append("\")\n");
            }
            entity.append("    private ").append(c.javaType()).append(' ').append(c.property()).append(";\n");
        }
        for (var c:table.columns()) {
            String p=c.property(), cap=Character.toUpperCase(p.charAt(0))+p.substring(1);
            entity.append("\n    public ").append(c.javaType()).append(" get").append(cap).append("() { return ").append(p).append("; }\n")
                    .append("    public void set").append(cap).append('(').append(c.javaType()).append(' ').append(p).append(") { this.").append(p).append(" = ").append(p).append("; }\n");
        }
        entity.append("}\n");
        var keys=table.columns().stream().filter(Schema.Column::primaryKey).toList();
        if (plus && keys.size()>1) throw new IllegalArgumentException("MyBatis-Plus BaseMapper 不支持复合主键，请使用 MyBatis 模式");
        StringBuilder api=new StringBuilder("package "+pkg+".mapper;\n\nimport "+model+";\nimport org.apache.ibatis.annotations.Mapper;\nimport org.apache.ibatis.annotations.Param;\nimport java.util.List;\n");
        if (plus) api.append("import com.baomidou.mybatisplus.core.mapper.BaseMapper;\n");
        api.append("\n@Mapper\npublic interface ").append(cls).append("Mapper").append(plus?" extends BaseMapper<"+cls+">":"").append(" {\n");
        if (!plus) {
            api.append("    int insert(").append(cls).append(" entity);\n    List<").append(cls).append("> selectAll();\n");
            if (!keys.isEmpty()) {
                String params=keys.stream().map(c->"@Param(\""+c.property()+"\") "+c.javaType()+" "+c.property()).collect(Collectors.joining(", "));
                api.append("    ").append(cls).append(" selectById(").append(params).append(");\n")
                        .append("    int deleteById(").append(params).append(");\n");
                if (table.columns().stream().anyMatch(c->!c.primaryKey())) api.append("    int updateById(").append(cls).append(" entity);\n");
            }
        }
        api.append("}\n");
        String tableName=Schema.xml(Schema.quote(table.name(),mysql));
        String cols=table.columns().stream().map(c->Schema.xml(Schema.quote(c.name(),mysql))).collect(Collectors.joining(", "));
        StringBuilder xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n<mapper namespace=\""+mapper+"\">\n  <resultMap id=\"BaseResultMap\" type=\""+model+"\">\n");
        for(var c:table.columns()) xml.append("    <").append(c.primaryKey()?"id":"result").append(" column=\"").append(Schema.xml(c.name())).append("\" property=\"").append(c.property()).append("\"/>\n");
        xml.append("  </resultMap>\n  <sql id=\"BaseColumnList\">").append(cols).append("</sql>\n");
        if (!plus) {
            var insert=table.columns().stream().filter(c->!c.autoIncrement()).toList();
            if (insert.isEmpty()) throw new IllegalArgumentException("表只有自增字段，需手动指定数据库默认值 INSERT 语法");
            var generated=keys.stream().filter(Schema.Column::autoIncrement).toList();
            xml.append("  <insert id=\"insert\"");
            if(generated.size()==1) xml.append(" useGeneratedKeys=\"true\" keyProperty=\"").append(generated.getFirst().property()).append("\" keyColumn=\"").append(Schema.xml(generated.getFirst().name())).append("\"");
            xml.append(">\n    INSERT INTO ").append(tableName).append(" (")
                    .append(insert.stream().map(c->Schema.xml(Schema.quote(c.name(),mysql))).collect(Collectors.joining(", ")))
                    .append(")\n    VALUES (").append(insert.stream().map(c->"#{"+c.property()+"}").collect(Collectors.joining(", "))).append(")\n  </insert>\n")
                    .append("  <select id=\"selectAll\" resultMap=\"BaseResultMap\">\n    SELECT <include refid=\"BaseColumnList\"/> FROM ").append(tableName).append("\n  </select>\n");
            if(!keys.isEmpty()) {
                String where=keys.stream().map(c->Schema.xml(Schema.quote(c.name(),mysql))+" = #{"+c.property()+"}").collect(Collectors.joining(" AND "));
                xml.append("  <select id=\"selectById\" resultMap=\"BaseResultMap\">\n    SELECT <include refid=\"BaseColumnList\"/> FROM ").append(tableName).append(" WHERE ").append(where).append("\n  </select>\n")
                        .append("  <delete id=\"deleteById\">DELETE FROM ").append(tableName).append(" WHERE ").append(where).append("</delete>\n");
                var updates=table.columns().stream().filter(c->!c.primaryKey()).toList();
                if(!updates.isEmpty()) xml.append("  <update id=\"updateById\">\n    UPDATE ").append(tableName).append(" SET ")
                        .append(updates.stream().map(c->Schema.xml(Schema.quote(c.name(),mysql))+" = #{"+c.property()+"}").collect(Collectors.joining(", ")))
                        .append(" WHERE ").append(where).append("\n  </update>\n");
            }
        }
        xml.append("</mapper>\n");
        Map<String,String> files=new LinkedHashMap<>(); String path=pkg.replace('.','/');
        files.put("src/main/java/"+path+"/model/"+cls+".java",entity.toString());
        files.put("src/main/java/"+path+"/mapper/"+cls+"Mapper.java",api.toString());
        files.put("src/main/resources/mapper/"+cls+"Mapper.xml",xml.toString());
        return files;
    }
    private static String javaString(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r"); }
}
