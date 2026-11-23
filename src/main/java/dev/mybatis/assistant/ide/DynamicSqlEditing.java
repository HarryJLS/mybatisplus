package dev.mybatis.assistant.ide;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.*;
import java.util.*;
import java.util.regex.*;

/** A bounded, representative branch for each SQL text host, without evaluating conditions. */
final class DynamicSqlEditing {
    private DynamicSqlEditing() {}
    record Place(TextRange range,String prefix,String suffix) {}
    private record Chunk(String text,int start,int end) {}
    private static final Key<CachedValue<List<Place>>> CACHE=Key.create("mybatis.assistant.dynamic.sql.editing");
    static List<Place> places(XmlText host){
        return CachedValuesManager.getManager(host.getProject()).getCachedValue(host,CACHE,()->{
            Builder builder=new Builder(host);List<Place> result;
            try{result=builder.build();}catch(IllegalArgumentException e){result=List.of();}
            List<Object> dependencies=new ArrayList<>(builder.files);dependencies.add(ModelDependencies.namespaces(host.getProject()));dependencies.add(MapperUsageIndex.tracker(host.getProject()));
            return CachedValueProvider.Result.create(result,dependencies.toArray());
        },false);
    }
    private static final class Builder {
        final XmlText focus;final Set<PsiFile> files=new LinkedHashSet<>();final Set<XmlTag> stack=new HashSet<>();int steps;XmlTag callingRoot;
        Builder(XmlText focus){this.focus=focus;files.add(focus.getContainingFile());}
        List<Place> build(){
            XmlTag owner=MapperModel.statement(focus);
            for(XmlTag parent=focus.getParentTag();parent!=null && parent!=owner;parent=parent.getParentTag())if(parent.getName().equals("selectKey")){owner=parent;break;}
            if(owner==null){owner=focus.getParentTag();while(owner!=null && !owner.getName().equals("sql"))owner=owner.getParentTag();}
            if(owner==null || MapperModel.isLegacy(MapperModel.root(owner)))return List.of();
            if(owner.getName().equals("sql")){
                XmlTag fragment=owner,root=MapperModel.root(owner);Set<XmlTag> roots=new LinkedHashSet<>(List.of(root));String namespace=root.getAttributeValue("namespace");if(namespace!=null)roots.addAll(MapperUsageIndex.roots(focus,"sql",namespace+"."+owner.getAttributeValue("id")));
                int[] budget={0};boolean found=false;
                for(XmlTag candidate:roots){for(XmlTag statement:candidate.getSubTags())if(MapperModel.isStatement(statement) && ParameterModel.uses(statement,fragment,new HashSet<>(),0,budget)){owner=statement;found=true;break;}if(found)break;}
            }
            callingRoot=MapperModel.root(owner);
            List<Chunk> chunks=new ArrayList<>();
            for(Chunk chunk:visit(owner,0)){
                int start=0;for(var token:dev.mybatis.assistant.core.SqlParameters.tokens(chunk.text())){
                    if(token.dollar())throw new IllegalArgumentException("动态标识符无法静态注入");
                    if(token.start()>start)chunks.add(slice(chunk,start,token.start()));chunks.add(new Chunk("?",-1,-1));start=token.end();
                }
                if(start<chunk.text().length())chunks.add(slice(chunk,start,chunk.text().length()));
            }
            // Empty shreds anchor excluded binding/tag-control characters at both host edges.
            StringBuilder focusValue=new StringBuilder();var focusHost=(PsiLanguageInjectionHost)focus;if(!focusHost.createLiteralTextEscaper().decode(ElementManipulators.getValueTextRange(focus),focusValue))return List.of();
            int firstFocus=-1,lastFocus=-1;for(int i=0;i<chunks.size();i++)if(chunks.get(i).start()>=0){if(firstFocus<0)firstFocus=i;lastFocus=i;}
            if(firstFocus<0)return List.of();
            chunks.add(lastFocus+1,new Chunk("",focusValue.length(),focusValue.length()));chunks.add(firstFocus,new Chunk("",0,0));
            StringBuilder all=new StringBuilder();for(Chunk chunk:chunks)all.append(chunk.text());
            if(all.indexOf("${")>=0)return List.of();
            String fragmentPrefix="";
            if(owner.getName().equals("sql")){
                String first=all.toString().stripLeading().toUpperCase(Locale.ROOT);
                fragmentPrefix=first.matches("(?s)^(AND|OR)\\b.*")?"SELECT * FROM __mybatis_fragment WHERE 1=1 ":first.matches("(?s)^(WHERE|JOIN|LEFT|RIGHT|INNER|ORDER|GROUP|HAVING|LIMIT)\\b.*")?"SELECT * FROM __mybatis_fragment ":first.startsWith("SELECT") || first.startsWith("WITH")?"":"SELECT ";
            }
            List<Place> places=new ArrayList<>();int offset=0,previous=0;int last=-1;for(int i=0;i<chunks.size();i++)if(chunks.get(i).start()>=0)last=i;
            int index=0;
            for(Chunk chunk:chunks){
                if(chunk.start()>=0){
                    var range=ElementManipulators.getValueTextRange(focus);var escaper=((PsiLanguageInjectionHost)focus).createLiteralTextEscaper();StringBuilder decoded=new StringBuilder();if(!escaper.decode(range,decoded))return List.of();
                    int a=escaper.getOffsetInHost(chunk.start(),range),b=escaper.getOffsetInHost(chunk.end(),range);if(a<0 || b<a)return List.of();
                    places.add(new Place(new TextRange(a,b),(places.isEmpty()?fragmentPrefix:"")+all.substring(previous,offset),index==last?all.substring(offset+chunk.text().length()):""));previous=offset+chunk.text().length();
                }
                offset+=chunk.text().length();index++;
            }
            return List.copyOf(places);
        }
        Chunk slice(Chunk chunk,int from,int to){return new Chunk(chunk.text().substring(from,to),chunk.start()<0?-1:chunk.start()+from,chunk.end()<0?-1:chunk.start()+to);}
        List<Chunk> visit(XmlTag tag,int depth){
            ProgressManager.checkCanceled();files.add(tag.getContainingFile());if(depth>32 || ++steps>2048 || !stack.add(tag))throw new IllegalArgumentException("动态 SQL 编辑展开超出上限或 include 循环");
            try{
                String name=tag.getName();if(name.equals("bind") || name.equals("selectKey") && !PsiTreeUtil.isAncestor(tag,focus,false))return List.of();
                if(name.equals("include")){
                    XmlTag root=callingRoot;String ref=tag.getAttributeValue("refid");if(ref==null || ref.contains("${"))throw new IllegalArgumentException("动态 refid 无法静态定位");
                    XmlTag target=MapperModel.definition(root,ref,"sql");if(target==null)throw new IllegalArgumentException("缺失 include");return visit(target,depth+1);
                }
                List<Chunk> chunks=new ArrayList<>();
                if(name.equals("choose")){
                    XmlTag chosen=null;for(XmlTag branch:tag.getSubTags())if(PsiTreeUtil.isAncestor(branch,focus,false)){chosen=branch;break;}
                    if(chosen==null)chosen=tag.findFirstSubTag("when");if(chosen==null)chosen=tag.findFirstSubTag("otherwise");if(chosen!=null)chunks.addAll(visit(chosen,depth+1));
                }else for(PsiElement child:tag.getChildren()){
                    if(child instanceof XmlText text){var host=(PsiLanguageInjectionHost)text;StringBuilder decoded=new StringBuilder();if(!host.createLiteralTextEscaper().decode(ElementManipulators.getValueTextRange(text),decoded))throw new IllegalArgumentException("XML 文本无法解码");String value=decoded.toString();chunks.add(new Chunk(" ",-1,-1));if(text==focus)chunks.add(new Chunk(value,0,value.length()));else chunks.add(new Chunk(value,-1,-1));chunks.add(new Chunk(" ",-1,-1));}
                    else if(child instanceof XmlTag nested)chunks.addAll(visit(nested,depth+1));
                }
                if(name.equals("where")){trim(chunks,"AND |OR |AND\n|OR\n|AND\r|OR\r|AND\t|OR\t",true);surround(chunks," WHERE ","");}
                if(name.equals("set")){trim(chunks,",",false);surround(chunks," SET ","");}
                if(name.equals("trim")){trim(chunks,tag.getAttributeValue("prefixOverrides"),true);trim(chunks,tag.getAttributeValue("suffixOverrides"),false);surround(chunks,tag.getAttributeValue("prefix"),tag.getAttributeValue("suffix"));}
                if(name.equals("foreach"))surround(chunks,tag.getAttributeValue("open"),tag.getAttributeValue("close"));
                int length=0;for(Chunk chunk:chunks){length+=chunk.text().length();if(length>262144)throw new IllegalArgumentException("SQL 超过上限");}
                return chunks;
            }finally{stack.remove(tag);}
        }
        void surround(List<Chunk> chunks,String prefix,String suffix){if(!joined(chunks).isBlank()){chunks.addFirst(new Chunk(" "+Objects.requireNonNullElse(prefix,"")+" ",-1,-1));chunks.add(new Chunk(" "+Objects.requireNonNullElse(suffix,"")+" ",-1,-1));}}
        String joined(List<Chunk> chunks){StringBuilder text=new StringBuilder();chunks.forEach(c->text.append(c.text()));return text.toString();}
        void trim(List<Chunk> chunks,String overrides,boolean front){
            String text=joined(chunks);int a=0,b=text.length();while(a<b && Character.isWhitespace(text.charAt(a)))a++;while(b>a && Character.isWhitespace(text.charAt(b-1)))b--;
            if(overrides!=null)for(String token:overrides.split("\\|")){
                if(token.isEmpty())continue;String candidate=text.substring(a,b).toUpperCase(Locale.ROOT),upper=token.toUpperCase(Locale.ROOT);
                if(front && candidate.startsWith(upper)){a+=token.length();break;}if(!front && candidate.endsWith(upper.strip())){b-=token.strip().length();break;}
            }
            List<Chunk> kept=new ArrayList<>();int offset=0;
            for(Chunk chunk:chunks){int from=Math.max(0,a-offset),to=Math.min(chunk.text().length(),b-offset);if(to>from)kept.add(new Chunk(chunk.text().substring(from,to),chunk.start()<0?-1:chunk.start()+from,chunk.end()<0?-1:chunk.start()+to));offset+=chunk.text().length();}
            chunks.clear();chunks.addAll(kept);
        }
    }
}
