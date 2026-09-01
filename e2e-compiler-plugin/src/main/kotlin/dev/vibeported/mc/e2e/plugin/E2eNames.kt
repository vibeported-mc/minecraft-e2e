package dev.vibeported.mc.e2e.plugin

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** The DSL declarations this plugin recognises, and the runtime it rewrites them onto. */
public object E2eNames {
    public val PACKAGE: FqName = FqName("dev.vibeported.mc.e2e")

    public val SUITE_BUILDER: ClassId = ClassId(PACKAGE, Name.identifier("SuiteBuilder"))
    public val BLOCK_ID: ClassId = ClassId(PACKAGE, Name.identifier("BlockId"))
    public val SHARED_ID: ClassId = ClassId(PACKAGE, Name.identifier("SharedId"))
    public val NODE_ID: ClassId = ClassId(PACKAGE, Name.identifier("NodeId"))
    public val NODE_ID_COMPANION: ClassId = NODE_ID.createNestedClassId(Name.identifier("Companion"))
    public val BLOCK_SCOPE: ClassId = ClassId(PACKAGE, Name.identifier("BlockScope"))
    public val BLOCK_TABLE: ClassId = ClassId(PACKAGE, Name.identifier("E2eBlockTable"))
    public val SHARED_DELEGATE: ClassId = ClassId(PACKAGE, Name.identifier("SharedDelegate"))

    public val SUITE: CallableId = CallableId(PACKAGE, Name.identifier("suite"))
    public val E2E: CallableId = CallableId(SUITE_BUILDER, Name.identifier("e2e"))
    public val SERVER: CallableId = CallableId(PACKAGE, Name.identifier("server"))
    public val CLIENT: CallableId = CallableId(PACKAGE, Name.identifier("client"))
    public val SHARED: CallableId = CallableId(PACKAGE, Name.identifier("shared"))

    /** Members of `BlockScope` the rewritten code calls. */
    public val DISPATCH: Name = Name.identifier("dispatch")
    public val SHARED_GET: Name = Name.identifier("sharedGet")
    public val SHARED_SET: Name = Name.identifier("sharedSet")

    /** Suffix of the generated per-file dispatch table object. */
    public const val TABLE_SUFFIX: String = "\$E2eBlocks"
}
