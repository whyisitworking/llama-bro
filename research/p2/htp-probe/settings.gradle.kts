// Throwaway probe for P2 T1 (can an app load the unsigned HTP skeleton?). Not part of the SDK build.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "htp-probe"
