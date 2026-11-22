package dev.mybatis.assistant.ide;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import dev.mybatis.assistant.core.SqlRenderer;
import java.nio.file.*;
import java.util.*;

/** Copies only requested PSI text under a read action. JDBC and rendering then run without PSI locks. */
public final class MapperSnapshot {
    private MapperSnapshot() {}
    public record Input(String xml,String statementId,String json,List<String> related) {
        public SqlRenderer.Result render()throws Exception{return SqlRenderer.render(xml,statementId,json,related,com.intellij.openapi.progress.ProgressManager::checkCanceled);}
    }
    public static PsiFile file(Project project,String requested)throws Exception{
        Path path=Path.of(requested);if(!path.isAbsolute())path=Path.of(Objects.requireNonNull(project.getBasePath())).resolve(path);
        Path real=path.toRealPath();boolean allowed=false;
        for(VirtualFile root:ProjectRootManager.getInstance(project).getContentRoots())if(real.startsWith(Path.of(root.getPath()).toRealPath())){allowed=true;break;}
        if(!allowed && project.getBasePath()!=null)allowed=real.startsWith(Path.of(project.getBasePath()).toRealPath());
        if(!allowed || Files.size(real)>4*1024*1024)throw new IllegalArgumentException("路径不属于当前项目或文件超过 4 MiB");
        VirtualFile virtual=LocalFileSystem.getInstance().refreshAndFindFileByPath(real.toString());if(virtual==null)throw new IllegalArgumentException("文件不存在");
        return ReadAction.compute(()->{PsiFile psi=PsiManager.getInstance(project).findFile(virtual);if(psi==null)throw new IllegalArgumentException("文件没有 PSI 模型");return psi;});
    }
    public static Input requested(Project project,String path,String id,String json)throws Exception{
        PsiFile file=file(project,path);return of(file,id,json);
    }
    public static Input of(PsiFile file,String id,String json){return ReadAction.compute(()->capture(file,id,json));}
    public static Input editor(Project project,String json){
        var editor=FileEditorManager.getInstance(project).getSelectedTextEditor();if(editor==null)throw new IllegalArgumentException("请先打开 Mapper XML 或 SQL 注解方法");
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());PsiFile file=PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());if(file==null)throw new IllegalArgumentException("编辑器文件不可用");int offset=editor.getCaretModel().getOffset();
        return ReadAction.compute(()->{
            PsiElement element=file.findElementAt(Math.min(offset,Math.max(0,file.getTextLength()-1)));XmlTag statement=MapperModel.statement(element);
            if(statement!=null)return capture(file,statement.getAttributeValue("id"),json);
            PsiMethod method=MapperLanguageSupport.findMethod(element);if(method==null)throw new IllegalArgumentException("请将光标放在语句或普通注解 Mapper 方法内");return capture(file,method.getName(),json);
        });
    }
    static Input capture(PsiFile file,String id,String json){
        if(DumbService.isDumb(file.getProject()))throw new IllegalArgumentException("IDEA 正在更新索引，请稍后重试");
        XmlTag root=MapperModel.root(file);List<String> related=new ArrayList<>();
        if(root!=null){
            List<XmlTag> matches=MapperModel.metadata(root).tags().getOrDefault(MapperModel.localId(root,id),List.of()).stream().filter(MapperModel::isStatement).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("语句 id 不存在或有多个 databaseId 候选，请明确语句");
            String source=file.getText();new Includes(file,source.length(),related).visit(matches.getFirst(),0);
            return new Input(source,matches.getFirst().getAttributeValue("id"),json,List.copyOf(related));
        }
        List<PsiMethod> methods=new ArrayList<>();if(file instanceof PsiJavaFile java)for(PsiClass cls:java.getClasses())methods.addAll(Arrays.asList(cls.getMethods()));
        for(MapperLanguageSupport support:MapperLanguageSupport.EP.getExtensionList())methods.addAll(support.methods(file));
        List<PsiMethod> matches=methods.stream().filter(m->m.getName().equals(id)).toList();if(matches.size()!=1)throw new IllegalArgumentException("注解方法不存在或重载不唯一");
        return new Input(AnnotationSql.mapperXml(matches.getFirst()),id,json,List.of());
    }
    /** Request-local PSI only. Follow selected statement fragments, including split namespaces. */
    private static final class Includes {
        private final Set<PsiFile> files=new HashSet<>();
        private final Set<XmlTag> visited=new HashSet<>();
        private final Map<String,List<XmlTag>> namespaces=new HashMap<>();
        private final XmlTag scope;
        private final List<String> xml;private long length;private int steps;
        Includes(PsiFile main,long length,List<String> xml){files.add(main);this.scope=MapperModel.root(main);this.length=length;this.xml=xml;}
        void visit(XmlTag tag,int depth){
            ProgressManager.checkCanceled();
            if(depth>64 || ++steps>100000)throw new IllegalArgumentException("include 展开超过上限");
            if(tag.getName().equals("include")){
                String ref=tag.getAttributeValue("refid");if(ref==null || ref.contains("${"))return;
                // MyBatis resolves nested bare refids in the calling mapper's namespace.
                XmlTag context=MapperModel.isLegacy(scope)?tag:scope;
                XmlTag target=MapperModel.definition(context,ref,"sql",namespaces);if(target==null)return;
                PsiFile file=target.getContainingFile();
                if(files.add(file)){
                    if(files.size()>65 || length+file.getTextLength()>8*1024*1024)throw new IllegalArgumentException("关联 Mapper 超过 64 个文件或 8 MiB");
                    String source=file.getText();length+=source.length();xml.add(source);
                }
                // The renderer still detects cycles and accounts for repeated expansion.
                if(visited.add(target))visit(target,depth+1);
                return;
            }
            for(XmlTag child:tag.getSubTags())visit(child,depth+1);
        }
    }
}
