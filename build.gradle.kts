import net.minecraftforge.renamer.gradle.RenameJar

plugins {
    id("java")
    id("net.minecraftforge.gradle") version "7.0.29"
    id("net.minecraftforge.renamer") version "1.1.2"
}

group = "ru.an0n.authprobe"
version = "1.5.4+1.20.1-forge"
description = "AuthProbe - client-side login probe for offline-mode Minecraft servers."

val mcv = "1.20.1"
val forgeVersion = "1.20.1-47.4.20"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

minecraft {
    mappings("official", mcv)
}

repositories {
    mavenCentral()
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    minecraft.mavenizer(this)
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.1.0")

    // Mixin refmap generation. Required for every mixin below 1.20.6.
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")

    implementation(minecraft.dependency("net.minecraftforge:forge:${forgeVersion}"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 17
}

tasks.withType<Jar> {
    manifest {
        attributes("MixinConfigs" to "authprobe.mixins.json")
    }
}

// 1.20.1 Forge runs SRG-named members, so the mojmap-compiled output must be renamed.
renamer {
    mappings(minecraft.dependency.toSrg)

    enableMixinRefmaps {
        config("authprobe.mixins.json")
        source(sourceSets["main"]) {
            refMap = "authprobe.mixins.refmap.json"
        }
        jar(tasks.named<Jar>("jar"))
    }

    classes(tasks.named<Jar>("jar")) {
        mappings(renamer.mixin.generatedMappings)
        archiveClassifier = "srg"
    }
}

tasks.withType<RenameJar> {
    output = layout.buildDirectory.get().asFile.resolve("libs/AuthProbe-${version}.jar")
}