// A plain Java project, and deliberately not a ModDevGradle one.
//
// `Launch` runs before FancyModLoader exists and touches exactly two of its types, so the whole
// modding setup would buy nothing here. Both dependencies are `compileOnly` because a NeoForge run
// already has them on its classpath -- this jar only has to be there beside them.
plugins {
    `java-library`
}

// FancyModLoader 11 is built for Java 25 and refuses to be resolved by anything older, so this
// module overrides the 21 the rest of the tree uses. It is the same reason `:mc-driver:driver` does.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Non-transitive, both of them. FancyModLoader's own dependencies include Mojang's libraries,
// which live in a repository only ModDevGradle knows about -- and none of them are needed to
// compile against `Entrypoint`. Taking the two jars alone is what keeps this module out of the
// modding setup entirely.
dependencies {
    compileOnly(libs.fml.loader) { isTransitive = false }
    // `Dist` lives in mergetool's api jar rather than in the loader.
    compileOnly(variantOf(libs.fml.distmarker) { classifier("api") }) { isTransitive = false }
}
