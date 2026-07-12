package com.popkter.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated

class RobotStatusProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    // 用于避免重复生成（process可能被调用多次）
    private var hasGenerated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("🟢 KSP processor started")

        if (hasGenerated) {
            logger.warn("⚠️ RobotStatusTransitions 已生成，跳过")
            return emptyList()
        }

        val robotClass = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("com.popkter.robot.status.RobotStatus")
        ) ?: run {
            logger.warn("❌ RobotStatus not found")
            return emptyList()
        }

        logger.warn("✅ Found RobotStatus class: ${robotClass.simpleName.asString()}")

        val transitionProps = robotClass.getAllProperties()
            .filter {
                it.type.resolve().declaration.simpleName.asString() == "TransitionProperty"
            }

        try {
            val file = codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = true),
                packageName = "com.popkter.robot.status",
                fileName = "RobotStatusTransitions"
            )

            file.bufferedWriter().use { writer ->
                writer.appendLine("package com.popkter.robot.status")
                writer.appendLine()
                writer.appendLine("import com.popkter.robot.status.RobotStatus")
                writer.appendLine("import com.popkter.robot.status.TransitionProperty")
                writer.appendLine()
                writer.appendLine("val RobotStatus.transitionMap: Map<String, TransitionProperty>")
                writer.appendLine("    get() = mapOf(")

                transitionProps.forEach {
                    val name = it.simpleName.asString()
                    writer.appendLine("        \"$name\" to this.$name,")
                }

                writer.appendLine("    )")
            }

            hasGenerated = true
        } catch (e: FileAlreadyExistsException) {
            logger.warn("⚠️ RobotStatusTransitions.kt 已存在，跳过生成")
        }

        return emptyList()
    }
}



