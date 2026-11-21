plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "dev.mybatis.assistant"
version = "0.5.0"
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

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
    pluginConfiguration { ideaVersion { sinceBuild = "261"; untilBuild = "261.*" } }
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
tasks.processResources {
    dependsOn(buildNative)
    from("native/target/release/$libraryName") { into("native/$platform-$architecture") }
    from(files("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses, buildNative)
    classpath = sourceSets.test.get().runtimeClasspath
    // Core namespace tests use NanoXmlUtil and its SDK runtime dependencies.
    classpath += files(providers.provider {
        fileTree(intellijPlatform.platformPath.resolve("lib").toFile()) { include("*.jar") }
    })
    mainClass = "dev.mybatis.assistant.core.CoreTests"
    jvmArgs("-ea", "-Dmybatis.assistant.native=${file("native/target/release/$libraryName").absolutePath}")
}
