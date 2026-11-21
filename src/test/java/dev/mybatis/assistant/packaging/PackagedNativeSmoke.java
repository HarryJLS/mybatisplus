package dev.mybatis.assistant.packaging;

import dev.mybatis.assistant.core.NativeBridge;

/** Exercises resource selection and JNI from the final installed plugin libraries. */
public final class PackagedNativeSmoke {
    public static void main(String[] args) {
        if (System.getProperty("mybatis.assistant.native") != null)
            throw new AssertionError("Packaged test must load the bundled library");
        if (NativeBridge.isLoaded()) throw new AssertionError("Native loading must remain lazy");
        String sql = NativeBridge.logsToSql("Preparing: select ?, ?\nParameters: 7(Long), O'Reilly(String)");
        if (!sql.equals("select 7, 'O''Reilly';")) throw new AssertionError(sql);
        if (!NativeBridge.isLoaded()) throw new AssertionError("Bundled native engine was not loaded");
        var table = NativeBridge.parseDdl("CREATE TABLE users (id BIGINT PRIMARY KEY, user_name VARCHAR(100))");
        if (!table.name().equals("users") || table.columns().size() != 2 || !table.columns().getFirst().primaryKey())
            throw new AssertionError("Bundled DDL parser returned unexpected metadata");
        System.out.println("PASS: packaged JNI lazy loading, SQL logs, DDL metadata");
    }
}
