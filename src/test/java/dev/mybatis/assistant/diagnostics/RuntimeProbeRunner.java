package dev.mybatis.assistant.diagnostics;

import com.google.gson.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import dev.mybatis.assistant.core.SqlRenderer;
import dev.mybatis.assistant.ide.*;
import java.lang.management.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** One-shot backend diagnostics only: no UI actions or project file mutations. */
public final class RuntimeProbeRunner {
    public static String run(Object candidate,String configuration)throws Exception {
        Project project=(Project)candidate;ProgressIndicator indicator=new EmptyProgressIndicator();
        JsonObject report=new JsonObject();int cacheBefore,cacheAfter;
                    JsonObject config=JsonParser.parseString(Files.readString(Path.of(configuration))).getAsJsonObject();
                    if(!Objects.equals(project.getBasePath(),config.get("project").getAsString()))throw new IllegalArgumentException("Probe belongs to a different project");
                    report.addProperty("ideaBuild",ApplicationInfo.getInstance().getBuild().asString());
                    report.addProperty("pid",ProcessHandle.current().pid());report.addProperty("startedAt",java.time.Instant.now().toString());
                    if(config.has("nativeOnly") && config.get("nativeOnly").getAsBoolean()){
                        report.addProperty("nativeLoadedBefore",dev.mybatis.assistant.core.NativeBridge.isLoaded());
                        String sql=dev.mybatis.assistant.core.NativeBridge.logsToSql("Preparing: select ?\nParameters: 1(Integer)");
                        var table=dev.mybatis.assistant.core.NativeBridge.parseDdl("CREATE TABLE probe (id bigint primary key, name varchar(20))");
                        if(!sql.equals("select 1;") || table.columns().size()!=2)throw new IllegalStateException("Native smoke output mismatch");
                        report.addProperty("nativeLoadedAfter",dev.mybatis.assistant.core.NativeBridge.isLoaded());report.addProperty("nativeSmokePassed",true);
                        Files.writeString(Path.of(config.get("output").getAsString()),report.toString());return report.toString();
                    }
                    report.addProperty("heapBefore",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    List<Long> modelTimes=new ArrayList<>(),renderTimes=new ArrayList<>();JsonArray problems=new JsonArray();
                    int files=0,interfaces=0,methods=0,models=0,rendered=0;
                    indicator.setText("Resolving real project Mapper/XML and parameter models");
                    for(JsonElement entry:config.getAsJsonArray("files")){
                        indicator.checkCanceled();String relative=entry.getAsString();
                        VirtualFile virtual=LocalFileSystem.getInstance().findFileByPath(project.getBasePath()+"/"+relative);if(virtual==null)continue;
                        PsiFile file=ReadAction.compute(()->PsiManager.getInstance(project).findFile(virtual));if(!(file instanceof XmlFile))continue;files++;
                        long start=System.nanoTime();int[] counts=ReadAction.compute(()->{
                            XmlTag root=MapperModel.root(file);if(root==null)return new int[3];
                            int resolved=MapperModel.mapperClass(root)==null?0:1,found=0,built=0;
                            for(XmlTag statement:root.getSubTags())if(MapperModel.isStatement(statement)){
                                if(MapperModel.method(statement)!=null)found++;
                                ParameterModel.of(null,statement).symbols();built++;
                                if(built>=20)break;
                            }return new int[]{resolved,found,built};
                        });modelTimes.add(System.nanoTime()-start);interfaces+=counts[0];methods+=counts[1];models+=counts[2];
                        String id=ReadAction.compute(()->{
                            XmlTag root=MapperModel.root(file);if(root==null)return null;
                            for(XmlTag tag:root.getSubTags())if(tag.getName().equals("select"))return tag.getAttributeValue("id");return null;
                        });
                        if(id!=null){
                            try{start=System.nanoTime();MapperSnapshot.of(file,id,config.get("parameters").toString()).render();renderTimes.add(System.nanoTime()-start);rendered++;}
                            catch(ProcessCanceledException canceled){throw canceled;}
                            catch(Exception failure){JsonObject problem=new JsonObject();problem.addProperty("file",relative);problem.addProperty("statement",id);problem.addProperty("error",failure.getClass().getSimpleName()+": "+failure.getMessage());problems.add(problem);}
                        }
                    }
                    report.addProperty("xmlFiles",files);report.addProperty("interfacesResolved",interfaces);report.addProperty("statementMethodsResolved",methods);report.addProperty("parameterModels",models);report.addProperty("realPreviewsSucceeded",rendered);report.add("realPreviewErrors",problems);
                    report.add("modelTiming",timing(modelTimes));report.add("realPreviewTiming",timing(renderTimes));
                    cacheBefore=cacheSize();int count=config.get("uniqueRenders").getAsInt();long peak=0,started=System.nanoTime();
                    report.addProperty("staticCacheBefore",cacheBefore);indicator.setText("Rendering unique safe expressions without static cache accumulation");
                    for(int i=0;i<count;i++){
                        indicator.checkCanceled();
                        String xml="<mapper namespace='probe.Mapper'><select id='find'><bind name='n' value='id + "+i+"'/><if test='id != null and "+i+" == "+i+"'>SELECT ${n},#{id}<foreach collection='ids' item='v' separator=','>#{v}</foreach></if></select></mapper>";
                        SqlRenderer.render(xml,"find","{\"id\":1,\"ids\":[1,2,3]}",List.of(),indicator::checkCanceled);
                        if(i%100==0){peak=Math.max(peak,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());indicator.setFraction((double)i/count);}
                    }
                    cacheAfter=cacheSize();report.addProperty("uniqueRenders",count);report.addProperty("staticCacheAfter",cacheAfter);report.addProperty("syntheticPreviewTotalMs",(System.nanoTime()-started)/1e6);report.addProperty("heapPeakSampled",peak);report.addProperty("heapAfter",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                    report.addProperty("finishedAt",java.time.Instant.now().toString());
                    report.addProperty("cacheRegressionPassed",cacheAfter==cacheBefore);
                    Files.writeString(Path.of(config.get("output").getAsString()),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        return report.toString();
    }
    private static int cacheSize()throws Exception{
        Class<?> type=Class.forName("org.apache.ibatis.scripting.xmltags.OgnlCache",true,SqlRenderer.class.getClassLoader());Field field=type.getDeclaredField("expressionCache");field.setAccessible(true);return ((Map<?,?>)field.get(null)).size();
    }
    private static JsonObject timing(List<Long> values){
        JsonObject result=new JsonObject();result.addProperty("samples",values.size());if(values.isEmpty())return result;
        values.sort(Long::compare);result.addProperty("medianMs",values.get(values.size()/2)/1e6);result.addProperty("p95Ms",values.get(Math.min(values.size()-1,(int)Math.ceil(values.size()*0.95)-1))/1e6);result.addProperty("maxMs",values.getLast()/1e6);return result;
    }
}
