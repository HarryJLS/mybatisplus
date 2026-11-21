package dev.mybatis.assistant.ide;

import com.intellij.execution.filters.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import dev.mybatis.assistant.core.NativeBridge;
import java.util.*;
import java.util.regex.*;

/** Console-owned, bounded pairing. Native parsing runs only when the user clicks the link. */
public final class SqlConsoleFilterProvider implements ConsoleFilterProvider {
    @Override public Filter[] getDefaultFilters(Project project){return new Filter[]{new SqlFilter(project)};}
    public static final class SqlFilter implements Filter {
        private final Project project;
        private final Map<String,String> pending=new LinkedHashMap<>();
        public SqlFilter(Project project){this.project=project;}
        @Override public synchronized Result applyFilter(String line,int entireLength){
            if(!AssistantSettings.get(project).consoleSql){pending.clear();return null;}
            if(line.length()>16384){pending.clear();return null;}
            int preparing=line.indexOf("Preparing:"),parameters=line.indexOf("Parameters:");
            int marker=preparing>=0?preparing:parameters;if(marker<0)return null;
            String thread=thread(line.substring(0,marker));
            if(preparing>=0){
                if(pending.size()>=64)pending.clear();
                pending.put(thread,pending.containsKey(thread)?"":line);return null;
            }
            String first=pending.remove(thread);if(first==null || first.isEmpty())return null;
            String pair=first+"\n"+line;
            return new Result(entireLength-line.length()+parameters,entireLength-line.length()+line.stripTrailing().length(),p->{
                new Task.Backgroundable(p,"还原控制台 SQL",true){
                    String sql,error;
                    @Override public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator){
                        indicator.checkCanceled();try{sql=NativeBridge.logsToSql(pair);}catch(IllegalArgumentException e){error=e.getMessage();}
                    }
                    @Override public void onSuccess(){if(!p.isDisposed())new TextPreviewDialog(p,error==null?"控制台 SQL（仅显示）":"日志无法还原",error==null?sql:error).show();}
                }.queue();
            });
        }
        private static String thread(String prefix){
            Matcher matcher=Pattern.compile("\\[([^]\\r\\n]+)]").matcher(prefix);String result="";
            while(matcher.find())result=matcher.group(1);return result;
        }
    }
}
