plugins {
    id("fabric-loom") version "1.17.21"
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
