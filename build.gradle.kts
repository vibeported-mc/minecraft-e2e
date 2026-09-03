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

    // A group of its own for the framework, which is load-bearing rather than tidy. Gradle
    // substitutes a project dependency for whichever project publishes the same `group:name`, and
    // picks one when two do -- `project(":rpc:host")` silently resolving to a different project
    // surfaces as a circular dependency naming neither the cause nor the two projects involved.
    // Two names still have to differ within a group; this stops `:core` and `:rpc:core`, and
    // `:example` and `:rpc:example`, from being the same trap as soon as anything depends on them.
    group = if (path == ":rpc" || path.startsWith(":rpc:")) "dev.vibeported.rpc" else rootProject.group
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

// The standing proof that the RPC framework is free of the game.
//
// Stated as a task rather than left to eye and discipline, because the failure mode is silent: one
// `import net.minecraft....` reached for out of convenience, and a framework meant to run anywhere
// quietly needs Minecraft on the classpath to compile. Checking the resolved classpaths catches it
// whether it arrives as a direct dependency or through somebody else's.
//
// Registered in each module rather than in the root, so nothing resolves another project's
// configurations: doing that from here is what Gradle means by resolution without an exclusive
// lock, and it is refused outright under the configuration cache.
subprojects {
    if (!path.startsWith(":rpc:")) return@subprojects

    val classpaths = configurations
        .matching { it.isCanBeResolved && it.name.endsWith("CompileClasspath") }
        .map { configuration ->
            // The name, lifted out before the lambda. Capturing `configuration` itself would put a
            // Configuration inside a task action, which the configuration cache cannot write -- and
            // it says so naming the type rather than the line, so it is worth not doing.
            val where = configuration.name
            configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                artifacts
                    .map { it.id.componentIdentifier.displayName }
                    .filter { "net.minecraft" in it || "net.neoforged" in it }
                    .map { "$where -> $it" }
            }
        }

    // Folded into a single provider, because a task action may hold providers but not a collection
    // of them: the configuration cache serializes what an action captured, and a map of providers
    // is not something it can write.
    val found = classpaths.fold(provider { emptyList<String>() }) { all, next ->
        all.zip(next) { a, b -> a + b }
    }

    // Read out here: inside the task block `path` is the task's own, which would name
    // `:rpc:testkit:checkNoGame` where the reader wants the module.
    val module = path

    val check = tasks.register("checkNoGame") {
        group = "verification"
        description = "Fails if this module can resolve a Minecraft or NeoForge artifact."
        doLast {
            val offending = found.get()
            require(offending.isEmpty()) {
                offending.joinToString(
                    prefix = "$module must not be able to resolve the game, and does: ",
                    separator = "; ",
                )
            }
        }
    }

    tasks.named("check") { dependsOn(check) }
}
