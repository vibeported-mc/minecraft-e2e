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
object ProcedureDiagnostics : KtDiagnosticsContainer() {

    /** The rule that makes the whole lifting scheme sound. */
    val ILLEGAL_CAPTURE: KtDiagnosticFactory1<String> by error1<KtElement, String>(
        SourceElementPositioningStrategies.DEFAULT
    )

    val PROCEDURE_NOT_LITERAL: KtDiagnosticFactory0 by error0<KtElement>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = ProcedureErrorMessages
}

object ProcedureErrorMessages : BaseDiagnosticRendererFactory() {

    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("E2E") { map ->
        map.put(
            ProcedureDiagnostics.ILLEGAL_CAPTURE,
            "A server/client procedure cannot capture ''{0}''. It runs in another process, where " +
                "that value does not exist. Pass it as an argument instead -- every procedure " +
                "takes up to ten -- or declare it inside the procedure.",
            null,
        )
        map.put(
            ProcedureDiagnostics.PROCEDURE_NOT_LITERAL,
            "This must be a lambda written in place. A function reference or a lambda held in a " +
                "variable has no stable identity to give the generated dispatch table.",
        )
    }
}
