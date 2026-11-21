package dev.mybatis.assistant.database;

import dev.mybatis.assistant.core.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Real H2 JDBC tests in an isolated in-memory database, never a user's configured connection. */
public final class JdbcExecutionTests {
    private static int count;
    private static void equal(Object a,Object b){count++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    private static int rows(Connection c)throws Exception{try(ResultSet r=c.createStatement().executeQuery("SELECT COUNT(*) FROM users")){r.next();return r.getInt(1);}}
    @FunctionalInterface interface Work{void run()throws Exception;}
    private static void fails(Work work)throws Exception{count++;try{work.run();}catch(Exception expected){return;}throw new AssertionError("failure expected");}
    public static void main(String[] args)throws Exception{
        Path jar=Path.of(System.getProperty("mybatis.test.h2"));Properties properties=new Properties();properties.setProperty("user","sa");
        Connection kept;
        try(JdbcSession session=JdbcSession.open(List.of(jar),"org.h2.Driver","jdbc:h2:mem:assistant_test",properties)){
            Connection c=session.connection();kept=c;
            try(Statement setup=c.createStatement()){
                setup.execute("CREATE TABLE users(id INT PRIMARY KEY,name VARCHAR(10000),note VARCHAR(20))");setup.execute("INSERT INTO users VALUES(1,'Alice',null)");
            }
            SqlExecution.Options read=new SqlExecution.Options(SqlExecution.Mode.READ_ONLY,100,10),rollback=new SqlExecution.Options(SqlExecution.Mode.ROLLBACK,100,10),commit=new SqlExecution.Options(SqlExecution.Mode.COMMIT,100,10);
            var result=SqlExecution.execute(c,"SELECT id,name,note FROM users WHERE id=?",List.of(1),read);
            equal(result.rows().size(),1);equal(result.rows().getFirst(),Arrays.asList(1,"Alice",null));equal(result.mode(),SqlExecution.Mode.READ_ONLY);equal(c.getAutoCommit(),true);
            try{SqlExecution.execute(c,"SELECT ?",List.of(new java.math.BigDecimal("1e1000000")),read);throw new AssertionError("oversized scale accepted");}catch(IllegalArgumentException expected){count++;}equal(rows(c),1);
            equal(SqlExecution.execute(c,"INSERT INTO users(id,name) VALUES(?,?)",List.of(2,"Bob"),rollback).updateCount(),1);equal(rows(c),1);
            SqlExecution.execute(c,"INSERT INTO users(id,name) VALUES(?,?)",List.of(2,"Bob"),commit);equal(rows(c),2);
            SqlExecution.execute(c,"UPDATE users SET name=? WHERE id=?",List.of("Changed",1),rollback);equal(SqlExecution.execute(c,"SELECT name FROM users WHERE id=?",List.of(1),read).rows().getFirst().getFirst(),"Alice");
            SqlExecution.execute(c,"DELETE FROM users WHERE id=?",List.of(2),rollback);equal(rows(c),2);
            fails(()->SqlExecution.execute(c,"UPDATE users SET name=?",List.of("No"),read));equal(rows(c),2);
            fails(()->SqlExecution.execute(c,"INSERT INTO users(id,name) VALUES(?,?)",List.of(1,"duplicate"),commit));equal(rows(c),2);equal(c.getAutoCommit(),true);
            fails(()->SqlExecution.execute(c,"INSERT INTO users(id,name) VALUES(?,?)",List.of(3,"cancel"),commit,()->true));equal(rows(c),2);
            var limited=SqlExecution.execute(c,"SELECT id FROM users ORDER BY id",List.of(),new SqlExecution.Options(SqlExecution.Mode.READ_ONLY,1,10));equal(limited.rows().size(),1);equal(limited.truncated(),true);
            SqlExecution.execute(c,"UPDATE users SET name=? WHERE id=?",List.of("x".repeat(5000),1),commit);var longValue=SqlExecution.execute(c,"SELECT name FROM users WHERE id=1",List.of(),read);equal(((String)longValue.rows().getFirst().getFirst()).length(),4096);equal(longValue.truncated(),true);
            var columns=JdbcMetadata.columns(c,"PUBLIC","users");equal(columns.size(),3);equal(columns.getFirst().primaryKey(),true);equal(columns.getFirst().nullable(),false);
            equal(JdbcMetadata.tables(c,"PUBLIC","users",10).getFirst().name(),"USERS");
            c.createStatement().execute("CREATE SCHEMA other");c.createStatement().execute("CREATE TABLE other.users(id INT)");fails(()->JdbcMetadata.columns(c,null,"users"));equal(JdbcMetadata.columns(c,"OTHER","USERS").size(),1);
            c.setAutoCommit(false);fails(()->SqlExecution.execute(c,"SELECT 1",List.of(),read));c.rollback();c.setAutoCommit(true);
        }
        equal(kept.isClosed(),true);
        System.out.println("PASS: "+count+" real H2 JDBC/transaction assertions");
    }
}
