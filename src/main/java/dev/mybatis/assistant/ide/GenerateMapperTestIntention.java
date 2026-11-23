package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.*;
import com.intellij.ui.components.JBTextArea;
import dev.mybatis.assistant.core.*;
import java.nio.charset.StandardCharsets;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

public final class GenerateMapperTestIntention extends PsiElementBaseIntentionAction {
    @Override public @NotNull String getText(){return "测试 Mapper SQL / 生成 testcase…";}
    @Override public @NotNull String getFamilyName(){return "MyBatis Assistant";}
    @Override public boolean startInWriteAction(){return false;}
    @Override public boolean isAvailable(@NotNull Project project,Editor editor,@NotNull PsiElement element){PsiMethod method=MapperLanguageSupport.findMethod(element);return !DumbService.isDumb(project) && method!=null && (MapperModel.mapper(method.getContainingClass()) || MapperModel.annotated(method));}
    @Override public void invoke(@NotNull Project project,Editor editor,@NotNull PsiElement element){PsiMethod method=MapperLanguageSupport.findMethod(element);if(method!=null)open(method);}
    public static void open(PsiMethod method){if(method.isValid() && !method.getProject().isDisposed() && !DumbService.isDumb(method.getProject()))new TestDialog(method).show();}
    private record Prepared(MapperSnapshot.Input input,String namespace,String method,String kind,List<String> resources,Map<String,String> aliases,String moduleRoot,boolean junit4) {}
    private static Prepared prepare(PsiMethod method,String json){
        var input=MapperSnapshot.forMethod(method,json);var statements=MapperModel.statements(method);
        if(statements.size()!=1 || MapperModel.isLegacy(MapperModel.root(statements.getFirst())))throw new IllegalArgumentException("testcase 生成需要唯一的 MyBatis XML 语句；常量注解请使用现有动态 SQL 预览");
        XmlTag statement=statements.getFirst();Set<PsiFile> files=new LinkedHashSet<>();collect(MapperModel.root(statement),files,new HashSet<>(),0,new int[]{0});
        List<String> resources=new ArrayList<>();Map<String,String> aliases=new TreeMap<>();
        for(PsiFile file:files){resources.add(resource(file));TypeAliases.names(file).forEach((alias,type)->{if(!type.startsWith("java.") && !type.endsWith("[]") && type.contains("."))aliases.put(alias,type);});}
        var module=ModuleUtilCore.findModuleForPsiElement(method);VirtualFile[] roots=module==null?new VirtualFile[0]:ModuleRootManager.getInstance(module).getContentRoots();String base=roots.length>0?roots[0].getPath():method.getProject().getBasePath();if(base==null)throw new IllegalArgumentException("无法定位测试模块目录");
        boolean junit4=JavaPsiFacade.getInstance(method.getProject()).findClass("org.junit.jupiter.api.Test",GlobalSearchScope.allScope(method.getProject()))==null && JavaPsiFacade.getInstance(method.getProject()).findClass("org.junit.Test",GlobalSearchScope.allScope(method.getProject()))!=null;
        return new Prepared(input,MapperModel.root(statement).getAttributeValue("namespace"),statement.getAttributeValue("id"),statement.getName(),List.copyOf(resources),Map.copyOf(aliases),base,junit4);
    }
    private static void collect(XmlTag tag,Set<PsiFile> files,Set<XmlTag> seen,int depth,int[] steps){
        ProgressManager.checkCanceled();if(depth>32 || ++steps[0]>10000 || files.size()>64)throw new IllegalArgumentException("测试 Mapper 依赖超过上限");if(!seen.add(tag))return;files.add(tag.getContainingFile());
        for(String name:List.of("refid","resultMap","extends")){String value=tag.getAttributeValue(name);if(value!=null)for(String ref:value.split("[,\\s]+")){if(ref.contains("${"))throw new IllegalArgumentException("动态映射引用需要手工测试配置");XmlTag target=MapperModel.definition(tag,ref,name.equals("refid")?"sql":"resultMap");if(target!=null)collect(MapperModel.root(target),files,seen,depth+1,steps);}}
        for(XmlTag child:tag.getSubTags())collect(child,files,seen,depth+1,steps);
    }
    private static String resource(PsiFile file){
        VirtualFile virtual=file.getVirtualFile();if(virtual==null)throw new IllegalArgumentException("请先保存 Mapper XML");String path=virtual.getPath();
        for(String marker:List.of("/src/main/resources/","/src/test/resources/")){int index=path.lastIndexOf(marker);if(index>=0)return path.substring(index+marker.length());}
        VirtualFile root=ProjectFileIndex.getInstance(file.getProject()).getSourceRootForFile(virtual);String relative=root==null?null:VfsUtilCore.getRelativePath(virtual,root,'/');if(relative==null)throw new IllegalArgumentException("Mapper XML 不在资源源目录中，请先将其目录标记为 Resources Root");return relative;
    }
    private static final class TestDialog extends DialogWrapper {
        final Project project;final SmartPsiElementPointer<PsiMethod> pointer;final JBTextArea json,output=new JBTextArea();final JButton render=new JButton("测试当前参数 SQL"),generate=new JButton("预览 testcase");Prepared prepared;SqlRenderer.Result result;String testedJson;
        TestDialog(PsiMethod method){super(method.getProject(),false);project=method.getProject();pointer=SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);json=new JBTextArea(MapperSamples.json(method));setTitle("Mapper SQL 测试："+method.getName());setOKButtonText("关闭");init();generate.setEnabled(false);output.setEditable(false);render.addActionListener(e->render());generate.addActionListener(e->generate());}
        @Override protected JComponent createCenterPanel(){
            JPanel panel=new JPanel(new BorderLayout(6,6));JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));controls.add(render);controls.add(generate);panel.add(controls,BorderLayout.NORTH);
            for(JBTextArea area:List.of(json,output))area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(json),new JScrollPane(output));split.setResizeWeight(.45);split.setPreferredSize(new Dimension(800,530));panel.add(split);panel.add(new JLabel("编辑 JSON 后测试 SQL；生成 JUnit SQL/参数断言，以及显式开启的数据库回滚测试。"),BorderLayout.SOUTH);return panel;
        }
        void render(){
            String parameters=json.getText();render.setEnabled(false);generate.setEnabled(false);
            new Task.Backgroundable(project,"测试 Mapper SQL",true){Prepared input;SqlRenderer.Result rendered;String error;
                @Override public void run(@NotNull ProgressIndicator indicator){try{input=ReadAction.compute(()->{PsiMethod method=pointer.getElement();if(method==null)throw new IllegalArgumentException("Mapper 方法已删除");return prepare(method,parameters);});rendered=input.input().render();}catch(ProcessCanceledException e){throw e;}catch(Exception e){error=e.getMessage();}}
                @Override public void onSuccess(){if(project.isDisposed() || !isShowing())return;if(error!=null)output.setText(error);else{prepared=input;result=rendered;testedJson=parameters;output.setText(rendered.display()+"\n\n已测试当前参数分支；未连接数据库。");generate.setEnabled(true);}}
                @Override public void onFinished(){if(!project.isDisposed())render.setEnabled(true);}
            }.queue();
        }
        void generate(){
            if(prepared==null || result==null)return;
            if(!Objects.equals(testedJson,json.getText())){output.setText("参数已变更，请重新测试后生成 testcase。");generate.setEnabled(false);return;}
            try{
                PsiMethod method=pointer.getElement();if(method==null)throw new IllegalArgumentException("Mapper 方法已删除");
                Prepared current=ReadAction.compute(()->prepare(method,testedJson));if(!current.input().equals(prepared.input()))throw new IllegalArgumentException("Mapper SQL 已变更，请重新测试后生成 testcase");
                var files=MapperTestGenerator.generate(prepared.namespace(),prepared.method(),prepared.kind(),prepared.resources(),prepared.aliases(),SqlRenderer.jsonValue(testedJson),result,prepared.junit4());
                DialogWrapper preview=new DialogWrapper(project,false){ {setTitle("testcase 预览：需要 MyBatis 与 "+(prepared.junit4()?"JUnit 4":"JUnit 5"));setOKButtonText("创建测试文件");init();} @Override protected JComponent createCenterPanel(){JTabbedPane tabs=new JTabbedPane();for(var entry:Map.of(files.javaPath(),files.javaSource(),files.configPath(),files.configSource()).entrySet()){JBTextArea area=new JBTextArea(entry.getValue());area.setEditable(false);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));tabs.addTab(entry.getKey(),new JScrollPane(area));}tabs.setPreferredSize(new Dimension(850,560));return tabs;} };
                if(!preview.showAndGet())return;write(project,prepared.moduleRoot(),files);close(OK_EXIT_CODE);
            }catch(IllegalArgumentException e){Messages.showErrorDialog(project,e.getMessage(),"生成 Mapper testcase");}
        }
    }
    static void write(Project project,String base,MapperTestGenerator.Files files){
        VirtualFile root=LocalFileSystem.getInstance().findFileByPath(base);if(root==null)throw new IllegalArgumentException("测试模块目录不存在");
        Map<String,String> sources=new LinkedHashMap<>();sources.put(files.javaPath(),files.javaSource());sources.put(files.configPath(),files.configSource());
        for(String path:sources.keySet())if(root.findFileByRelativePath(path)!=null)throw new IllegalArgumentException("测试文件已存在，不覆盖: "+path);
        List<VirtualFile> created=new ArrayList<>();WriteCommandAction.runWriteCommandAction(project,"生成 Mapper testcase",null,()->{
            try{for(var entry:sources.entrySet()){String path=entry.getKey();VirtualFile dir=VfsUtil.createDirectoryIfMissing(base+"/"+path.substring(0,path.lastIndexOf('/')));String name=path.substring(path.lastIndexOf('/')+1);if(dir==null || dir.findChild(name)!=null)throw new java.io.IOException("目标文件已存在或无法创建");VirtualFile file=dir.createChildData(GenerateMapperTestIntention.class,name);created.add(file);file.setBinaryContent(entry.getValue().getBytes(StandardCharsets.UTF_8));}}
            catch(java.io.IOException e){for(VirtualFile file:created)try{file.delete(GenerateMapperTestIntention.class);}catch(java.io.IOException ignored){}throw new IllegalArgumentException(e.getMessage(),e);}
        });if(!created.isEmpty())FileEditorManager.getInstance(project).openFile(created.getFirst(),true);
    }
}
