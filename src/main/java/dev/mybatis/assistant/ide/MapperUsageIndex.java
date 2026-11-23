package dev.mybatis.assistant.ide;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.util.indexing.*;
import com.intellij.util.io.*;
import com.intellij.util.xml.*;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.*;
import com.intellij.openapi.progress.ProgressManager;
import java.io.StringReader;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Reverse references are collected only when an XML file changes, never by project scans. */
public final class MapperUsageIndex extends ScalarIndexExtension<String> {
    public static final ID<String,Void> NAME=ID.create("dev.mybatis.assistant.mapper.usage");
    @Override public @NotNull ID<String,Void> getName(){return NAME;}
    @Override public int getVersion(){return 1;}
    @Override public boolean dependsOnFileContent(){return true;}
    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor(){return EnumeratorStringDescriptor.INSTANCE;}
    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter(){return new DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE);}
    @Override public @NotNull DataIndexer<String,Void,FileContent> getIndexer(){return content->extract(content.getContentAsText());}
    static Map<String,Void> extract(CharSequence text){
        Map<String,Void> keys=new HashMap<>();if(text.length()>4194304)return keys;
        NanoXmlUtil.parse(new StringReader(text.toString()),new NanoXmlBuilder(){
            int depth,steps;String namespace;
            @Override public void startElement(String name,String prefix,String uri,String systemId,int line)throws Exception{depth++;if(++steps>20000 || depth==1 && !name.equals("mapper"))NanoXmlBuilder.stop();}
            @Override public void addAttribute(String name,String prefix,String uri,String value,String type){
                if(depth==1 && name.equals("namespace"))namespace=value;
                if(depth>1 && namespace!=null && Set.of("refid","resultMap","extends").contains(name))for(String ref:value.split("[,\\s]+"))if(!ref.isBlank() && !ref.contains("${"))keys.put((name.equals("refid")?"sql:":"map:")+(ref.contains(".")?ref:namespace+"."+ref),null);
            }
            @Override public void endElement(String name,String prefix,String uri){depth--;}
        });return keys;
    }
    static List<XmlTag> roots(PsiElement context,String kind,String qualifiedId){
        List<XmlTag> roots=new ArrayList<>();
        for(var file:FileBasedIndex.getInstance().getContainingFiles(NAME,kind+":"+qualifiedId,GlobalSearchScope.projectScope(context.getProject()))){
            ProgressManager.checkCanceled();if(roots.size()>=128)throw new IllegalArgumentException("映射引用超过 128 个文件，请缩小映射范围");
            PsiFile psi=PsiManager.getInstance(context.getProject()).findFile(file);if(psi instanceof XmlFile xml && xml.getRootTag()!=null)roots.add(xml.getRootTag());
        }return roots;
    }
    static com.intellij.openapi.util.ModificationTracker tracker(com.intellij.openapi.project.Project project){return ()->FileBasedIndex.getInstance().getIndexModificationStamp(NAME,project);}
}
