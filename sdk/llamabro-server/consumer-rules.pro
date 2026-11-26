# llamabro-server uses no reflection of its own; Ktor's CIO engine and kotlinx.serialization ship what they need.

# Ktor looks for an attached IntelliJ debugger through java.lang.management, which Android lacks: R8 in an app that
# minifies fails on the missing classes. The lookup is guarded and never runs on a phone
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
