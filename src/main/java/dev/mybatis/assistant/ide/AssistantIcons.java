package dev.mybatis.assistant.ide;

import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;

/** Original artwork shared by Mapper files, interfaces and navigation markers. */
public final class AssistantIcons {
    public static final Icon XML_MAPPER = IconLoader.getIcon("/icons/mapperXml.svg", AssistantIcons.class);
    public static final Icon JAVA_MAPPER = IconLoader.getIcon("/icons/mapperJava.svg", AssistantIcons.class);
    private AssistantIcons() {}
}
