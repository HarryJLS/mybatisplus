package dev.mybatis.assistant.ide;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import dev.mybatis.assistant.core.JdbcSession;
import java.util.*;

public interface DatabaseProvider {
    ExtensionPointName<DatabaseProvider> EP=ExtensionPointName.create("dev.mybatis.assistant.databaseProvider");
    record Source(String id,String name,String kind,boolean readOnly) { @Override public String toString(){return name+" ("+kind+")";} }
    List<Source> sources(Project project);
    JdbcSession open(Project project,String id)throws Exception;
}
