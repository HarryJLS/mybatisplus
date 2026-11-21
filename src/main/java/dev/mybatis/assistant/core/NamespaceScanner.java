package dev.mybatis.assistant.core;

import com.intellij.util.xml.*;
import java.io.StringReader;
import java.util.*;

/** MyBatis stops at the root; iBATIS additionally streams statement IDs during file indexing. */
public final class NamespaceScanner {
    private NamespaceScanner(){}
    public static final String LEGACY_ROOT="@ibatis.root";
    public static final String LEGACY_NAMESPACE="@ibatis.namespace:";
    public static final String LEGACY_FILE="@ibatis.file:";
    public static final String STATEMENT="@ibatis.statement:";
    private static final Set<String> SQL_TAGS=Set.of("select","insert","update","delete","statement","procedure");
    public static Map<String,Void> extract(CharSequence text){
        Map<String,Void> result=new HashMap<>(1);
        NanoXmlUtil.parse(new StringReader(text.toString()),new NanoXmlBuilder(){
            private boolean mapper,legacy;
            private int depth;
            private String namespace,tag;
            @Override public void startElement(String name,String prefix,String uri,String systemId,int line)throws Exception{
                depth++;tag=name;
                if(depth==1){mapper="mapper".equals(name);legacy="sqlMap".equals(name);if(!mapper && !legacy)NanoXmlBuilder.stop();if(legacy)result.put(LEGACY_ROOT,null);}
            }
            @Override public void addAttribute(String name,String prefix,String uri,String value,String type){
                if(depth==1 && "namespace".equals(name) && !value.isBlank()){
                    namespace=value;result.put(value,null);if(legacy)result.put(LEGACY_NAMESPACE+value,null);
                }
                if(legacy && depth==2 && SQL_TAGS.contains(tag) && "id".equals(name) && !value.isBlank()){
                    result.put(STATEMENT+value,null);
                    if(namespace!=null){
                        String local=value.startsWith(namespace+".")?value.substring(namespace.length()+1):value;
                        result.put(STATEMENT+local,null);result.put(STATEMENT+namespace+"."+local,null);
                    }
                }
            }
            @Override public void elementAttributesProcessed(String name,String prefix,String uri)throws Exception{if(mapper && depth==1)NanoXmlBuilder.stop();}
            @Override public void endElement(String name,String prefix,String uri){depth--;}
        });return result;
    }
}
