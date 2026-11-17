package com.suhel.llamabro.typed.ksp

import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate

/**
 * For each `@LbToolbox` class `X`, writes `X_LbToolbox.kt` with `fun X.asToolbox(json: Json = Json): Toolbox` (D61):
 * the `@LbTool` methods of `X` and of its `@LbToolbox` supertypes, each once, built with llamabro-typed's `tool(…)`.
 * Every misuse D61 lists is a `KSPLogger.error` on its symbol, which fails the build.
 */
internal class ToolboxProcessor(private val codeGenerator: CodeGenerator, private val logger: KSPLogger) :
    SymbolProcessor {
    private val reader = ToolReader(logger)

    /** The toolbox each generated file belongs to, by package and file name, so two can't claim one name. */
    private val fileOwners = mutableMapOf<String, String>()

    /** Each method read once, so a tool reached from several toolboxes reports its errors once. */
    private val read = mutableMapOf<String, ToolSpec?>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getSymbolsWithAnnotation(LB_TOOL).forEach(::checkPlacement)
        val (ready, deferred) =
            resolver.getSymbolsWithAnnotation(LB_TOOLBOX).partition {
                it.validate(enableNewFeatures = true)
            }
        ready.filterIsInstance<KSClassDeclaration>().forEach(::generate)
        return deferred
    }

    private fun checkPlacement(symbol: KSAnnotated) {
        val f = symbol as? KSFunctionDeclaration ?: return
        val owner = f.parentDeclaration as? KSClassDeclaration
        val method = f.simpleName.asString()
        when {
            owner == null -> {
                logger.error("tool $method is not in a class: move it into an @LbToolbox class", f)
            }

            !owner.has(LB_TOOLBOX) -> {
                val name = owner.simpleName.asString()
                logger.error(
                    "tool $method is in $name, which is not an @LbToolbox: annotate $name with @LbToolbox",
                    f,
                )
            }
        }
    }

    private fun generate(box: KSClassDeclaration) {
        val visibility = visibility(box) ?: return
        val prefix = box.annotation(LB_TOOLBOX)?.arg("prefix") as? String ?: ""
        val types = toolboxTypes(box)
        val functions =
            types
                .flatMap { type ->
                    type.declarations.filterIsInstance<KSFunctionDeclaration>().filter { it.has(LB_TOOL) }
                }.distinctBy(::key)
        val tools = functions.map { f -> read.getOrPut(key(f)) { reader.read(f) } }
        if (tools.any { it == null }) return
        val specs = tools.filterNotNull()
        if (!namesOk(box, prefix, specs)) return
        val names = generateSequence(box as KSDeclaration) { it.parentDeclaration }.toList().reversed()
        val stem = names.joinToString("_") { it.simpleName.asString() }
        if (!fileNameFree(box, stem)) return
        val source =
            ToolboxWriter(
                packageName = box.packageName.asString(),
                receiver = qualified(box.fqn()),
                stem = stem,
                visibility = visibility,
                prefix = prefix,
            ).write(specs)
        val sources = types.mapNotNull { it.containingFile }.distinct().toTypedArray()
        codeGenerator
            .createNewFile(Dependencies(false, *sources), box.packageName.asString(), "${stem}_LbToolbox")
            .bufferedWriter()
            .use { it.write(source) }
    }

    /** Whether `<stem>_LbToolbox.kt` is still free: `Outer.Tools` and a class `Outer_Tools` would share it. */
    private fun fileNameFree(box: KSClassDeclaration, stem: String): Boolean {
        val file = "${box.packageName.asString()}.${stem}_LbToolbox"
        val owner = fileOwners.getOrPut(file) { box.fqn() }
        if (owner == box.fqn()) return true
        logger.error(
            "@LbToolbox ${box.fqn()} and $owner would both generate ${stem}_LbToolbox.kt: rename one of them",
            box,
        )
        return false
    }

    /** [box] and its `@LbToolbox` supertypes, each once, nearest first. */
    private fun toolboxTypes(box: KSClassDeclaration): List<KSClassDeclaration> {
        val found = linkedMapOf<String, KSClassDeclaration>()
        val queue = ArrayDeque(listOf(box))
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            val name = type.fqn()
            if (name in found || name == "kotlin.Any") continue
            found[name] = type
            queue += type.superTypes.mapNotNull { it.resolve().classDeclaration() }
        }
        return found.values.filter { it == box || it.has(LB_TOOLBOX) }
    }

    /** Every name, prefix included, matches the pattern, and no two tools share one. */
    private fun namesOk(box: KSClassDeclaration, prefix: String, tools: List<ToolSpec>): Boolean {
        val byName = mutableMapOf<String, ToolSpec>()
        var ok = true
        tools.forEach { tool ->
            val name = prefix + tool.name
            val method = tool.function.simpleName.asString()
            val clash = byName.putIfAbsent(name, tool)
            val problem =
                when {
                    !TOOL_NAME.matches(name) -> {
                        "tool $method: the name '$name' must match ${TOOL_NAME.pattern}, the toolbox's prefix " +
                            "included: change @LbTool(name = …) or @LbToolbox(prefix = …)"
                    }

                    clash != null -> {
                        "two tools are named '$name' in ${box.simpleName.asString()}: " +
                            "${clash.function.simpleName.asString()} and $method; give one another @LbTool(name = …)"
                    }

                    else -> {
                        null
                    }
                }
            if (problem != null) {
                logger.error(problem, tool.function)
                ok = false
            }
        }
        return ok
    }

    /** The extension's visibility, the toolbox's own (D61); null after an error when generated code can't see it. */
    private fun visibility(box: KSClassDeclaration): String? {
        val chain = generateSequence(box as KSDeclaration) { it.parentDeclaration }.toList()
        val problem =
            when {
                chain.any { it.isPrivate() || it.isProtected() } -> "is not visible to generated code"
                box.typeParameters.isNotEmpty() -> "has type parameters"
                else -> return if (chain.any { it.isInternal() }) "internal" else "public"
            }
        logger.error("@LbToolbox ${box.simpleName.asString()} $problem: make it a public or internal class", box)
        return null
    }

    private fun key(f: KSFunctionDeclaration): String =
        f.fqn() +
            f.parameters.joinToString(",", "(", ")") {
                it.type
                    .resolve()
                    .declaration
                    .fqn()
            }
}
