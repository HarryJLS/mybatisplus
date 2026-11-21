package dev.mybatis.assistant.core;

import java.sql.*;
import java.util.*;

public final class JdbcMetadata {
    private JdbcMetadata() {}
    public record Table(String catalog,String schema,String name,String type) {}
    public record Column(String name,int jdbcType,String typeName,int size,Boolean nullable,boolean primaryKey) {}
    public static List<Table> tables(Connection connection,String schema,String search,int limit)throws SQLException{
        if(limit<1 || limit>500)throw new IllegalArgumentException("表列表上限为 500");
        List<Table> tables=new ArrayList<>();String filter=search==null?"":search.toLowerCase(Locale.ROOT);int scanned=0;
        try(ResultSet rs=connection.getMetaData().getTables(connection.getCatalog(),schema,null,new String[]{"TABLE","VIEW"})){
            while(rs.next() && tables.size()<limit){if(++scanned>50000)throw new IllegalArgumentException("元数据扫描超过 50000 张表，请缩小 schema");String name=rs.getString("TABLE_NAME");if((schema==null || schema.equalsIgnoreCase(rs.getString("TABLE_SCHEM"))) && name.toLowerCase(Locale.ROOT).contains(filter))tables.add(new Table(rs.getString("TABLE_CAT"),rs.getString("TABLE_SCHEM"),name,rs.getString("TABLE_TYPE")));}
        }return List.copyOf(tables);
    }
    public static List<Column> columns(Connection connection,String schema,String table)throws SQLException{
        if(table==null || table.isBlank() || table.length()>256)throw new IllegalArgumentException("需要精确表名");
        DatabaseMetaData metadata=connection.getMetaData();String escape=metadata.getSearchStringEscape();
        String pattern=escape(table,escape);
        List<Table> matches=new ArrayList<>();
        lookupTables(connection,metadata,schema,pattern,table,matches);
        if(matches.isEmpty()){
            String alternate=metadata.storesUpperCaseIdentifiers()?table.toUpperCase(Locale.ROOT):metadata.storesLowerCaseIdentifiers()?table.toLowerCase(Locale.ROOT):table;
            if(!alternate.equals(table))lookupTables(connection,metadata,schema,escape(alternate,escape),table,matches);
        }
        if(matches.size()!=1)throw new IllegalArgumentException(matches.isEmpty()?"找不到表":"同名表不唯一，请指定 schema");
        Table target=matches.getFirst();Set<String> keys=new HashSet<>();
        try(ResultSet rs=metadata.getPrimaryKeys(target.catalog(),target.schema(),target.name())){while(rs.next())keys.add(rs.getString("COLUMN_NAME"));}
        List<Column> columns=new ArrayList<>();
        try(ResultSet rs=metadata.getColumns(target.catalog(),target.schema(),escape(target.name(),escape),null)){
            while(rs.next()){if(!target.name().equals(rs.getString("TABLE_NAME")))continue;if(columns.size()>=2000)throw new IllegalArgumentException("字段数量超过 2000");String name=rs.getString("COLUMN_NAME");int nullable=rs.getInt("NULLABLE");columns.add(new Column(name,rs.getInt("DATA_TYPE"),rs.getString("TYPE_NAME"),rs.getInt("COLUMN_SIZE"),nullable==DatabaseMetaData.columnNullableUnknown?null:nullable!=DatabaseMetaData.columnNoNulls,keys.contains(name)));}
        }return List.copyOf(columns);
    }
    private static String escape(String value,String escape){return escape==null || escape.isEmpty()?value:value.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");}
    private static void lookupTables(Connection connection,DatabaseMetaData metadata,String schema,String pattern,String requested,List<Table> matches)throws SQLException{
        try(ResultSet rs=metadata.getTables(connection.getCatalog(),schema,pattern,new String[]{"TABLE","VIEW"})){
            while(rs.next()){String name=rs.getString("TABLE_NAME"),foundSchema=rs.getString("TABLE_SCHEM");if(name.equalsIgnoreCase(requested) && (schema==null || schema.equalsIgnoreCase(foundSchema)))matches.add(new Table(rs.getString("TABLE_CAT"),foundSchema,name,rs.getString("TABLE_TYPE")));if(matches.size()>1)break;}
        }
    }
}
