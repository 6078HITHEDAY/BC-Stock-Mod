plugins {
    id("fabric-loom") version "1.17.21"
    // 代码格式化：本地 `./gradlew spotlessApply`，`./gradlew check`（含 build）会跑 spotlessCheck。
    // 格式化只在私有仓做，Copybara 不做格式化（copy.bara.sky 里没有执行外部命令的 transform）。
    // 本文件随公开子集导出，公开仓因此用同一份格式规则。见 docs/copybara.md §代码格式化。
    id("com.diffplug.spotless") version "8.10.4"
}

val archives_base_name: String by project
val minecraft_version: String by project
val yarn_mappings: String by project
val loader_version: String by project
val fabric_version: String by project
val mod_version: String by project
val maven_group: String by project

base.archivesName = archives_base_name
group = maven_group
version = mod_version

java {
    toolchain.languageVersion = JavaLanguageVersion.of(22)
}

loom {
    mods {
        create("bcstock") {
            sourceSet("main")
        }
    }
}

repositories {
    mavenCentral()
    maven("https://maven.shedaniel.me/")
    maven("https://maven.terraformersmc.com/releases/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")
    mappings("net.fabricmc:yarn:$yarn_mappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loader_version")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabric_version")

    // Cloth Config / ModMenu：可选依赖（suggests）。编译期需要 API，不打进 jar。
    modCompileOnly("me.shedaniel.cloth:cloth-config-fabric:21.11.153")
    modCompileOnly("com.terraformersmc:modmenu:17.0.1")

    // 嵌入式账本：纯 Java，打进 mod jar。不用 SQLite（要各平台原生库）。
    implementation("com.h2database:h2:2.3.232")
    include("com.h2database:h2:2.3.232")

    // 探测逻辑的离线回归测试（拿 data/ 里的实测快照当黄金样本）
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 22
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    java {
        target("src/**/*.java")
        // Google 风格：2 空格缩进、100 列。只覆盖 src/ 下的 Java；
        // docs/、data/、copy.bara.sky 不参与，避免把文档与同步配置的 diff 搅进来。
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    // 版本锁死：规则升级要单独一个提交，别让某次构建悄悄改格式。
    isEnforceCheck = true
}
