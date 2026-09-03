package dev.vibeported.rpc.plugin.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory3
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error3
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

    /** Running a body here, when the whole point of it is to run somewhere else. */
    public val BODY_INVOKED_LOCALLY: KtDiagnosticFactory0 by error0<KtElement>()

    /** An argument or result nothing can encode. Reported on the lambda that declared the type. */
    public val UNSERIALIZABLE_TYPE: KtDiagnosticFactory3<String, String, String> by
        error3<KtElement, String, String, String>(SourceElementPositioningStrategies.DEFAULT)

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
            "A procedure body must be a lambda written here, or one handed to this function in a " +
                "parameter marked @RpcLift. Anything else was never lifted, and would fail at the " +
                "far end of the chain with no clue which link dropped it.",
        )
        map.put(
            RpcDiagnostics.BODY_INVOKED_LOCALLY,
            "A procedure body cannot be run here. It was lifted out of this file at compile time, " +
                "and what is left in this parameter is a handle naming it -- pass it to a call, " +
                "which is the only thing that can reach the node it belongs to.",
        )
        map.put(
            RpcDiagnostics.UNSERIALIZABLE_TYPE,
            "A procedure {0} cannot be ''{1}'', because {2}. It has to cross a wire, and " +
                "kotlinx.serialization is what carries it.",
            null,
            null,
            null,
        )
    }
}
