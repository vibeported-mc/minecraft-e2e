package dev.vibeported.mc.e2e.plugin.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtElement

/**
 * The errors this plugin can report.
 *
 * These are declared as FIR diagnostics rather than raised from the IR phase because that is what
 * puts them under the cursor. A FIR checker runs in the IDE as you type, so a block that captures
 * something it cannot is underlined in red immediately; an IR-phase failure would only show up in a
 * build log, long after the mistake was made.
 */
object E2eDiagnostics : KtDiagnosticsContainer() {

    /** The rule that makes the whole lifting scheme sound. */
    val E2E_ILLEGAL_CAPTURE: KtDiagnosticFactory1<String> by error1<KtElement, String>(
        SourceElementPositioningStrategies.DEFAULT
    )

    val E2E_BLOCK_NOT_LITERAL: KtDiagnosticFactory0 by error0<KtElement>()

    val E2E_NAME_NOT_CONSTANT: KtDiagnosticFactory0 by error0<KtElement>()

    val E2E_SHARED_MISPLACED: KtDiagnosticFactory0 by error0<KtElement>()

    val E2E_BLOCK_IN_NESTED_LAMBDA: KtDiagnosticFactory0 by error0<KtElement>()

    /** A test body is a plan, not code: only shared declarations and blocks belong in it. */
    val E2E_TEST_BODY_NOT_DECLARATIVE: KtDiagnosticFactory0 by error0<KtElement>()

    val E2E_DUPLICATE_NAME: KtDiagnosticFactory1<String> by error1<KtElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = E2eErrorMessages
}

object E2eErrorMessages : BaseDiagnosticRendererFactory() {

    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("E2E") { map ->
        map.put(
            E2eDiagnostics.E2E_ILLEGAL_CAPTURE,
            "A server/client block cannot capture ''{0}''. The block runs in another process, where " +
                "that value does not exist. Declare it as `var x by shared<T>()` in the enclosing " +
                "e2e block, or move it inside this block.",
            null,
        )
        map.put(
            E2eDiagnostics.E2E_BLOCK_NOT_LITERAL,
            "This must be a lambda written in place. A function reference or a lambda held in a " +
                "variable has no stable identity to give the generated dispatch table.",
        )
        map.put(
            E2eDiagnostics.E2E_NAME_NOT_CONSTANT,
            "A suite or test name must be a compile-time constant, because it forms part of the " +
                "stable id that identifies this test across processes and in reports.",
        )
        map.put(
            E2eDiagnostics.E2E_SHARED_MISPLACED,
            "shared<T>() may only initialise a local declared directly inside an e2e block, as " +
                "`val pos = shared<BlockPos>()`, which is what gives the value one declaring scope " +
                "and therefore one stable id.",
        )
        map.put(
            E2eDiagnostics.E2E_BLOCK_IN_NESTED_LAMBDA,
            "A server/client block cannot be declared inside another lambda: its stable id would " +
                "depend on how many times that lambda ran.",
        )
        map.put(
            E2eDiagnostics.E2E_TEST_BODY_NOT_DECLARATIVE,
            "An e2e test body may only declare shared values and call server/client blocks. It is " +
                "never executed: the compiler reads the blocks out of it as an ordered list of " +
                "steps for the orchestrator, so there is nowhere for this statement to run. Move " +
                "it inside a server or client block.",
        )
        map.put(
            E2eDiagnostics.E2E_DUPLICATE_NAME,
            "''{0}'' is declared twice in the same scope, so the two would share a stable id.",
            null,
        )
    }
}
