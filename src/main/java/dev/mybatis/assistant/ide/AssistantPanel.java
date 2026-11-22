package dev.mybatis.assistant.ide;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.ui.components.*;
import dev.mybatis.assistant.core.*;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;

/** No idle work. Each button launches one cancellable background task, bounded by its input. */
public final class AssistantPanel extends JPanel {
    private final Project project;private final JTabbedPane tabs=new JTabbedPane();
    private final JBTextArea ddl=area("CREATE TABLE users (\n  id BIGINT PRIMARY KEY AUTO_INCREMENT,\n  user_name VARCHAR(100) NOT NULL,\n  age INT,\n  created_at TIMESTAMP\n);");
    private final JTextField pkg=new JTextField(24),target=new JTextField(24);
    private final JComboBox<String> framework=new JComboBox<>(new String[]{"MyBatis","MyBatis-Plus"});
    private final JComboBox<String> dialect=new JComboBox<>(new String[]{"MySQL","PostgreSQL / ANSI"});
    private final JTabbedPane files=new JTabbedPane();private final JButton save=new JButton("写入预览文件");
    private Map<String,String> generated=Map.of();private long revision;
    private final JLabel generateStatus=new JLabel("先预览，再写入；同名文件会阻止写入。");
    private final JBTextArea logs=area("==> Preparing: SELECT * FROM users WHERE id = ? AND user_name = ?\n==> Parameters: 1(Long), 张三(String)");
    private final JBTextArea logOutput=area("");
    private final JBTextArea params=area("{\n  \"id\": 1,\n  \"name\": \"张三\",\n  \"ids\": [1, 2, 3]\n}");
    private final JBTextArea sqlOutput=area("");private final JTextField statementId=new JTextField(20);
    private final JLabel sqlStatus=new JLabel("在 XML 语句内放置光标，再点击预览。");
    public AssistantPanel(Project project){
        super(new BorderLayout());this.project=project;pkg.setText(AssistantSettings.get(project).basePackage);target.setText(Objects.requireNonNullElse(project.getBasePath(),""));
        tabs.addTab("生成 CRUD",generator());tabs.addTab("日志转 SQL",logPanel());tabs.addTab("动态 SQL 预览",sqlPanel());tabs.addTab("数据库执行",new DatabaseExecutionPanel(project));add(tabs,BorderLayout.CENTER);
    }
    public void select(int tab){tabs.setSelectedIndex(tab);}
    private JPanel generator(){
        JPanel top=new JPanel(new GridLayout(0,2,8,4));top.add(new JLabel("Java 基础包名"));top.add(pkg);top.add(new JLabel("写入模块目录"));top.add(target);top.add(new JLabel("框架"));top.add(framework);top.add(new JLabel("SQL 方言"));top.add(dialect);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));JButton preview=new JButton("从 DDL 生成预览");buttons.add(preview);buttons.add(save);save.setEnabled(false);
        DocumentListener changed=new DocumentListener(){public void insertUpdate(DocumentEvent e){invalidatePreview();}public void removeUpdate(DocumentEvent e){invalidatePreview();}public void changedUpdate(DocumentEvent e){invalidatePreview();}};
        ddl.getDocument().addDocumentListener(changed);pkg.getDocument().addDocumentListener(changed);target.getDocument().addDocumentListener(changed);framework.addActionListener(e->invalidatePreview());dialect.addActionListener(e->invalidatePreview());
        preview.addActionListener(e->{String text=ddl.getText(),base=pkg.getText().trim();boolean plus=framework.getSelectedIndex()==1,mysql=dialect.getSelectedIndex()==0;long version=revision;
            background("生成 MyBatis CRUD",preview,()->CodeGenerator.generate(NativeBridge.parseDdl(text),base,plus,mysql),result->{
                if(version!=revision){generateStatus.setText("输入已变更，请重新预览。");return;}generated=result;files.removeAll();
                result.forEach((path,source)->{JBTextArea code=area(source);code.setEditable(false);files.addTab(path.substring(path.lastIndexOf('/')+1),new JBScrollPane(code));files.setToolTipTextAt(files.getTabCount()-1,path);});save.setEnabled(true);generateStatus.setText("已生成 "+result.size()+" 个文件预览。");
            });
        });save.addActionListener(e->writeGenerated());
        JPanel header=new JPanel(new BorderLayout());header.add(top,BorderLayout.NORTH);header.add(buttons,BorderLayout.CENTER);header.add(generateStatus,BorderLayout.SOUTH);
        JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));panel.add(header,BorderLayout.NORTH);panel.add(split(new JBScrollPane(ddl),files),BorderLayout.CENTER);return panel;
    }
    private void invalidatePreview(){revision++;generated=Map.of();save.setEnabled(false);generateStatus.setText("输入已变更，请生成预览。");}
    private void writeGenerated(){
        if(generated.isEmpty())return;
        String base=target.getText().trim();VirtualFile root=LocalFileSystem.getInstance().findFileByPath(base);
        if(root==null || !root.isDirectory()){Messages.showErrorDialog(project,"写入模块目录不存在","MyBatis Assistant");return;}
        for(String path:generated.keySet())if(root.findFileByRelativePath(path)!=null){Messages.showErrorDialog(project,"文件已存在，未写入任何文件:\n"+path,"MyBatis Assistant");return;}
        List<VirtualFile> created=new ArrayList<>();
        WriteCommandAction.runWriteCommandAction(project,"生成 MyBatis CRUD",null,()->{
            try {
                for(var entry:generated.entrySet()){
                    String relative=entry.getKey();VirtualFile dir=VfsUtil.createDirectoryIfMissing(root.getPath()+"/"+relative.substring(0,relative.lastIndexOf('/')));
                    if(dir==null)throw new java.io.IOException("无法创建目录");
                    String filename=relative.substring(relative.lastIndexOf('/')+1);
                    if(dir.findChild(filename)!=null)throw new java.io.IOException("同名文件在预览后被创建: "+filename);
                    VirtualFile file=dir.createChildData(this,filename);created.add(file);file.setBinaryContent(entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                generateStatus.setText("已写入 "+created.size()+" 个文件，可使用 IDEA 撤销。");save.setEnabled(false);
            } catch(java.io.IOException ex){
                for(VirtualFile file:created)try{file.delete(this);}catch(java.io.IOException ignored){}
                Messages.showErrorDialog(project,ex.getMessage(),"生成失败");
            }
        });
    }
    private JPanel logPanel(){
        JButton convert=new JButton("还原 SQL");convert.addActionListener(e->{String input=logs.getText();background("还原 SQL 日志",convert,()->NativeBridge.logsToSql(input),logOutput::setText);});
        logOutput.setEditable(false);JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));controls.add(convert);controls.add(copy(logOutput));controls.add(new JLabel("交错日志请先按线程筛选；结果仅显示。"));
        JPanel panel=new JPanel(new BorderLayout());panel.add(controls,BorderLayout.NORTH);panel.add(split(new JBScrollPane(logs),new JBScrollPane(logOutput)),BorderLayout.CENTER);return panel;
    }
    private JPanel sqlPanel(){
        JButton render=new JButton("预览当前 XML 语句");
        render.addActionListener(e->{
            var editor=FileEditorManager.getInstance(project).getSelectedTextEditor();if(editor==null){sqlStatus.setText("请先打开 Mapper XML。");return;}
            PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());var file=PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            int offset=editor.getCaretModel().getOffset();String json=params.getText(),requestedId=statementId.getText().trim();
            background("渲染 MyBatis 动态 SQL",render,()->{
                MapperSnapshot.Input input=ReadAction.compute(()->{
                    if(file==null || !file.isValid())throw new IllegalArgumentException("XML 已关闭或无效");
                    XmlTag root=MapperModel.root(file);
                    if(root==null){
                        PsiMethod method=MapperLanguageSupport.findMethod(file.findElementAt(Math.min(offset,Math.max(0,file.getTextLength()-1))));
                        if(method==null || !MapperModel.annotated(method))throw new IllegalArgumentException("请打开 Mapper XML 或把光标放在 SQL 注解方法内");
                        return new MapperSnapshot.Input(AnnotationSql.mapperXml(method),method.getName(),json,List.of());
                    }
                    XmlTag statement=MapperModel.statement(file.findElementAt(Math.min(offset,Math.max(0,file.getTextLength()-1))));
                    String id=requestedId.isEmpty()?statement==null?null:statement.getAttributeValue("id"):requestedId;
                    if(id==null)throw new IllegalArgumentException("请把光标放在 select/insert/update/delete 内，或填写语句 id");
                    return MapperSnapshot.capture(file,id,json);
                });
                return input.render().display();
            },result->{sqlOutput.setText(result);sqlStatus.setText("已渲染当前参数分支；未连接数据库。");});
        });
        sqlOutput.setEditable(false);JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));controls.add(render);controls.add(new JLabel("语句 id（可选）"));controls.add(statementId);controls.add(copy(sqlOutput));
        JPanel parameterInput=new JPanel(new BorderLayout());parameterInput.add(new JLabel("JSON 参数：支持当前 XML 或常量 SQL 注解；iBATIS 也支持数组、标量和 null"),BorderLayout.NORTH);parameterInput.add(new JBScrollPane(params),BorderLayout.CENTER);
        JPanel panel=new JPanel(new BorderLayout());panel.add(controls,BorderLayout.NORTH);panel.add(split(parameterInput,new JBScrollPane(sqlOutput)),BorderLayout.CENTER);panel.add(sqlStatus,BorderLayout.SOUTH);return panel;
    }
    private <T> void background(String title,JButton button,Callable<T> work,java.util.function.Consumer<T> success){
        button.setEnabled(false);new Task.Backgroundable(project,title,true){
            T result;Throwable failure;
            @Override public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator){try{indicator.checkCanceled();result=work.call();indicator.checkCanceled();}catch(com.intellij.openapi.progress.ProcessCanceledException e){throw e;}catch(Exception|LinkageError e){failure=e;}}
            @Override public void onSuccess(){if(project.isDisposed())return;if(failure==null)success.accept(result);else Messages.showErrorDialog(project,Objects.requireNonNullElse(failure.getMessage(),failure.toString()),"MyBatis Assistant");}
            @Override public void onFinished(){if(!project.isDisposed())button.setEnabled(true);}
        }.queue();
    }
    private static JButton copy(JTextArea text){JButton button=new JButton("复制结果");button.addActionListener(e->Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()),null));return button;}
    private static JBTextArea area(String text){JBTextArea a=new JBTextArea(text);a.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));a.setTabSize(2);a.setBorder(BorderFactory.createEmptyBorder(6,6,6,6));return a;}
    private static JSplitPane split(Component top,Component bottom){JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,top,bottom);split.setResizeWeight(.45);return split;}
}
