package dev.vibeported.rpc.plugin.fir

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
 * What this plugin refuses to compile.
 *
 * Declared as FIR diagnostics rather than raised from the backend, because that is what puts them
 * under the cursor: a frontend checker runs in the IDE as you type, while an IR-phase failure only
 * shows up in a build log long after the mistake was made.
 */
public object RpcDiagnostics : KtDiagnosticsContainer() {

    /** The rule the whole lifting scheme rests on. */
    public val ILLEGAL_CAPTURE: KtDiagnosticFactory1<String> by error1<KtElement, String>(
        SourceElementPositioningStrategies.DEFAULT
    )

    public val BODY_NOT_LITERAL: KtDiagnosticFactory0 by error0<KtElement>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = RpcErrorMessages
}

public object RpcErrorMessages : BaseDiagnosticRendererFactory() {

    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("RPC") { map ->
        map.put(
            RpcDiagnostics.ILLEGAL_CAPTURE,
            "A procedure body cannot capture ''{0}''. It runs on another node, where that value " +
                "does not exist. Pass it as an argument instead, or declare it inside the body.",
            null,
        )
        map.put(
            RpcDiagnostics.BODY_NOT_LITERAL,
            "A procedure body must be a lambda written in place. A function reference, or a lambda " +
                "held in a variable, has no body to lift and no stable identity to record.",
        )
    }
}
