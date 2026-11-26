plugins {
    `java-platform`
    id("llamabro.publish")
}

description = "Llama Bro's bill of materials: one version for every Llama Bro module an app uses."

// The modules must match: the backends load native libraries the core was built with. Every published module, at the
// same version as this BOM (the catalog's llamabro version, set by llamabro.publish).
val published =
    listOf(
        "llamabro",
        "llamabro-hexagon",
        "llamabro-opencl",
        "llamabro-hub",
        "llamabro-hub-work",
        "llamabro-typed",
        "llamabro-typed-ksp",
        "llamabro-server",
        "llamabro-rag",
        "llamabro-testing",
        "llamabro-hub-testing",
    )

dependencies {
    constraints {
        published.forEach { api(project(":$it")) }
    }
}
