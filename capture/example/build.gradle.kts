// Renders a rotating triangle the way Minecraft renders, and records it.

plugins { application }

val lwjglVersion = "3.3.6"
val lwjglNatives = "natives-windows"

dependencies {
    implementation(project(":capture:libav"))

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
}

application {
    mainClass = "dev.vibeported.capture.example.RotatingTriangleCapture"
}

tasks.named<JavaExec>("run") {
    // The dev loop: load the DLLs straight out of dist/bin rather than the ones
    // packed into the jar, so a rebuild of the natives takes effect immediately.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("libav.home", project(":capture").layout.buildDirectory.dir("natives").get().asFile.absolutePath)
    workingDir = layout.projectDirectory.asFile
}
