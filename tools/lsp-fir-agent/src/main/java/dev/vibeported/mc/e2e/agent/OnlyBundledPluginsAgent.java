package dev.vibeported.mc.e2e.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ConstantDescs;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

/**
 * Makes the JetBrains language server load third-party Kotlin K2 compiler plugins.
 *
 * <p>The server hardcodes {@code onlyBundledPluginsEnabled = true} when it builds the compiler
 * plugin cache, so {@code KtCompilerPluginsCache$Companion.substitutePluginJar} drops every plugin
 * jar that has no bundled counterpart. IntelliJ IDEA reads the same flag from the registry key
 * {@code kotlin.k2.only.bundled.compiler.plugins.enabled} and can therefore be told to keep them;
 * the language server passes a literal {@code true}. See IDEA-393372.
 *
 * <p>Rather than patch one known instruction at one known offset, this rewrites <em>every</em> call
 * to the factory, whatever class makes it and however the argument is produced: the boolean on the
 * stack is popped and replaced with {@code false} immediately before the call. A rebuilt server that
 * moves the call, computes the flag, or reads it from somewhere is still handled.
 *
 * <p>System properties, all optional:
 * <ul>
 *   <li>{@code e2e.agent.disabled=true} -- install nothing, so the flag can be turned off without
 *       editing the launch arguments.
 *   <li>{@code e2e.agent.quiet=true} -- patch silently. By default every rewrite is announced on
 *       stderr, which is where the server's log picks it up.
 *   <li>{@code e2e.agent.owner=<internal/name>} and {@code e2e.agent.method=<name>} -- target
 *       something else entirely.
 * </ul>
 */
public final class OnlyBundledPluginsAgent {

    private static final String OWNER = System.getProperty(
        "e2e.agent.owner",
        "org/jetbrains/kotlin/idea/fir/extensions/KtCompilerPluginsCache$Companion"
    );

    private static final String METHOD = System.getProperty("e2e.agent.method", "new");

    private static final boolean QUIET = Boolean.getBoolean("e2e.agent.quiet");

    private OnlyBundledPluginsAgent() {
    }

    /**
     * Dry run: rewrites a class file on disk instead of a loaded class, so the transform can be
     * checked against a real server jar without starting an IDE.
     *
     * <pre>java -jar lsp-fir-agent.jar In.class Out.class</pre>
     */
    public static void main(String[] args) throws Exception {
        byte[] input = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        byte[] output = new Rewriter().transform(null, args[0], null, null, input);
        if (output == null) {
            System.err.println("[e2e-fir-agent] no call to " + OWNER + "." + METHOD + " found");
            System.exit(1);
        }
        java.nio.file.Files.write(java.nio.file.Path.of(args[1]), output);
    }

    /** Entry point for {@code -javaagent:}, run before the server's main class. */
    public static void premain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    /** Entry point for attaching to an already running server. */
    public static void agentmain(String args, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static void install(Instrumentation instrumentation) {
        if (Boolean.getBoolean("e2e.agent.disabled")) {
            log("disabled by e2e.agent.disabled, not installing");
            return;
        }
        instrumentation.addTransformer(new Rewriter(), true);
        log("installed; will force " + OWNER + "." + METHOD + " to onlyBundledPluginsEnabled=false");
    }

    private static void log(String message) {
        if (!QUIET) {
            System.err.println("[e2e-fir-agent] " + message);
        }
    }

    private static final class Rewriter implements ClassFileTransformer {

        @Override
        public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> beingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
        ) {
            // Thousands of classes go past here during startup, and parsing each one would be paid
            // on every launch. A class that calls the factory has the owner's name in its constant
            // pool as plain UTF-8, so this rejects almost everything without a parse.
            if (!mentionsTarget(classfileBuffer)) {
                return null;
            }

            try {
                return rewrite(className, classfileBuffer);
            } catch (Throwable failure) {
                // Never take the IDE down over this. Returning null leaves the class as it was,
                // which is exactly the behaviour without the agent.
                log("failed to rewrite " + className + ": " + failure);
                return null;
            }
        }

        private static boolean mentionsTarget(byte[] bytes) {
            // ISO-8859-1 keeps one byte per char, so the search is over the raw bytes; the owner
            // name is ASCII and cannot be split across the constant pool's modified UTF-8.
            return new String(bytes, StandardCharsets.ISO_8859_1).contains(OWNER);
        }

        private static byte[] rewrite(String className, byte[] bytes) {
            ClassFile classFile = ClassFile.of();
            boolean[] touched = {false};

            CodeTransform forceFalse = (builder, element) -> {
                if (element instanceof InvokeInstruction call && targets(call)) {
                    // The stack holds the argument this call is about to consume. Drop it and
                    // push `false`, so it makes no difference where the original came from.
                    builder.pop();
                    builder.iconst_0();
                    touched[0] = true;
                }
                builder.with(element);
            };

            byte[] rewritten = classFile.transformClass(
                classFile.parse(bytes),
                ClassTransform.transformingMethodBodies(forceFalse)
            );

            if (!touched[0]) {
                // The name appeared but no call matched -- some other reference to the class.
                return null;
            }

            log("patched " + className);
            return rewritten;
        }

        private static boolean targets(InvokeInstruction call) {
            if (!OWNER.equals(call.owner().asInternalName())) {
                return false;
            }
            if (!METHOD.equals(call.name().stringValue())) {
                return false;
            }
            // Matched on shape rather than on the exact descriptor: all that matters is that the
            // last argument is the boolean being forced.
            var type = call.typeSymbol();
            int count = type.parameterCount();
            return count > 0 && ConstantDescs.CD_boolean.equals(type.parameterType(count - 1));
        }
    }
}
