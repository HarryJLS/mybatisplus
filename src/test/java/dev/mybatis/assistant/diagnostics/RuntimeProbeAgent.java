package dev.mybatis.assistant.diagnostics;

import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Attach-only, test-only backend probe. It never instruments classes or performs UI actions. */
public final class RuntimeProbeAgent {
    private static Class<?> find(Instrumentation instrumentation,String name){
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals(name))return type;
        throw new IllegalStateException("Not loaded: "+name);
    }
    public static void agentmain(String encoded,Instrumentation instrumentation)throws Exception{
        String[] paths=new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8).split("\n");
        Class<?> ids=find(instrumentation,"com.intellij.openapi.extensions.PluginId");
        Class<?> plugins=find(instrumentation,"com.intellij.ide.plugins.PluginManagerCore");
        Object id=ids.getMethod("getId",String.class).invoke(null,"dev.mybatis.assistant");
        Object plugin=plugins.getMethod("getPlugin",ids).invoke(null,id);
        Class<?> descriptor=find(instrumentation,"com.intellij.ide.plugins.IdeaPluginDescriptor");
        ClassLoader parent=(ClassLoader)descriptor.getMethod("getPluginClassLoader").invoke(plugin);
        Class<?> manager=find(instrumentation,"com.intellij.openapi.project.ProjectManager");
        Object instance=manager.getMethod("getInstance").invoke(null);
        Object[] projects=(Object[])manager.getMethod("getOpenProjects").invoke(instance);
        Class<?> projectType=find(instrumentation,"com.intellij.openapi.project.Project");
        Object selected=null;
        for(Object project:projects)if(paths[2].equals(projectType.getMethod("getBasePath").invoke(project)))selected=project;
        if(selected==null)throw new IllegalStateException("Requested project is not open");
        try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of(paths[1]).toUri().toURL()},parent)){
            Class<?> runner=loader.loadClass("dev.mybatis.assistant.diagnostics.RuntimeProbeRunner");
            runner.getMethod("run",Object.class,String.class).invoke(null,selected,paths[0]);
        }
    }
    public static void main(String[] arguments)throws Exception{
        if(arguments.length!=4)throw new IllegalArgumentException("PID configPath runnerJar projectPath required");
        String agent=Path.of(RuntimeProbeAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String options=Base64.getEncoder().encodeToString(String.join("\n",Arrays.copyOfRange(arguments,1,arguments.length)).getBytes(StandardCharsets.UTF_8));
        VirtualMachine vm=VirtualMachine.attach(arguments[0]);
        try{vm.loadAgent(agent,options);}finally{vm.detach();}
        System.out.println("PASS: live IDEA backend probe completed");
    }
}
