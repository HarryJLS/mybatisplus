package dev.mybatis.assistant.diagnostics;

import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Uses IDEA's normal restart-time installation queue; does not replace a live plugin JAR. */
public final class RuntimeInstallAgent {
    public static void agentmain(String encoded,Instrumentation instrumentation)throws Exception{
        String[] paths=new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8).split("\n");
        ClassLoader core=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("com.intellij.openapi.project.ProjectManager"))core=type.getClassLoader();
        if(core==null)throw new IllegalStateException("IDEA platform not found");
        String name="com.intellij.ide.startup.StartupActionScriptManager";
        Class<?> manager=Class.forName(name,true,core);
        Class<?> delete=Class.forName(name+"$DeleteCommand",true,core);
        Class<?> unzip=Class.forName(name+"$UnzipCommand",true,core);
        Path plugins=Path.of(paths[1]);List<Object> commands=new ArrayList<>();
        commands.add(delete.getConstructor(Path.class).newInstance(plugins.resolve("mybatis-assistant")));
        commands.add(unzip.getConstructor(Path.class,Path.class).newInstance(Path.of(paths[0]),plugins));
        if(Files.exists(plugins.resolve("mybatis-runtime-probe")))commands.add(delete.getConstructor(Path.class).newInstance(plugins.resolve("mybatis-runtime-probe")));
        manager.getMethod("addActionCommands",List.class).invoke(null,commands);
        Files.writeString(Path.of(paths[2]),"Queued for next IDEA restart:\n"+commands);
    }
    public static void main(String[] arguments)throws Exception{
        if(arguments.length!=4)throw new IllegalArgumentException("PID stagedZip pluginDirectory reportPath required");
        String agent=Path.of(RuntimeInstallAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String options=Base64.getEncoder().encodeToString(String.join("\n",Arrays.copyOfRange(arguments,1,4)).getBytes(StandardCharsets.UTF_8));
        VirtualMachine vm=VirtualMachine.attach(arguments[0]);
        try{vm.loadAgent(agent,options);}finally{vm.detach();}
        System.out.println("PASS: plugin update queued through IDEA's startup installer");
    }
}
