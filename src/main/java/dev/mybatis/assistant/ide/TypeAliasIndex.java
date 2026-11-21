package dev.mybatis.assistant.ide;

import com.intellij.util.indexing.*;
import com.intellij.util.io.*;
import dev.mybatis.assistant.core.TypeAliasConfig;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Persistent config keys; the IDE indexes changed files, never a completion-time file walk. */
public final class TypeAliasIndex extends FileBasedIndexExtension<String,String> {
    public static final ID<String,String> NAME = ID.create("dev.mybatis.assistant.type.aliases");
    public static final String PACKAGES = "#packages";
    public static final String CONFIG = "#config";
    @Override public @NotNull ID<String,String> getName() { return NAME; }
    @Override public int getVersion() { return 1; }
    @Override public boolean dependsOnFileContent() { return true; }
    @Override public @NotNull KeyDescriptor<String> getKeyDescriptor() { return EnumeratorStringDescriptor.INSTANCE; }
    @Override public @NotNull DataExternalizer<String> getValueExternalizer() { return EnumeratorStringDescriptor.INSTANCE; }
    @Override public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return file -> file.getExtension()!=null && Set.of("xml", "yml", "yaml", "properties").contains(file.getExtension());
    }
    @Override public @NotNull DataIndexer<String,String,FileContent> getIndexer() {
        return content -> {
            TypeAliasConfig.Config config = TypeAliasConfig.parse(content.getContentAsText().toString(), content.getFileName());
            Map<String,String> result = new HashMap<>(config.aliases());
            if (!config.packages().isEmpty()) result.put(PACKAGES, String.join(";", config.packages()));
            if (!result.isEmpty()) result.put(CONFIG, "1");
            return result;
        };
    }
}
