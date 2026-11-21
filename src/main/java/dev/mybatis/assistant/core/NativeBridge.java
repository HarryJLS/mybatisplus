package dev.mybatis.assistant.core;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/** Loads the small Rust library only when a user invokes a native tool. */
public final class NativeBridge {
    private NativeBridge() {}
    private static volatile boolean loaded;
    public static boolean isLoaded(){return loaded;}
    private static final class Loader {
        static final boolean LOADED = load();
        private static boolean load() {
            String explicit = System.getProperty("mybatis.assistant.native");
            if (explicit != null) { System.load(explicit); loaded=true;return true; }
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch");
            arch = arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x86_64";
            String platform = os.contains("mac") ? "macos" : os.contains("win") ? "windows" : "linux";
            String filename = platform.equals("macos") ? "libmybatis_engine.dylib"
                    : platform.equals("windows") ? "mybatis_engine.dll" : "libmybatis_engine.so";
            String resource = "/native/" + platform + "-" + arch + "/" + filename;
            try (InputStream in = NativeBridge.class.getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("安装包缺少 " + platform + "-" + arch + " 原生库，请在目标平台构建插件。");
                Path directory = Files.createTempDirectory("mybatis-assistant-native-");
                directory.toFile().deleteOnExit();
                Path library = directory.resolve(filename);
                Files.copy(in, library);
                library.toFile().deleteOnExit();
                System.load(library.toAbsolutePath().toString());
                loaded=true;
                return true;
            } catch (IOException | UnsatisfiedLinkError e) {
                throw new IllegalStateException("无法加载 Rust 引擎: " + e.getMessage(), e);
            }
        }
    }
    private static native String request(String json);
    public static JsonElement call(String operation, String text) {
        if (text.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("输入过大（最多 4M 字符）");
        if (!Loader.LOADED) throw new IllegalStateException("原生引擎未加载");
        JsonObject req = new JsonObject(); req.addProperty("operation", operation); req.addProperty("text", text);
        String response = request(req.toString());
        if (response == null) throw new IllegalStateException("Rust 引擎未返回结果");
        JsonObject result = JsonParser.parseString(response).getAsJsonObject();
        if (!result.get("ok").getAsBoolean()) throw new IllegalArgumentException(result.get("error").getAsString());
        return result.get("data");
    }
    public static Schema.Table parseDdl(String text) { return new Gson().fromJson(call("ddl", text), Schema.Table.class); }
    public static String logsToSql(String text) { return call("logs", text).getAsString(); }
}
