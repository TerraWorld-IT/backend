rootProject.name = "terraworld-backend"

// Composite build: the openapi-backend module is a git submodule that holds
// openapi-generator kotlin-spring output regenerated from TerraWorld-IT/openapi
// on every spec push. Including it here lets Gradle resolve it from source,
// so changes to the spec become compile-time feedback against our controllers.
includeBuild("openapi-backend")
