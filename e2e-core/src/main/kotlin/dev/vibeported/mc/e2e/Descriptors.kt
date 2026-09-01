package dev.vibeported.mc.e2e

import kotlinx.serialization.Serializable

/** One `suite("...") { }`, built by running its (side-effect free) builder body. */
@Serializable
public data class SuiteDescriptor(
    public val id: String,
    public val name: String,
    public val tests: List<TestDescriptor>,
)

/** One `e2e("...") { }`. Its steps live in the generated index. */
@Serializable
public data class TestDescriptor(
    public val id: String,
    public val name: String,
)
