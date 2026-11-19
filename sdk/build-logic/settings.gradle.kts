// The SDK's convention plugins, included by both builds (the repository's and sdk/'s), so the build setup every
// published library shares is written once.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
