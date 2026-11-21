package dev.mybatis.assistant.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.ProjectManager;
import dev.mybatis.assistant.core.McpProtocol;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.jetbrains.ide.HttpRequestHandler;

public final class McpHttpHandler extends HttpRequestHandler {
    public static final String PATH="/mybatis-assistant/mcp";
    private final String endpoint;
    private final java.util.function.Supplier<com.intellij.openapi.project.Project[]> openedProjects;
    public McpHttpHandler(){this(PATH,()->ProjectManager.getInstance().getOpenProjects());}
    McpHttpHandler(String endpoint,java.util.function.Supplier<com.intellij.openapi.project.Project[]> openedProjects){this.endpoint=endpoint;this.openedProjects=openedProjects;}
    public static boolean localHeaders(HttpRequest request){
        String host=request.headers().get(HttpHeaderNames.HOST,"").toLowerCase(java.util.Locale.ROOT);
        return request.headers().get(HttpHeaderNames.ORIGIN)==null && (host.matches("(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?") || host.matches("\\[::1](?::[0-9]+)?"));
    }
    @Override public boolean isSupported(FullHttpRequest request){return new QueryStringDecoder(request.uri()).path().equals(endpoint);}
    @Override public boolean isAccessible(HttpRequest request){return localHeaders(request);}
    @Override public boolean process(QueryStringDecoder url,FullHttpRequest request,ChannelHandlerContext context){
        if(!url.path().equals(endpoint))return false;
        if(!localHeaders(request) || !(context.channel().remoteAddress() instanceof InetSocketAddress peer) || !peer.getAddress().isLoopbackAddress()){send(context,403,"");return true;}
        String authorization=request.headers().get(HttpHeaderNames.AUTHORIZATION,"");McpProjectService service=null;
        if(authorization.startsWith("Bearer ") && authorization.length()<256)for(var project:openedProjects.get())if(!project.isDisposed()){
            McpProjectService candidate=project.getService(McpProjectService.class);if(candidate.accepts(authorization.substring(7))){service=candidate;break;}
        }
        if(service==null){send(context,401,"");return true;}
        if(!request.method().equals(HttpMethod.POST)){send(context,405,"");return true;}
        String version=request.headers().get("MCP-Protocol-Version","2025-03-26");if(!McpProtocol.VERSIONS.contains(version)){send(context,400,"");return true;}
        String accept=request.headers().get(HttpHeaderNames.ACCEPT,"");if(!accept.contains("application/json") || !accept.contains("text/event-stream")){send(context,406,"");return true;}
        if(!request.headers().get(HttpHeaderNames.CONTENT_TYPE,"").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")){send(context,415,"");return true;}
        if(request.content().readableBytes()>1048576){send(context,413,"");return true;}
        String body;try{body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(request.content().nioBuffer()).toString();}catch(java.nio.charset.CharacterCodingException e){send(context,400,"");return true;}
        McpProjectService active=service;if(!active.acquire()){send(context,429,"");return true;}
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            try{McpProtocol.Reply reply=McpProtocol.handle(body,active);context.executor().execute(()->send(context,reply.status(),reply.body()==null?"":reply.body().toString()));}
            finally{active.release();}
        });return true;
    }
    private static void send(ChannelHandlerContext context,int status,String body){
        byte[] data=body.getBytes(StandardCharsets.UTF_8);FullHttpResponse response=new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.valueOf(status),Unpooled.wrappedBuffer(data));
        if(!body.isEmpty())response.headers().set(HttpHeaderNames.CONTENT_TYPE,"application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH,data.length);response.headers().set(HttpHeaderNames.CACHE_CONTROL,"no-store");
        if(status==405)response.headers().set(HttpHeaderNames.ALLOW,"POST");if(status==401)response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE,"Bearer");
        response.headers().set(HttpHeaderNames.CONNECTION,"close");context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
