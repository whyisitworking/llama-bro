package com.suhel.llamabro.typed.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Registered in META-INF/services; KSP builds a [ToolboxProcessor] per compilation. */
public class ToolboxProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ToolboxProcessor(environment.codeGenerator, environment.logger)
}
