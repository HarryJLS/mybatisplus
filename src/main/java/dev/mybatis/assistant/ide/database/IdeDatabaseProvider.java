package dev.mybatis.assistant.ide.database;

import com.intellij.database.access.DatabaseCredentials;
import com.intellij.database.dataSource.*;
import com.intellij.database.dataSource.artifacts.DatabaseArtifactContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.util.ui.classpath.SimpleClasspathElement;
import dev.mybatis.assistant.core.JdbcSession;
import dev.mybatis.assistant.ide.DatabaseProvider;
import java.nio.file.*;
import java.util.*;

/** Reads existing IDE config on demand; direct JDBC does not reuse an active console transaction. */
public final class IdeDatabaseProvider implements DatabaseProvider {
    @Override public List<Source> sources(Project project){return LocalDataSourceManager.getInstance(project).getDataSources().stream().map(ds->new Source("ide:"+ds.getUniqueId(),ds.getName(),"IDE Database",ds.isReadOnly())).toList();}
    @Override public JdbcSession open(Project project,String id)throws Exception{
        LocalDataSource source=LocalDataSourceManager.getInstance(project).getDataSources().stream().filter(ds->id.equals("ide:"+ds.getUniqueId())).findFirst().orElseThrow(()->new IllegalArgumentException("IDE 数据源已移除"));
        if(DataSourceSshTunnelConfiguration.hasTunnel(source) || source.getSslCfg()!=null && source.getSslCfg().myEnabled || !Objects.requireNonNullElse(source.getInitScript(),"").isBlank())throw new IllegalArgumentException("直接 JDBC 入口暂不支持 IDE SSH、额外 SSL 配置或初始化脚本；请配置等效的独立 JDBC URL");
        DatabaseDriver driver=source.getDatabaseDriver();if(driver==null)throw new IllegalArgumentException("请先在 IDEA 下载并配置数据库驱动");
        DatabaseArtifactContext context=DatabaseArtifactContext.Companion.getInstance(project,source);List<SimpleClasspathElement> elements=new ArrayList<>(driver.getAdditionalClasspathElements());
        for(var artifact:driver.getArtifacts())elements.addAll(artifact.getElements(context));
        Set<Path> jars=new LinkedHashSet<>();
        for(SimpleClasspathElement element:elements)for(String url:element.getClassesRootUrls()){
            String path=VfsUtilCore.urlToPath(url);if(path.endsWith("!/"))path=path.substring(0,path.length()-2);Path jar=Path.of(path);if(Files.isRegularFile(jar))jars.add(jar);
        }
        Properties credentials=new Properties();credentials.putAll(source.getConnectionProperties());credentials.setProperty("user",Objects.requireNonNullElse(source.getUsername(),""));
        var stored=DatabaseCredentials.getInstance().getCredentials(source);if(stored!=null){if(stored.getUserName()!=null)credentials.setProperty("user",stored.getUserName());if(stored.getPasswordAsString()!=null)credentials.setProperty("password",stored.getPasswordAsString());}
        JdbcSession session=JdbcSession.open(List.copyOf(jars),source.getDriverClass(),source.getUrl(),credentials);
        if(source.isReadOnly())session.connection().setReadOnly(true);
        return session;
    }
}
