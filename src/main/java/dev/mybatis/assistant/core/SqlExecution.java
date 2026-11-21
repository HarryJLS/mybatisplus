package dev.mybatis.assistant.core;

import java.io.*;
import java.sql.*;
import java.util.*;

/** One parameterized statement and one caller-owned connection. No timers, pools or workers. */
public final class SqlExecution {
    private SqlExecution() {}
    public enum Mode { READ_ONLY, ROLLBACK, COMMIT }
    public record Options(Mode mode,int maxRows,int timeoutSeconds) {
        public Options {
            Objects.requireNonNull(mode);
            if(maxRows<1 || maxRows>1000 || timeoutSeconds<1 || timeoutSeconds>60)throw new IllegalArgumentException("行数范围为 1–1000，超时范围为 1–60 秒");
        }
    }
    public record Result(List<String> columns,List<List<Object>> rows,int updateCount,boolean truncated,Mode mode,long elapsedMillis) {}
    public static Result execute(Connection connection,String sql,List<?> parameters,Options options)throws SQLException,IOException {
        return execute(connection,sql,parameters,options,()->Thread.currentThread().isInterrupted());
    }
    public static Result execute(Connection connection,String sql,List<?> parameters,Options options,java.util.function.BooleanSupplier canceled)throws SQLException,IOException {
        String statement=SqlExecutionPolicy.validate(sql,options.mode());
        if(parameters.size()>10000)throw new IllegalArgumentException("参数数量超过 10000");
        for(Object value:parameters){
            if(value instanceof java.math.BigDecimal number && (number.precision()>1000 || Math.abs((long)number.scale())>1000))throw new IllegalArgumentException("执行数值精度或 scale 超过 1000");
            if(value instanceof Map<?,?> || value instanceof Collection<?> || value!=null && value.getClass().isArray())throw new IllegalArgumentException("执行参数必须是标量；集合应由 foreach 展开");
        }
        if(!connection.getMetaData().supportsTransactions())throw new SQLException("数据库不支持事务，不能执行此入口");
        long start=System.nanoTime();boolean oldAuto=connection.getAutoCommit(),oldRead=connection.isReadOnly();
        if(!oldAuto)throw new SQLException("执行入口需要独立的自动提交连接，不能接管已有事务");
        if(oldRead && options.mode()!=Mode.READ_ONLY)throw new SQLException("数据源为只读");
        boolean completed=false;
        connection.setAutoCommit(false);
        try {
            if(options.mode()==Mode.READ_ONLY)connection.setReadOnly(true);
            try(PreparedStatement prepared=connection.prepareStatement(statement)){
                prepared.setQueryTimeout(options.timeoutSeconds());prepared.setMaxRows(options.maxRows()+1);
                for(int i=0;i<parameters.size();i++){
                    Object value=parameters.get(i);
                    prepared.setObject(i+1,value);
                }
                boolean query=prepared.execute();List<String> columns=new ArrayList<>();List<List<Object>> rows=new ArrayList<>();boolean truncated=false;int size=0;
                if(query)try(ResultSet rs=prepared.getResultSet()){
                    ResultSetMetaData meta=rs.getMetaData();int count=meta.getColumnCount();
                    if(count>256)throw new IllegalArgumentException("结果列超过 256");
                    for(int i=1;i<=count;i++)columns.add(meta.getColumnLabel(i));
                    while(rs.next()){
                        if(canceled.getAsBoolean())throw new SQLException("任务已取消");
                        if(rows.size()>=options.maxRows()){truncated=true;break;}
                        List<Object> row=new ArrayList<>();
                        for(int i=1;i<=count;i++){
                            Cell cell=readCell(rs,meta.getColumnType(i),i);row.add(cell.value());truncated|=cell.truncated();size+=String.valueOf(cell.value()).length();
                        }
                        if(size>262144){truncated=true;break;}rows.add(Collections.unmodifiableList(row));
                    }
                }
                int updated=query?-1:prepared.getUpdateCount();
                if(canceled.getAsBoolean())throw new SQLException("任务已取消");
                if(options.mode()==Mode.COMMIT)connection.commit();else connection.rollback();completed=true;
                return new Result(List.copyOf(columns),List.copyOf(rows),updated,truncated,options.mode(),(System.nanoTime()-start)/1_000_000);
            }
        }finally{
            if(!completed)try{connection.rollback();}catch(SQLException failure){try{connection.close();}catch(SQLException close){failure.addSuppressed(close);}throw failure;}
            if(!connection.isClosed()){try{connection.setReadOnly(oldRead);}finally{connection.setAutoCommit(oldAuto);}}
        }
    }
    private record Cell(Object value,boolean truncated) {}
    private static Cell readCell(ResultSet rs,int type,int column)throws SQLException,IOException {
        if(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(type)){try(InputStream stream=rs.getBinaryStream(column)){return new Cell(stream==null?null:"[二进制内容未读取]",stream!=null);}}
        if(Set.of(Types.ARRAY,Types.STRUCT,Types.JAVA_OBJECT,Types.REF,Types.OTHER,Types.SQLXML).contains(type))return new Cell("[复杂 JDBC 类型未读取]",true);
        if(Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(type)){
            try(Reader reader=rs.getCharacterStream(column)){
                if(reader==null)return new Cell(null,false);char[] chars=new char[4097];int count=0,n;
                while(count<chars.length && (n=reader.read(chars,count,chars.length-count))>0)count+=n;
                return new Cell(new String(chars,0,Math.min(count,4096)),count>4096);
            }
        }
        Object value=rs.getObject(column);
        if(value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor)value=value.toString();
        return new Cell(value,false);
    }
}
