package dev.mybatis.assistant.ide;

import com.google.gson.GsonBuilder;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import dev.mybatis.assistant.core.*;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Separate execution flow: review the rendered statement, then explicitly query or commit. */
final class DatabaseExecutionPanel extends JPanel {
    private final Project project;
    private final JComboBox<DatabaseProvider.Source> sources=new JComboBox<>();
    private final JComboBox<String> mode=new JComboBox<>(new String[]{"查询（仅 SELECT）","试跑后回滚","执行并提交"});
    private final JTextArea parameters=new JTextArea("{}",7,40),preview=new JTextArea(),output=new JTextArea();
    private final JSpinner rows=new JSpinner(new SpinnerNumberModel(100,1,1000,1)),timeout=new JSpinner(new SpinnerNumberModel(15,1,60,1));
    private final JButton execute=new JButton("执行已预览的 SQL");
    private MapperSnapshot.Input reviewed;private SqlRenderer.Result rendered;
    DatabaseExecutionPanel(Project project){
        super(new BorderLayout(6,6));this.project=project;JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));JButton refresh=new JButton("刷新数据源"),render=new JButton("预览当前语句"),tables=new JButton("读取表结构");
        controls.add(refresh);controls.add(sources);controls.add(mode);controls.add(new JLabel("最多行数"));controls.add(rows);controls.add(new JLabel("超时秒数"));controls.add(timeout);controls.add(render);controls.add(execute);controls.add(tables);execute.setEnabled(false);
        refresh.addActionListener(e->background(refresh,"读取数据源",()->DatabaseAccess.sources(project),list->{sources.removeAllItems();list.forEach(sources::addItem);}));
        sources.addActionListener(e->invalidateReview());parameters.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){invalidateReview();}public void removeUpdate(DocumentEvent e){invalidateReview();}public void changedUpdate(DocumentEvent e){invalidateReview();}});
        render.addActionListener(e->{try{
            MapperSnapshot.Input request=MapperSnapshot.editor(project,parameters.getText());background(render,"渲染待执行 SQL",request::render,result->{reviewed=request;rendered=result;preview.setText(result.display());execute.setEnabled(result.notes().isEmpty());});
        }catch(Exception error){Messages.showErrorDialog(project,DatabaseAccess.error(error),"SQL 预览");}});
        execute.addActionListener(e->execute());tables.addActionListener(e->tableInfo(tables));
        preview.setEditable(false);output.setEditable(false);for(JTextArea area:new JTextArea[]{parameters,preview,output})area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        JTabbedPane results=new JTabbedPane();results.addTab("待执行 SQL",new JScrollPane(preview));results.addTab("数据库结果",new JScrollPane(output));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(parameters),results);split.setResizeWeight(.25);add(controls,BorderLayout.NORTH);add(split,BorderLayout.CENTER);add(new JLabel("JSON 使用实际绑定名称。数据库连接在明确执行时创建，完成后关闭。"),BorderLayout.SOUTH);
    }
    private void invalidateReview(){reviewed=null;rendered=null;execute.setEnabled(false);}
    private void execute(){
        DatabaseProvider.Source source=(DatabaseProvider.Source)sources.getSelectedItem();if(source==null || reviewed==null || rendered==null)return;
        try{
            if(!reviewed.equals(MapperSnapshot.editor(project,parameters.getText()))){invalidateReview();throw new IllegalArgumentException("语句或参数已变化，请重新预览");}
            SqlExecution.Mode selected=SqlExecution.Mode.values()[mode.getSelectedIndex()];if(source.readOnly() && selected!=SqlExecution.Mode.READ_ONLY)throw new IllegalArgumentException("此数据源为只读");
            SqlExecutionPolicy.validate(rendered.sql(),selected);SqlRenderer.Result sql=rendered;
            if(selected==SqlExecution.Mode.COMMIT){
                String text="数据源："+source.name()+"\n模式：执行并提交\n\n"+sql.display();
                DialogWrapper confirm=new DialogWrapper(project,false){ {setTitle("确认数据库提交");setOKButtonText("执行并提交");init();} @Override protected JComponent createCenterPanel(){JTextArea area=new JTextArea(text);area.setEditable(false);JScrollPane pane=new JScrollPane(area);pane.setPreferredSize(new Dimension(760,420));return pane;} };
                if(!confirm.showAndGet())return;
            }
            var options=new SqlExecution.Options(selected,(Integer)rows.getValue(),(Integer)timeout.getValue());
            background(execute,"执行 MyBatis SQL",()->{try(JdbcSession session=DatabaseAccess.open(project,source.id())){return SqlExecution.execute(session.connection(),sql.sql(),sql.parameters(),options,()->ProgressManager.getInstance().getProgressIndicator()!=null && ProgressManager.getInstance().getProgressIndicator().isCanceled());}},result->output.setText(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result)));
        }catch(Exception error){Messages.showErrorDialog(project,DatabaseAccess.error(error),"SQL 执行");}
    }
    private void tableInfo(JButton button){
        DatabaseProvider.Source source=(DatabaseProvider.Source)sources.getSelectedItem();if(source==null)return;
        String table=Messages.showInputDialog(project,"精确表名（留空列出表）","读取表结构",null);if(table==null)return;
        String schema=Messages.showInputDialog(project,"schema（可留空）","读取表结构",null);if(schema==null)return;
        background(button,"读取数据库表结构",()->{try(JdbcSession session=DatabaseAccess.open(project,source.id())){return table.isBlank()?JdbcMetadata.tables(session.connection(),schema.isBlank()?null:schema,"",100):JdbcMetadata.columns(session.connection(),schema.isBlank()?null:schema,table);}},result->output.setText(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result)));
    }
    private <T> void background(JButton button,String title,Callable<T> work,java.util.function.Consumer<T> success){
        button.setEnabled(false);new Task.Backgroundable(project,title,true){T result;Exception error;
            @Override public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator){try{indicator.checkCanceled();result=work.call();indicator.checkCanceled();}catch(com.intellij.openapi.progress.ProcessCanceledException e){throw e;}catch(Exception e){error=e;}catch(LinkageError e){error=new IllegalArgumentException("驱动加载失败："+e.getClass().getSimpleName());}}
            @Override public void onSuccess(){if(project.isDisposed())return;if(error==null)success.accept(result);else Messages.showErrorDialog(project,DatabaseAccess.error(error),title);}
            @Override public void onFinished(){if(!project.isDisposed())button.setEnabled(button!=execute || reviewed!=null && rendered!=null && rendered.notes().isEmpty());}
        }.queue();
    }
}
