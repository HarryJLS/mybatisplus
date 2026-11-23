plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.mybatis.assistant"
version = "0.5.8"
// 263 SDK classes use Java 25; keep our own bytecode target at Java 21.
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
dependencies {
    implementation("org.mybatis:mybatis:3.5.19")
    implementation("com.google.code.gson:gson:2.13.2")
    intellijPlatform {
        val sdkVersion = providers.gradleProperty("ideaVersion")
        if (sdkVersion.isPresent) {
            intellijIdea(sdkVersion)
        } else {
            local(providers.gradleProperty("ideaHome").orElse("/Applications/IntelliJ IDEA.app/Contents"))
        }
        bundledPlugin("com.intellij.java")
        // XML became a separate bundled plugin in 263; older SDKs expose it in the platform.
        val xmlIsSeparate = if (sdkVersion.isPresent) {
            val parts = sdkVersion.get().split(".")
            parts[0].toInt() > 2026 || parts[0] == "2026" && parts[1].toInt() >= 3
        } else {
            val home = file(providers.gradleProperty("ideaHome").orElse("/Applications/IntelliJ IDEA.app/Contents").get())
            val info = listOf(home.resolve("Resources/product-info.json"), home.resolve("product-info.json")).firstOrNull { it.exists() }
            info != null && Regex("\"buildNumber\"\\s*:\\s*\"(?:[A-Z]+-)?(\\d+)").find(info.readText())?.groupValues?.get(1)?.toInt()?.let { it >= 263 } == true
        }
        if (xmlIsSeparate) bundledPlugin("com.intellij.xml")
        bundledPlugin("com.intellij.database")
        bundledPlugin("com.intellij.spring")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.ibatis:ibatis-sqlmap:2.3.4.726")
}
intellijPlatform {
    pluginConfiguration { ideaVersion { sinceBuild = "261"; untilBuild = "263.*" } }
    buildSearchableOptions = false
}
val buildNative by tasks.registering(Exec::class) {
    workingDir("native")
    commandLine("cargo", "build", "--release", "--locked")
    inputs.files(fileTree("native/src"), "native/Cargo.toml", "native/Cargo.lock")
    outputs.dir("native/target/release")
}
val osName = System.getProperty("os.name").lowercase()
val platform = if (osName.contains("mac")) "macos" else if (osName.contains("win")) "windows" else "linux"
val architecture = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "aarch64" else "x86_64"
val libraryName = if (platform == "macos") "libmybatis_engine.dylib" else if (platform == "windows") "mybatis_engine.dll" else "libmybatis_engine.so"
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask>("buildPlugin") {
    archiveFileName.set("mybatis-assistant-${project.version}-$platform-$architecture.zip")
}
tasks.processResources {
    dependsOn(buildNative)
    from("native/target/release/$libraryName") { into("native/$platform-$architecture") }
    from(files("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21) }
// The 263 distribution splits platform/API classes into many module JARs.
// Compile-only SDK inputs stay out of the installation ZIP.
val sdkCompileJars = providers.provider {
    val sdk = intellijPlatform.platformPath
    listOf("lib", "plugins/java/lib", "plugins/xml-plugin/lib", "plugins/DatabaseTools/lib", "plugins/Spring/lib", "plugins/Kotlin/lib")
        .flatMap { folder -> fileTree(sdk.resolve(folder).toFile()) { include("**/*.jar") }.files }
}
dependencies { compileOnly(files(sdkCompileJars)) }
tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses, buildNative)
    classpath = sourceSets.test.get().runtimeClasspath
    // Core namespace tests use NanoXmlUtil and its SDK runtime dependencies.
    classpath += files(providers.provider {
        fileTree(intellijPlatform.platformPath.resolve("lib").toFile()) { include("*.jar") }
    })
    mainClass = "dev.mybatis.assistant.core.CoreTests"
    javaLauncher.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) })
    jvmArgs("-ea", "--enable-native-access=ALL-UNNAMED", "-Dmybatis.assistant.native=${file("native/target/release/$libraryName").absolutePath}")
}
