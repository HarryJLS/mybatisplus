package dev.mybatis.assistant.ide;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import java.util.*;
import java.util.regex.Matcher;
import org.jetbrains.annotations.NotNull;

public final class MapperInspection extends LocalInspectionTool {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,boolean onTheFly){
        if(!AssistantSettings.get(holder.getProject()).inspections || DumbService.isDumb(holder.getProject()))return PsiElementVisitor.EMPTY_VISITOR;
        return new XmlElementVisitor(){
            @Override public void visitXmlTag(@NotNull XmlTag tag){
                XmlTag root=MapperModel.root(tag);if(root==null)return;
                String id=tag.getAttributeValue("id");
                if(id!=null && tag.getParentTag()==root){
                    var peers=MapperModel.metadata(root).tags().getOrDefault(MapperModel.localId(root,id),List.of());
                    if(peers.stream().filter(t->sameKind(t,tag) && Objects.equals(t.getAttributeValue("databaseId"),tag.getAttributeValue("databaseId"))).count()>1)problem(holder,tag.getAttribute("id"),"重复的映射 id: "+id);
                    if(MapperModel.isStatement(tag) && !MapperModel.isLegacy(root)){
                        PsiClass cls=MapperModel.mapperClass(root);
                        if(cls!=null && cls.findMethodsByName(id,true).length==0)problem(holder,tag.getAttribute("id"),"Mapper 接口缺少方法: "+id);
                    }
                }
                for(String attribute:List.of("refid","resultMap","extends","parameterMap")){
                    XmlAttribute a=tag.getAttribute(attribute);if(a==null || a.getValue()==null)continue;
                    String kind=attribute.equals("refid")?"sql":attribute.equals("parameterMap")?"parameterMap":"resultMap";
                    for(String token:a.getValue().split("[,\\s]+"))if(!token.isEmpty() && !token.contains("${") && MapperModel.definition(tag,token,kind)==null)problem(holder,a,"找不到 "+kind+": "+token);
                }
                if(!MapperModel.isLegacy(root) && AssistantSettings.get(tag.getProject()).ognlChecks){
                    ParameterModel model=ParameterModel.of(MapperModel.method(MapperModel.statement(tag)==null?tag:MapperModel.statement(tag)),tag);
                    for(String name:List.of("test","collection","value")){
                        if(name.equals("value") && !tag.getName().equals("bind") || name.equals("collection") && !tag.getName().equals("foreach"))continue;
                        XmlAttribute expression=tag.getAttribute(name);if(expression!=null)OgnlChecks.inspect(expression,model,holder);
                    }
                }
                if(tag.getName().equals("resultMap") && AssistantSettings.get(tag.getProject()).columnChecks)checkColumns(tag,holder);
                if(!MapperModel.isLegacy(root) && tag.getName().equals("select"))checkResultType(tag,holder);
                XmlAttribute property=tag.getAttribute("property");
                if(property!=null && property.getValue()!=null){
                    if(MapperModel.isLegacy(root) && MapperReferences.parameterProperty(tag)){
                        XmlTag statement=MapperModel.statement(tag);ParameterModel model=ParameterModel.of(statement==null?null:MapperModel.method(statement),tag);
                        if(!model.dynamic() && model.resolve(property.getValue())==null)problem(holder,property,"找不到 iBATIS 参数属性: "+property.getValue());
                        return;
                    }
                    PsiType type=MappingTypes.ownerType(tag);
                    if(type!=null && !ParameterModel.isMap(type) && !ParameterModel.properties(type).containsKey(property.getValue()))problem(holder,property,"实体中找不到属性: "+property.getValue());
                }
            }
            @Override public void visitXmlText(@NotNull XmlText text){
                XmlTag statement=MapperModel.statement(text);if(statement==null)return;
                ParameterModel model=ParameterModel.of(MapperModel.method(statement),text);
                boolean legacy=MapperModel.isLegacy(MapperModel.root(text));int group=legacy?2:1;
                Matcher matcher=(legacy?MapperReferences.LEGACY_PLACEHOLDER:MapperReferences.PLACEHOLDER).matcher(text.getText());
                while(matcher.find()){
                    String path=matcher.group(group);
                    if(model.resolve(path)==null && !model.dynamic() && !path.equals("_databaseId"))holder.registerProblem(text,new com.intellij.openapi.util.TextRange(matcher.start(group),matcher.end(group)),"找不到参数或属性: "+path);
                    if(text.getText().charAt(matcher.start())=='$' && AssistantSettings.get(text.getProject()).warnDollar)holder.registerProblem(text,new com.intellij.openapi.util.TextRange(matcher.start(),matcher.end()),(legacy?"$…$":"${}")+" 直接拼接 SQL，请确认输入来自可信白名单");
                }
            }
        };
    }
    private static boolean sameKind(XmlTag a,XmlTag b){return MapperModel.isStatement(a) && MapperModel.isStatement(b) || a.getName().equals(b.getName());}
    private static void problem(ProblemsHolder holder,XmlAttribute a,String message){if(a!=null && a.getValueElement()!=null)holder.registerProblem(a.getValueElement(),message,ProblemHighlightType.GENERIC_ERROR_OR_WARNING);}
    private static void checkColumns(XmlTag map,ProblemsHolder holder){
        checkColumnFields(map,StatementSql.columns(map),holder,0);
    }
    private static void checkColumnFields(XmlTag node,dev.mybatis.assistant.core.SqlProjection.Result projection,ProblemsHolder holder,int depth){
        if(depth>32)return;Set<String> seen=new HashSet<>();
        for(XmlTag field:node.getSubTags()){
            if(Set.of("association","collection","constructor").contains(field.getName())){checkColumnFields(field,projection,holder,depth+1);continue;}
            String column=field.getAttributeValue("column");if(column==null || !Set.of("id","result").contains(field.getName()))continue;
            if(!seen.add(column.toLowerCase(Locale.ROOT)))problem(holder,field.getAttribute("column"),"resultMap 重复列: "+column);
            String output=StatementSql.columnPrefix(field)+column;
            if(projection.complete() && projection.columns().stream().noneMatch(c->c.equalsIgnoreCase(output)))problem(holder,field.getAttribute("column"),"SELECT 输出中找不到 resultMap 列: "+output);
        }
    }
    private static void checkResultType(XmlTag statement,ProblemsHolder holder){
        String name=statement.getAttributeValue("resultType");if(name==null)return;PsiMethod method=MapperModel.method(statement);if(method==null)return;
        PsiClass declared=MapperModel.typeClass(statement,name);PsiType returned=MapperModel.entityType(method.getReturnType());
        if(declared==null || returned==null || ParameterModel.isMap(returned))return;
        PsiType actual=JavaPsiFacade.getElementFactory(statement.getProject()).createType(declared);
        if(!com.intellij.psi.util.TypeConversionUtil.isAssignable(returned,actual))problem(holder,statement.getAttribute("resultType"),"resultType 与 Mapper 方法返回类型不兼容");
    }
}
