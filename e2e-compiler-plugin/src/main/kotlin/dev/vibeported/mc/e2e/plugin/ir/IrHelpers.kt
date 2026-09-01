package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.parentAsClass

/**
 * The JVM class the file facade compiles to, which is where a top-level suite property ends up as
 * a static getter.
 *
 * Mirrors the compiler rule: Movement.kt becomes MovementKt, unless a file-level JvmName says
 * otherwise.
 */
internal fun IrFile.facadeClassName(): String {
    val simple = jvmNameAnnotation() ?: defaultFacadeSimpleName()
    val packageName = packageFqName.asString()
    return if (packageName.isEmpty()) simple else "$packageName.$simple"
}

private fun IrFile.jvmNameAnnotation(): String? = annotations
    .firstOrNull { it.isJvmName() }
    ?.let { annotation ->
        val index = annotation.symbol.owner.parameters.indexOfFirst { it.name.asString() == "name" }
        (annotation.arguments.getOrNull(index) as? IrConst)?.value as? String
    }

private fun IrConstructorCall.isJvmName(): Boolean =
    runCatching { symbol.owner.parentAsClass.name.asString() == "JvmName" }.getOrDefault(false)

private fun IrFile.defaultFacadeSimpleName(): String {
    val base = fileEntry.name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.')
    val sanitized = base.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
    return sanitized.replaceFirstChar { it.uppercaseChar() } + "Kt"
}

/** Turns an IR node back into something the compiler can print a file:line:column for. */
internal fun IrFile.locationOf(element: IrElement): CompilerMessageLocation? {
    val offset = element.startOffset
    if (offset < 0) return null
    return CompilerMessageLocation.create(
        fileEntry.name,
        fileEntry.getLineNumber(offset) + 1,
        fileEntry.getColumnNumber(offset) + 1,
        null,
    )
}
