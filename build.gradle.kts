import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Declared here, and never applied here. A plugin named in the root build lands in the
    // root-project classloader scope, which is the parent of every subproject scope, so all five
    // modules share one copy of ModDevGradle. Left to themselves they would not: `:example`
    // carries an extra plugin from the included build, so Gradle gives it a scope of its own and
    // loads ModDevGradle into it a second time. MDG applies `gradle-idea-ext` to the *root* project,
    // and Gradle's already-applied check is per Class object, so the second copy does not recognise
    // the first one's work and collides on the `settings` extension -- an IDE import that fails
    // naming neither plugin.
    alias(libs.plugins.moddev) apply false
}

// `subprojects { }` runs before the subprojects are configured, so their own catalog extension
// does not exist yet. Resolve the accessors here, against the root, and hand the providers down.
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.platform.launcher

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = rootProject.group
    version = rootProject.version

    // 21 rather than the installed 25: the runtime side is eventually loaded by Minecraft, and
    // class files built for 21 load fine on the JDK 25 that Minecraft 26.2 runs on.
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    dependencies {
        "testImplementation"(junitJupiter)
        "testRuntimeOnly"(junitLauncher)
    }
}
