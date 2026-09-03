import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    `java-gradle-plugin`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.JVM_21
}

dependencies {
    // The only dependency there is, and deliberately so. This plugin lives in an included build, so
    // whatever is on its runtime classpath is exported onto the consuming script's plugin classpath
    // -- a second copy of everything it names. Its neighbour under `gradle-plugin/` learned that the
    // expensive way with ModDevGradle: two copies, and Gradle's already-applied check being per
    // Class object, produced a collision on the `settings` extension that named neither plugin and
    // only bit during an IDE import. Nothing here has any business knowing about Minecraft anyway.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
}

gradlePlugin {
    plugins {
        create("rpc") {
            id = "dev.vibeported.rpc"
            implementationClass = "dev.vibeported.rpc.gradle.RpcGradlePlugin"
            displayName = "RPC"
            description = "Lifts procedure bodies into dispatch tables, and packages the manifest that finds them."
        }
    }
}
