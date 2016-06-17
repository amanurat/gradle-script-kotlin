package org.gradle.script.lang.kotlin.support

import org.gradle.api.internal.ClassPathRegistry
import org.gradle.tooling.ProjectConnection
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.script.KotlinScriptExternalDependencies
import org.jetbrains.kotlin.script.ScriptDependenciesResolver
import org.jetbrains.kotlin.script.SimpleAnnotationAst
import org.jetbrains.kotlin.script.parseAnnotation
import java.io.File

class GetGradleKotlinScriptDependencies : ScriptDependenciesResolver {

    override fun resolve(projectRoot: File?, scriptFile: File?, annotations: Iterable<KtAnnotationEntry>, context: Any?): KotlinScriptExternalDependencies? {
        fun File.existingOrNull() = let { if (it.exists() && it.isDirectory) it else null }
        fun File.listPrefixedJars(prefixes: Iterable<String>): Iterable<File> = listFiles { file -> prefixes.any { file.name.startsWith(it) } }.asIterable()

        fun depsFromGradleHome(gradleHome: File): KotlinScriptExternalDependencies? {
            val libDir = File(gradleHome, "lib").existingOrNull()
            val pluginsDir = libDir?.let { File(libDir, "plugins") }?.existingOrNull()
            val classpath = (libDir?.listPrefixedJars(defaultDependenciesJarsPrefixes) ?: emptyList()) +
                (pluginsDir?.listPrefixedJars(defaultDependenciesJarsPrefixes) ?: emptyList())
            return if (classpath.size < defaultDependenciesJarsPrefixes.size) {
                // log
                null
            } else {
                makeDependencies(annotations, classpath.asIterable())
            }
        }

        fun depsFromGradleHomeAndProjectConnection(gradleHome: File?, projectConnection: ProjectConnection?): KotlinScriptExternalDependencies? {
            return if (projectConnection == null || gradleHome == null) null
                   else depsFromGradleHome(gradleHome)
        }

        return when (context) {
            is ClassPathRegistry -> makeDependencies(annotations, KotlinScriptDefinitionProvider.selectGradleApiJars(context))
            is File -> {
                depsFromGradleHome(context)
            }
            is Map<*,*> -> {
                depsFromGradleHomeAndProjectConnection(
                    context["gradleHome"]?.let { it as? File },
                    context["projectConnection"]?.let { it as? ProjectConnection })
            }
            else -> null
        }
    }

    private fun makeDependencies(annotations: Iterable<KtAnnotationEntry>, defaultClasspath: Iterable<File>): KotlinScriptExternalDependencies {
        val anns = annotations.map { parseAnnotation(it) }.filter { it.name == dependsOn::class.simpleName }
        val cp = anns.flatMap {
            it.value.mapNotNull {
                when (it) {
                    is SimpleAnnotationAst.Node.str -> File(it.value)
                    else -> null
                }
            }
        }
        return object : KotlinScriptExternalDependencies {
            override val classpath = defaultClasspath + cp
            override val imports = implicitImports
            override val sources = defaultClasspath + cp
        }
    }

    companion object {
        val implicitImports = listOf(
            "org.gradle.script.lang.kotlin.support.depends",
            "org.gradle.api.plugins.*",
            "org.gradle.script.lang.kotlin.*")

        val defaultDependenciesJarsPrefixes = listOf(
                "kotlin-stdlib-",
                "kotlin-reflect-",
                "kotlin-runtime-",
                "ant-",
                "gradle-",
                "groovy-all-")
    }
}

@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
annotation class dependsOn(val path: String)

