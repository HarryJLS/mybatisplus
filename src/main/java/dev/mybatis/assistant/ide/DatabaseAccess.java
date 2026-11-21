package dev.mybatis.assistant.ide;

import com.intellij.credentialStore.*;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;
import dev.mybatis.assistant.core.JdbcSession;
import java.nio.file.Path;
import java.util.*;

public final class DatabaseAccess {
    private DatabaseAccess() {}
    public static List<DatabaseProvider.Source> sources(Project project){
        List<DatabaseProvider.Source> sources=new ArrayList<>();
        if(!AssistantSettings.get(project).jdbcUrl.isBlank())sources.add(new DatabaseProvider.Source("manual","手动 JDBC 配置","JDBC",false));
        for(DatabaseProvider provider:DatabaseProvider.EP.getExtensionList())sources.addAll(provider.sources(project));
        return List.copyOf(sources);
    }
    public static JdbcSession open(Project project,String id)throws Exception{
        if(id.equals("manual")){
            var settings=AssistantSettings.get(project);Properties credentials=new Properties();credentials.setProperty("user",settings.jdbcUsername);
            Credentials stored=PasswordSafe.getInstance().get(credentials(project));if(stored!=null && stored.getPasswordAsString()!=null)credentials.setProperty("password",stored.getPasswordAsString());
            List<Path> jars=Arrays.stream(settings.jdbcJars.split("\\R")).filter(s->!s.isBlank()).map(s->Path.of(s.trim())).toList();
            return JdbcSession.open(jars,settings.jdbcDriver,settings.jdbcUrl,credentials);
        }
        for(DatabaseProvider provider:DatabaseProvider.EP.getExtensionList())if(provider.sources(project).stream().anyMatch(s->s.id().equals(id)))return provider.open(project,id);
        throw new IllegalArgumentException("数据源不存在或已移除");
    }
    public static String sourceId(Project project,String requested){
        List<DatabaseProvider.Source> matches=sources(project).stream().filter(s->s.id().equals(requested) || s.name().equals(requested)).toList();
        if(matches.size()!=1)throw new IllegalArgumentException(matches.isEmpty()?"需要指定可用 dataSource id":"数据源名称有歧义，请使用 id");return matches.getFirst().id();
    }
    public static CredentialAttributes credentials(Project project){return new CredentialAttributes("MyBatisAssistant/JDBC/"+Objects.requireNonNullElse(project.getBasePath(),project.getLocationHash()));}
    public static void savePassword(Project project,char[] password){PasswordSafe.getInstance().set(credentials(project),new Credentials(AssistantSettings.get(project).jdbcUsername,password));Arrays.fill(password,'\0');}
    public static String error(Exception e){
        if(e instanceof java.sql.SQLException sql)return "数据库操作失败（SQLState="+sql.getSQLState()+"，code="+sql.getErrorCode()+"）";
        if(e instanceof IllegalArgumentException)return e.getMessage();return "连接或驱动加载失败："+e.getClass().getSimpleName();
    }
}
