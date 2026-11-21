package dev.mybatis.assistant.ide;

import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.ide.BuiltInServerManager;
import javax.swing.*;
import java.awt.*;
import java.util.*;

/** Explicit connection settings. Secrets are saved in PasswordSafe, never in project XML. */
final class RuntimeSettingsPanel extends JPanel {
    private final Project project;
    private final JTextField url=new JTextField(45),driver=new JTextField(45),username=new JTextField(45);
    private final JTextArea jars=new JTextArea(4,45);
    private final JPasswordField password=new JPasswordField(45);
    private final JCheckBox mcp=new JCheckBox("启用此项目 MCP"),read=new JCheckBox("允许 MCP 读取数据库表结构和执行查询"),dml=new JCheckBox("允许 MCP 事务回滚试跑 INSERT/UPDATE/DELETE"),spring=new JCheckBox("启用 Spring Mapper Bean 识别和 MapperScan 检查");
    RuntimeSettingsPanel(Project project){
        super(new BorderLayout());this.project=project;JPanel fields=new JPanel();fields.setLayout(new BoxLayout(fields,BoxLayout.Y_AXIS));
        fields.add(new JLabel("可使用已配置的 IDEA Database 数据源，也可填写独立 JDBC 连接"));
        addField(fields,"JDBC URL",url);addField(fields,"驱动类（如 org.postgresql.Driver）",driver);addField(fields,"本地 JDBC 驱动文件：每行一个完整路径",new JScrollPane(jars));addField(fields,"用户名",username);addField(fields,"新密码（留空保留已保存密码）",password);
        JButton clear=new JButton("清除此连接已保存的密码");clear.addActionListener(e->ApplicationManager.getApplication().executeOnPooledThread(()->PasswordSafe.getInstance().set(DatabaseAccess.credentials(project),null)));fields.add(clear);
        for(JCheckBox box:new JCheckBox[]{mcp,read,dml,spring})fields.add(box);
        fields.add(new JLabel("MCP 使用 IDEA 内置服务；关闭项目或重启 IDEA 后需重新复制令牌配置"));
        JButton config=new JButton("查看 MCP 客户端连接配置");config.addActionListener(e->showConfig(project));fields.add(config);add(fields,BorderLayout.NORTH);reset();
    }
    private static void addField(JPanel panel,String label,JComponent component){panel.add(new JLabel(label));component.setMaximumSize(new Dimension(850,component instanceof JScrollPane?100:32));component.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(component);}
    boolean modified(){var s=AssistantSettings.get(project);return !url.getText().equals(s.jdbcUrl) || !driver.getText().equals(s.jdbcDriver) || !username.getText().equals(s.jdbcUsername) || !jars.getText().equals(s.jdbcJars) || password.getDocument().getLength()>0 || mcp.isSelected()!=s.mcpEnabled || read.isSelected()!=s.mcpDatabaseRead || dml.isSelected()!=s.mcpDmlRollback || spring.isSelected()!=s.springIntegration;}
    void apply(){
        var s=AssistantSettings.get(project);s.jdbcUrl=url.getText().trim();s.jdbcDriver=driver.getText().trim();s.jdbcUsername=username.getText();s.jdbcJars=jars.getText();s.mcpEnabled=mcp.isSelected();s.mcpDatabaseRead=read.isSelected();s.mcpDmlRollback=dml.isSelected();s.springIntegration=spring.isSelected();
        char[] secret=password.getPassword();password.setText("");if(secret.length>0)ApplicationManager.getApplication().executeOnPooledThread(()->{try{DatabaseAccess.savePassword(project,secret);}finally{Arrays.fill(secret,'\0');}});
    }
    void reset(){var s=AssistantSettings.get(project);url.setText(s.jdbcUrl);driver.setText(s.jdbcDriver);username.setText(s.jdbcUsername);jars.setText(s.jdbcJars);password.setText("");mcp.setSelected(s.mcpEnabled);read.setSelected(s.mcpDatabaseRead);dml.setSelected(s.mcpDmlRollback);spring.setSelected(s.springIntegration);}
    static void showConfig(Project project){
        new Task.Backgroundable(project,"读取 MCP 连接信息",true){
            int port;
            @Override public void run(@org.jetbrains.annotations.NotNull ProgressIndicator indicator){indicator.checkCanceled();port=BuiltInServerManager.getInstance().waitForStart().getPort();}
            @Override public void onSuccess(){if(!project.isDisposed()){
                var service=project.getService(McpProjectService.class);JTabbedPane tabs=new JTabbedPane();tabs.addTab("JSON 客户端",text(service.clientConfig(port)));tabs.addTab("Codex TOML",text(service.codexConfig(port)));
                tabs.setPreferredSize(new Dimension(760,330));JPanel panel=new JPanel(new BorderLayout());panel.add(new JLabel(AssistantSettings.get(project).mcpEnabled?"项目 MCP 已启用；令牌仅用于此项目":"请先应用设置，启用此项目 MCP"),BorderLayout.NORTH);panel.add(tabs,BorderLayout.CENTER);
                new com.intellij.openapi.ui.DialogWrapper(project,false){ {setTitle("MCP 连接配置");setOKButtonText("关闭");init();} @Override protected JComponent createCenterPanel(){return panel;} }.show();
            }}
        }.queue();
    }
    private static JScrollPane text(String value){JBTextArea area=new JBTextArea(value);area.setEditable(false);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));return new JScrollPane(area);}
}
