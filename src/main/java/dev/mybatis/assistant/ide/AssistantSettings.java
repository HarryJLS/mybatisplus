package dev.mybatis.assistant.ide;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@State(name="MyBatisAssistant",storages=@Storage("mybatis-assistant.xml"))
public final class AssistantSettings implements PersistentStateComponent<AssistantSettings.Options>,com.intellij.openapi.util.ModificationTracker {
    public static final class Options {
        public boolean completion=true;
        public boolean inspections=true;
        public boolean gutter=true;
        public boolean useActualParamName=true;
        public boolean warnDollar=true;
        public String basePackage="com.example";
        public String namespaceBindings="";
        public String typeAliasPackages="";
        public String typeAliasBindings="";
        public boolean ognlChecks=true;
        public boolean columnChecks=true;
        public boolean consoleSql=false;
        public String jdbcUrl="";
        public String jdbcDriver="";
        public String jdbcJars="";
        public String jdbcUsername="";
        public volatile boolean mcpEnabled=false;
        public volatile boolean mcpDatabaseRead=false;
        public volatile boolean mcpDmlRollback=false;
        public boolean springIntegration=true;
    }
    private Options options=new Options();
    private long modificationCount;
    private String parsedBindings;
    private java.util.Map<String,String> bindings=java.util.Map.of();
    public java.util.Map<String,String> namespaceBindings(){
        String text=java.util.Objects.requireNonNullElse(options.namespaceBindings,"");
        if(!text.equals(parsedBindings)){
            java.util.Map<String,String> result=new java.util.LinkedHashMap<>();
            for(String line:text.split("\\R")){
                line=line.trim();if(line.isEmpty() || line.startsWith("#"))continue;int split=line.indexOf('=');
                if(split>0 && split<line.length()-1)result.put(line.substring(0,split).trim(),line.substring(split+1).trim());
            }
            bindings=java.util.Collections.unmodifiableMap(result);parsedBindings=text;
        }
        return bindings;
    }
    public static Options get(Project p){return p.getService(AssistantSettings.class).getState();}
    @Override public @NotNull Options getState(){return options;}
    @Override public void loadState(@NotNull Options state){options=state;modificationCount++;}
    public void changed(){modificationCount++;}
    @Override public long getModificationCount(){return modificationCount;}
}
