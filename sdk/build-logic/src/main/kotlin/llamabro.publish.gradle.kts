// Publishing to Maven Central through the Central Portal, for every published module (D133): the coordinates
// io.github.whyisitworking:<module>:<the catalog's llamabro version>, the release variant with a sources jar and the
// javadoc jar Central requires, and the POM. A module adds its `description`. docs/publishing.md has the commands and
// the properties; no credential or key is ever in the repository.
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish.base")
}

group = "io.github.whyisitworking"
version =
    extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("llamabro")
        .get()
        .requiredVersion

// The key comes from Gradle properties only: in memory (signingInMemoryKey, usually from the environment as
// ORG_GRADLE_PROJECT_signingInMemoryKey) or the signing plugin's key ring file (signing.keyId). Without one nothing is
// signed, so publishToMavenLocal and the checks need no key, and an upload to Maven Central is refused below.
val signingKeyConfigured =
    providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent

mavenPublishing {
    // uploads a deployment the maintainer then publishes or drops by hand in the Portal; never released by the build
    publishToMavenCentral(automaticRelease = false)
    if (signingKeyConfigured) signAllPublications()

    // KDoc reaches an IDE through the sources jar; the javadoc jar is the placeholder Central accepts (D133)
    plugins.withId("com.android.library") {
        configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        configure(KotlinJvm(JavadocJar.Empty(), SourcesJar.Sources()))
    }

    pom {
        name.set(project.name)
        description.set(
            provider { checkNotNull(project.description) { "${project.path} needs a description for its POM" } },
        )
        inceptionYear.set("2026")
        url.set("https://github.com/whyisitworking/llama-bro")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("whyisitworking")
                name.set("Suhel Chakraborty")
                url.set("https://github.com/whyisitworking")
            }
        }
        scm {
            url.set("https://github.com/whyisitworking/llama-bro")
            connection.set("scm:git:https://github.com/whyisitworking/llama-bro.git")
            developerConnection.set("scm:git:ssh://git@github.com/whyisitworking/llama-bro.git")
        }
    }
}

if (!signingKeyConfigured) {
    // the plugin's first step of an upload, before it reads the credentials
    tasks.matching { it.name == "prepareMavenCentralPublishing" }.configureEach {
        doFirst {
            throw GradleException(
                "An upload to Maven Central must be signed: set signingInMemoryKey (docs/publishing.md).",
            )
        }
    }
}
