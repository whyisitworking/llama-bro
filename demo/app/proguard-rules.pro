# the benchmark builds add this class (src/benchmark); the app finds it by name (ContainerFactory)
-keep class com.suhel.llamabro.demo.BenchmarkContainerSource { *; }
-dontwarn com.suhel.llamabro.demo.BenchmarkContainerSource

# Ktor (llamabro-server) looks for an attached IntelliJ debugger through java.lang.management, which Android lacks; the
# lookup is guarded and never runs on a phone
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
