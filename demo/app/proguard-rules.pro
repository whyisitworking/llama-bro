# the benchmark builds add this class (src/benchmark); the app finds it by name (ContainerFactory)
-keep class com.suhel.llamabro.demo.BenchmarkContainerSource { *; }
-dontwarn com.suhel.llamabro.demo.BenchmarkContainerSource
