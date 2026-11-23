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
            if(!(attr.getParent() instanceof XmlAttribute a))return;
            XmlTag tag=a.getParent();String name=a.getName();
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
                String token=before.substring(Math.max(before.lastIndexOf(','),before.lastIndexOf(' '))+1);int dot=token.lastIndexOf('.');
                List<XmlTag> roots=dot<0?MapperModel.xmls(pos.getProject(),root.getAttributeValue("namespace")):MapperModel.xmls(pos.getProject(),token.substring(0,dot));
                Set<XmlTag> candidates=new LinkedHashSet<>(roots);if(dot<0)candidates.add(root);CompletionResultSet target=result.withPrefixMatcher(dot<0?token:token.substring(dot+1));
                for(XmlTag candidate:candidates)for(var entry:MapperModel.metadata(candidate).tags().entrySet())if(entry.getValue().stream().anyMatch(t->kind.equals(t.getName())))target.addElement(LookupElementBuilder.create(entry.getKey()).withTypeText(kind));
                return;
            }
            if(name.equals("property")){if(MapperModel.isLegacy(MapperModel.root(tag)) && MapperReferences.parameterProperty(tag))parameters(pos,before,result);else{int dot=before.lastIndexOf('.');var owner=MappingTypes.ownerType(tag);var nested=dot<0?null:MappingTypes.property(owner,before.substring(0,dot));add(result.withPrefixMatcher(dot<0?before:before.substring(dot+1)),ParameterModel.properties(dot<0?owner:nested==null?null:nested.type()));}return;}
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
                    String option=inside.substring(inside.lastIndexOf(',')+1).stripLeading();int equal=option.indexOf('=');
                    CompletionResultSet target=result.withPrefixMatcher(equal<0?option:option.substring(equal+1).stripLeading());
                    if(equal<0)for(String key:dev.mybatis.assistant.core.SqlParameters.OPTIONS)target.addElement(LookupElementBuilder.create(key+"="));
                    else if(option.substring(0,equal).strip().equals("jdbcType"))for(var type:org.apache.ibatis.type.JdbcType.values())target.addElement(LookupElementBuilder.create(type.name()));
                    else if(option.substring(0,equal).strip().equals("mode"))for(String mode:List.of("IN","OUT","INOUT"))target.addElement(LookupElementBuilder.create(mode));
                    else if(option.substring(0,equal).strip().equals("javaType"))TypeAliases.names(pos).forEach((alias,type)->target.addElement(LookupElementBuilder.create(alias).withTypeText(type)));
                    return;
                }parameters(pos,inside,result);if(before.charAt(open)=='$')methods(pos,inside,result);return;
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
        ParameterModel model=ParameterModel.of(method,pos);
        String path=expression(before);int dot=path.lastIndexOf('.');String prefix=dot<0?path:path.substring(dot+1);
        PsiType type=dot<0?null:OgnlTypes.type(path.substring(0,dot),model,pos);
        add(result.withPrefixMatcher(prefix),dot<0?model.symbols():ParameterModel.properties(type));
    }
    private static String expression(String before){
        int depth=0;char quote=0;
        for(int i=before.length()-1;i>=0;i--){char c=before.charAt(i);if(quote!=0){if(c==quote && (i==0 || before.charAt(i-1)!='\\'))quote=0;continue;}if(c=='\'' || c=='"'){quote=c;continue;}if(c==')' || c==']')depth++;else if(c=='(' || c=='['){if(depth==0)return before.substring(i+1);depth--;}else if(depth==0 && (Character.isWhitespace(c) || "=!<>+?:,&|".indexOf(c)>=0))return before.substring(i+1);}
        return before;
    }
    private static void add(CompletionResultSet result,Map<String,ParameterModel.Symbol> symbols){
        symbols.forEach((name,s)->result.addElement(LookupElementBuilder.create(name).withTypeText(s.type()==null?"动态变量":s.type().getPresentableText()).withIcon(com.intellij.icons.AllIcons.Nodes.Property)));
    }
    private static void methods(PsiElement pos,String before,CompletionResultSet result){
        String path=expression(before);int dot=path.lastIndexOf('.');if(dot<0)return;
        PsiType owner=OgnlTypes.type(path.substring(0,dot),ParameterModel.of(null,pos),pos);
        if(!(owner instanceof PsiClassType type))return;PsiClass cls=type.resolve();if(cls==null)return;
        CompletionResultSet target=result.withPrefixMatcher(path.substring(dot+1));Set<String> seen=new HashSet<>();
        for(PsiMethod method:cls.getAllMethods())if(method.hasModifierProperty(PsiModifier.PUBLIC) && !method.hasModifierProperty(PsiModifier.STATIC) && seen.add(method.getName()))target.addElement(LookupElementBuilder.create(method.getName()+(method.getParameterList().getParametersCount()==0?"()":"(")).withPresentableText(method.getName()+"("+String.join(", ",Arrays.stream(method.getParameterList().getParameters()).map(p->p.getType().getPresentableText()).toList())+")").withTypeText(method.getReturnType()==null?"":method.getReturnType().getPresentableText()));
    }
}
