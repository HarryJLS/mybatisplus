package dev.mybatis.assistant.ide;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.util.ProcessingContext;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class MapperCompletion extends CompletionContributor {
    public MapperCompletion(){extend(CompletionType.BASIC,PlatformPatterns.psiElement(),new CompletionProvider<>(){
        @Override protected void addCompletions(@NotNull CompletionParameters p,@NotNull ProcessingContext context,@NotNull CompletionResultSet result){complete(p,result);}
    });}
    private static void complete(CompletionParameters p,CompletionResultSet result){
        PsiElement pos=p.getPosition();
        if(DumbService.isDumb(pos.getProject()) || !AssistantSettings.get(pos.getProject()).completion || MapperModel.root(pos)==null)return;
        XmlAttributeValue attr=PsiTreeUtil.getParentOfType(pos,XmlAttributeValue.class,false);
        if(attr!=null){
            XmlAttribute a=(XmlAttribute)attr.getParent();XmlTag tag=a.getParent();String name=a.getName();
            int offset=Math.max(0,p.getOffset()-attr.getValueTextRange().getStartOffset());String value=attr.getValue();String before=value.substring(0,Math.min(value.length(),offset));
            if(Set.of("resultType","parameterType","type","ofType","javaType","resultClass","parameterClass","class").contains(name)){
                TypeAliases.names(attr).forEach((alias,type)->result.addElement(LookupElementBuilder.create(alias).withTypeText(type)));return;
            }
            if(name.equals("column")){
                XmlTag map=tag;while(map!=null && !map.getName().equals("resultMap"))map=map.getParentTag();
                String prefix=StatementSql.columnPrefix(tag);
                if(map!=null)for(String column:StatementSql.columns(map).columns())if(column.startsWith(prefix))result.addElement(LookupElementBuilder.create(column.substring(prefix.length())).withTypeText("SELECT 输出列"));
                return;
            }
            if(name.equals("refid") || name.equals("resultMap") || name.equals("extends") || name.equals("parameterMap")){
                String kind=name.equals("refid")?"sql":name.equals("parameterMap")?"parameterMap":"resultMap";XmlTag root=MapperModel.root(attr);
                for(var entry:MapperModel.metadata(root).tags().entrySet())if(entry.getValue().stream().anyMatch(t->kind.equals(t.getName())))result.addElement(LookupElementBuilder.create(entry.getKey()).withTypeText(kind));
                return;
            }
            if(name.equals("property")){if(MapperModel.isLegacy(MapperModel.root(tag)) && MapperReferences.parameterProperty(tag))parameters(pos,before,result);else add(result,ParameterModel.properties(MappingTypes.ownerType(tag)));return;}
            if(name.equals("compareProperty") && MapperModel.isLegacy(MapperModel.root(tag))){parameters(pos,before,result);return;}
            if(Set.of("test","collection","keyProperty").contains(name) || name.equals("value") && "bind".equals(tag.getName())){parameters(pos,before,result);methods(pos,before,result);return;}
        }
        XmlText text=PsiTreeUtil.getParentOfType(pos,XmlText.class,false);
        if(text!=null){
            int offset=Math.min(text.getTextLength(),Math.max(0,p.getOffset()-text.getTextRange().getStartOffset()));String before=text.getText().substring(0,offset);
            if(MapperModel.isLegacy(MapperModel.root(text))){
                int hash=before.lastIndexOf('#'),dollar=before.lastIndexOf('$'),open=Math.max(hash,dollar);
                if(open>=0){String inside=before.substring(open+1);if(inside.matches("[\\p{L}\\p{N}_.\\[\\]]*") && before.chars().filter(c->c==before.charAt(open)).count()%2==1)parameters(pos,inside,result);}
                return;
            }
            int open=Math.max(before.lastIndexOf("#{"),before.lastIndexOf("${"));
            if(open>=0 && before.indexOf('}',open)<0){
                String inside=before.substring(open+2);if(inside.contains(",")){
                    if(inside.matches("(?s).*jdbcType\\s*=\\s*[A-Z]*$"))for(var type:org.apache.ibatis.type.JdbcType.values())result.addElement(LookupElementBuilder.create(type.name()));
                    return;
                }parameters(pos,inside,result);return;
            }
            for(String keyword:List.of("SELECT","FROM","WHERE","AND","OR","JOIN","LEFT JOIN","ON","GROUP BY","ORDER BY","HAVING","LIMIT","INSERT INTO","VALUES","UPDATE","SET","DELETE FROM","IS NULL","IS NOT NULL","IN","EXISTS"))result.addElement(LookupElementBuilder.create(keyword).withTypeText("SQL"));
            XmlTag statement=MapperModel.statement(pos);
            if(statement!=null){
                for(String column:StatementSql.projection(statement).columns())result.addElement(LookupElementBuilder.create(column).withTypeText("SELECT 输出列"));
                String map=statement.getAttributeValue("resultMap");XmlTag definition=map==null?null:MapperModel.definition(statement,map,"resultMap");
                if(definition!=null)for(XmlTag field:definition.getSubTags()){String column=field.getAttributeValue("column");if(column!=null)result.addElement(LookupElementBuilder.create(column).withTypeText("resultMap 列"));}
            }
        }
    }
    private static void parameters(PsiElement pos,String before,CompletionResultSet result){
        XmlTag statement=MapperModel.statement(pos);PsiMethod method=statement==null?null:MapperModel.method(statement);
        if(method==null && !MapperModel.isLegacy(MapperModel.root(pos)))return;ParameterModel model=ParameterModel.of(method,pos);
        String path=before.replaceAll("(?s).*[^\\p{L}\\p{N}_.\\[\\]]","");int dot=path.lastIndexOf('.');String prefix=dot<0?path:path.substring(dot+1);
        add(result.withPrefixMatcher(prefix),model.complete(path));
    }
    private static void add(CompletionResultSet result,Map<String,ParameterModel.Symbol> symbols){
        symbols.forEach((name,s)->result.addElement(LookupElementBuilder.create(name).withTypeText(s.type()==null?"动态变量":s.type().getPresentableText()).withIcon(com.intellij.icons.AllIcons.Nodes.Property)));
    }
    private static void methods(PsiElement pos,String before,CompletionResultSet result){
        String path=before.replaceAll("(?s).*[^\\p{L}\\p{N}_.\\[\\]]","");int dot=path.lastIndexOf('.');if(dot<0)return;
        XmlTag statement=MapperModel.statement(pos);if(statement==null)return;
        var symbol=ParameterModel.of(MapperModel.method(statement),pos).resolve(path.substring(0,dot));
        if(symbol==null || !(symbol.type() instanceof PsiClassType type))return;PsiClass cls=type.resolve();if(cls==null)return;
        CompletionResultSet target=result.withPrefixMatcher(path.substring(dot+1));Set<String> seen=new HashSet<>();
        for(PsiMethod method:cls.getAllMethods())if(method.hasModifierProperty(PsiModifier.PUBLIC) && !method.hasModifierProperty(PsiModifier.STATIC) && method.getParameterList().getParametersCount()==0 && seen.add(method.getName()))target.addElement(LookupElementBuilder.create(method.getName()+"()").withTypeText(method.getReturnType()==null?"":method.getReturnType().getPresentableText()));
    }
}
