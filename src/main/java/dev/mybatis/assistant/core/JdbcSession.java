package dev.mybatis.assistant.core;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit connection lifetime, with user/IDE-provided drivers. Nothing is auto-downloaded. */
public final class JdbcSession implements AutoCloseable {
    private final DriverLoader loader;
    private final Connection connection;
    private JdbcSession(DriverLoader loader,Connection connection){this.loader=loader;this.connection=connection;}
    public Connection connection(){return connection;}
    public static JdbcSession open(List<Path> jars,String driverClass,String url,Properties credentials)throws Exception{
        if(jars.isEmpty() || jars.size()>64 || !url.startsWith("jdbc:"))throw new IllegalArgumentException("请配置 JDBC URL、驱动类及最多 64 个本地驱动文件");
        List<URL> urls=new ArrayList<>();
        for(Path jar:jars){if(!Files.isRegularFile(jar))throw new IllegalArgumentException("驱动文件不存在");urls.add(jar.toUri().toURL());}
        DriverLoader loader=new DriverLoader(urls.toArray(URL[]::new));
        try{
            Driver driver=(Driver)Class.forName(driverClass,true,loader).getDeclaredConstructor().newInstance();
            Connection connection=driver.connect(url,credentials);if(connection==null)throw new SQLException("驱动不支持该 JDBC URL");
            return new JdbcSession(loader,connection);
        }catch(Exception|LinkageError e){try{loader.cleanup();}catch(Exception close){e.addSuppressed(close);}finally{loader.close();}throw e;}
    }
    @Override public void close()throws Exception{try{connection.close();}finally{try{loader.cleanup();}finally{loader.close();}}}
    private static final class DriverLoader extends URLClassLoader {
        DriverLoader(URL[] urls){super(urls,JdbcSession.class.getClassLoader());}
        void cleanup()throws Exception{
            String name="dev.mybatis.assistant.core.JdbcDriverCleanup";Class<?> helper=findLoadedClass(name);
            if(helper==null)try(InputStream stream=JdbcSession.class.getResourceAsStream("/"+name.replace('.','/')+".class")){
                if(stream==null)throw new IOException("JDBC 清理资源缺失");byte[] bytes=stream.readAllBytes();helper=defineClass(name,bytes,0,bytes.length);
            }
            helper.getMethod("unregister",ClassLoader.class).invoke(null,this);
        }
    }
}
