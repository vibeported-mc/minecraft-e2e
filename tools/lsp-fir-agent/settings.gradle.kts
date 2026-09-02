// Deliberately its own build. It patches a JetBrains language server, has nothing to do with the
// framework, and must never end up on anything's classpath -- so it is not included from the root
// settings and is built on its own with `gradlew -p tools/lsp-fir-agent`.
rootProject.name = "lsp-fir-agent"
