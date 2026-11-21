package dev.mybatis.assistant.ide;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import javax.swing.*;
import java.awt.*;

public final class AssistantConfigurable implements Configurable {
    private final Project project;private JPanel panel;
    private JCheckBox completion,inspections,gutter,actual,dollar;private JTextField pkg;
    private JTextArea namespaceBindings;
    private JTextArea typeAliasBindings;
    private JTextField typeAliasPackages;
    private JCheckBox ognl,column,console;
    private RuntimeSettingsPanel runtime;
    public AssistantConfigurable(Project project){this.project=project;}
    @Override public String getDisplayName(){return "MyBatis Assistant";}
    @Override public JComponent createComponent(){
        panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));
        completion=new JCheckBox("启用参数与映射补全");inspections=new JCheckBox("启用基础映射检查");gutter=new JCheckBox("显示 Mapper / XML 跳转图标");
        actual=new JCheckBox("MyBatis 使用 Java 参数名（项目编译需启用 -parameters；否则建议使用 @Param）");dollar=new JCheckBox("提示 ${} / $…$ 直接拼接 SQL");
        for(JCheckBox b:new JCheckBox[]{completion,inspections,gutter,actual,dollar}){b.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(b);}
        panel.add(new JLabel("代码生成默认包名"));pkg=new JTextField(30);pkg.setMaximumSize(new Dimension(600,30));pkg.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(pkg);
        panel.add(Box.createVerticalStrut(12));panel.add(new JLabel("iBATIS 自定义映射：每行 接口全名=namespace（无 namespace 时填 XML 文件名，不含扩展名）"));
        namespaceBindings=new JTextArea(7,55);JScrollPane mappings=new JScrollPane(namespaceBindings);mappings.setMaximumSize(new Dimension(800,180));mappings.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(mappings);
        ognl=new JCheckBox("检查 OGNL 语法、已知属性和方法");column=new JCheckBox("检查 resultMap 重复列及明确的 SELECT 输出列");console=new JCheckBox("控制台 Parameters 行提供 SQL 还原链接（点击后计算）");
        for(JCheckBox b:new JCheckBox[]{ognl,column,console}){b.setAlignmentX(Component.LEFT_ALIGNMENT);panel.add(b);}
        panel.add(new JLabel("补充 typeAlias 包名（逗号分隔；自动读取 MyBatis XML 与常量 Spring 配置）"));
        typeAliasPackages=new JTextField(40);typeAliasPackages.setMaximumSize(new Dimension(800,30));panel.add(typeAliasPackages);
        panel.add(new JLabel("补充类型别名：每行 别名=类全名"));typeAliasBindings=new JTextArea(4,55);JScrollPane aliasPane=new JScrollPane(typeAliasBindings);aliasPane.setMaximumSize(new Dimension(800,120));panel.add(aliasPane);
        panel.add(Box.createVerticalStrut(18));panel.add(new JLabel("仅在用户操作时加载 Rust；索引由 IDEA 按 XML 文件变更更新。"));runtime=new RuntimeSettingsPanel(project);reset();JTabbedPane tabs=new JTabbedPane();tabs.addTab("编辑与映射",new JScrollPane(panel));tabs.addTab("数据库 / MCP / Spring",new JScrollPane(runtime));return tabs;
    }
    @Override public boolean isModified(){var s=AssistantSettings.get(project);return completion.isSelected()!=s.completion || inspections.isSelected()!=s.inspections || gutter.isSelected()!=s.gutter || actual.isSelected()!=s.useActualParamName || dollar.isSelected()!=s.warnDollar || !pkg.getText().equals(s.basePackage) || !namespaceBindings.getText().equals(s.namespaceBindings) || ognl.isSelected()!=s.ognlChecks || column.isSelected()!=s.columnChecks || console.isSelected()!=s.consoleSql || !typeAliasPackages.getText().equals(s.typeAliasPackages) || !typeAliasBindings.getText().equals(s.typeAliasBindings) || runtime.modified();}
    @Override public void apply(){var s=AssistantSettings.get(project);s.completion=completion.isSelected();s.inspections=inspections.isSelected();s.gutter=gutter.isSelected();s.useActualParamName=actual.isSelected();s.warnDollar=dollar.isSelected();s.basePackage=pkg.getText().trim();s.namespaceBindings=namespaceBindings.getText();s.ognlChecks=ognl.isSelected();s.columnChecks=column.isSelected();s.consoleSql=console.isSelected();s.typeAliasPackages=typeAliasPackages.getText();s.typeAliasBindings=typeAliasBindings.getText();runtime.apply();project.getService(AssistantSettings.class).changed();com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart();}
    @Override public void reset(){if(panel==null)return;var s=AssistantSettings.get(project);completion.setSelected(s.completion);inspections.setSelected(s.inspections);gutter.setSelected(s.gutter);actual.setSelected(s.useActualParamName);dollar.setSelected(s.warnDollar);pkg.setText(s.basePackage);namespaceBindings.setText(s.namespaceBindings);ognl.setSelected(s.ognlChecks);column.setSelected(s.columnChecks);console.setSelected(s.consoleSql);typeAliasPackages.setText(s.typeAliasPackages);typeAliasBindings.setText(s.typeAliasBindings);if(runtime!=null)runtime.reset();}
    @Override public void disposeUIResources(){panel=null;completion=null;inspections=null;gutter=null;actual=null;dollar=null;pkg=null;namespaceBindings=null;ognl=null;column=null;console=null;typeAliasPackages=null;typeAliasBindings=null;runtime=null;}
}
