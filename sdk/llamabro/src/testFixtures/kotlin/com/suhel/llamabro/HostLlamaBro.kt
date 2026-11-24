package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import java.io.File

/**
 * A [LlamaBro] over the real native core built for this machine, for other modules' host end-to-end tests
 * (`llamabro-typed`'s `HostTypedTest`). [libDir] holds the host build's libraries, which must also be on
 * `java.library.path`. A test fixture, so it is not in the released artifact (review L4).
 */
public fun hostLlamaBro(libDir: String, filesDir: File, config: LlamaBroConfig = LlamaBroConfig.Default): LlamaBro =
    LlamaBro(LlamaBroRuntime(JniEngine, filesDir, libDir, config))
