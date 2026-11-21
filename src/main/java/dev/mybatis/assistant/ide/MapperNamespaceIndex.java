package dev.mybatis.assistant.ide;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.util.indexing.*;
import com.intellij.util.io.*;
import com.intellij.util.xml.*;
import java.io.StringReader;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Disk-backed namespace and legacy statement lookup; only changed XML files are indexed. */
public final class MapperNamespaceIndex extends ScalarIndexExtension<String> {
    public static final ID<String,Void> NAME=ID.create("dev.mybatis.assistant.namespace");
    @Override public @NotNull ID<String,Void> getName(){return NAME;}
    @Override public int getVersion(){return 2;}
    @Override public boolean dependsOnFileContent(){return true;}
    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor(){return EnumeratorStringDescriptor.INSTANCE;}
    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter(){return new DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE);}
    @Override public @NotNull DataIndexer<String,Void,FileContent> getIndexer(){return content -> {
        Map<String,Void> keys=dev.mybatis.assistant.core.NamespaceScanner.extract(content.getContentAsText());
        if(keys.containsKey(dev.mybatis.assistant.core.NamespaceScanner.LEGACY_ROOT)){
            String filename=content.getFileName();int dot=filename.lastIndexOf('.');
            keys.put(dev.mybatis.assistant.core.NamespaceScanner.LEGACY_FILE+(dot<0?filename:filename.substring(0,dot)),null);
        }
        return keys;
    };}
    public static Map<String,Void> extract(CharSequence text) {
        return dev.mybatis.assistant.core.NamespaceScanner.extract(text);
    }
}
