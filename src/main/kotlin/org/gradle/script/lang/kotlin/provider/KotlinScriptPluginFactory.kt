/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.script.lang.kotlin.provider

import org.gradle.script.lang.kotlin.KotlinBuildScript
import org.gradle.script.lang.kotlin.loggerFor
import org.gradle.script.lang.kotlin.support.KotlinScriptDefinitionProvider.BUILDSCRIPT_PROTOTYPE
import org.gradle.script.lang.kotlin.support.KotlinScriptDefinitionProvider.scriptDefinitionFor
import org.gradle.script.lang.kotlin.support.KotlinScriptDefinitionProvider.scriptDefinitionFromTemplate
import org.gradle.script.lang.kotlin.support.KotlinScriptDefinitionProvider.selectGradleApiJars

import org.gradle.api.Project
import org.gradle.api.initialization.dsl.ScriptHandler
import org.gradle.api.internal.ClassPathRegistry
import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.api.internal.initialization.ScriptHandlerInternal

import org.gradle.configuration.ScriptPlugin
import org.gradle.configuration.ScriptPluginFactory

import org.gradle.groovy.scripts.ScriptSource

import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath

import java.io.File

import java.lang.reflect.InvocationTargetException

class KotlinScriptPluginFactory(val classPathRegistry: ClassPathRegistry) : ScriptPluginFactory {

    val logger = loggerFor<KotlinScriptPluginFactory>()

    override fun create(scriptSource: ScriptSource, scriptHandler: ScriptHandler,
                        targetScope: ClassLoaderScope, baseScope: ClassLoaderScope,
                        topLevelScript: Boolean): ScriptPlugin =
        KotlinScriptPlugin(
            scriptSource,
            compile(scriptSource, scriptHandler as ScriptHandlerInternal, targetScope, baseScope))

    private fun compile(scriptSource: ScriptSource, scriptHandler: ScriptHandlerInternal,
                        targetScope: ClassLoaderScope, baseScope: ClassLoaderScope): (Project) -> Unit {
        val resource = scriptSource.resource
        val script = resource.text
        val scriptFile = resource.file
        val buildscriptRange = extractBuildScriptFrom(script)
        return when {
            buildscriptRange != null ->
                dualPhaseScript(scriptFile, script, buildscriptRange, scriptHandler, targetScope, baseScope)
            else ->
                singlePhaseScript(scriptFile, targetScope)
        }
    }

    private fun singlePhaseScript(scriptFile: File, targetScope: ClassLoaderScope): (Project) -> Unit {
        val classPath = defaultClassPath()
        val scriptClassLoader = classLoaderFor(targetScope)
        val scriptClass = compileScriptFile(scriptFile, classPath, scriptClassLoader)
        return { target -> executeScriptWithContextClassLoader(scriptClassLoader, scriptClass, target) }
    }

    private fun dualPhaseScript(scriptFile: File, script: String, buildscriptRange: IntRange,
                                scriptHandler: ScriptHandlerInternal,
                                targetScope: ClassLoaderScope, baseScope: ClassLoaderScope): (Project) -> Unit {
        val defaultClassPath = defaultClassPath()
        val buildscriptClassLoader = buildscriptClassLoaderFrom(baseScope)
        val buildscriptClass =
            compileBuildscriptSection(buildscriptRange, script, defaultClassPath, buildscriptClassLoader)
        return { target ->
            executeScriptWithContextClassLoader(buildscriptClassLoader, buildscriptClass, target)
            val effectiveClassPath = defaultClassPath + scriptHandler.scriptClassPath.asFiles
            val scriptClassLoader = scriptBodyClassLoaderFor(scriptHandler, targetScope)
            val scriptClass = compileScriptFile(scriptFile, effectiveClassPath, scriptClassLoader)
            executeScriptWithContextClassLoader(scriptClassLoader, scriptClass, target)
        }
    }

    private fun buildscriptClassLoaderFrom(baseScope: ClassLoaderScope) =
        classLoaderFor(baseScope.createChild("buildscript"))

    private fun scriptBodyClassLoaderFor(scriptHandler: ScriptHandlerInternal, targetScope: ClassLoaderScope) =
        classLoaderFor(targetScope.apply { export(scriptHandler.scriptClassPath) })

    private fun classLoaderFor(scope: ClassLoaderScope) =
        scope.run {
            export(KotlinBuildScript::class.java.classLoader)
            lock()
            localClassLoader
        }

    private fun compileBuildscriptSection(buildscriptRange: IntRange, script: String,
                                          classPath: ClassPath, classLoader: ClassLoader) =
        compileKotlinScript(
            tempBuildscriptFileFor(script.substring(buildscriptRange)),
            scriptDefinitionFor(classPath.asFiles, prototype = BUILDSCRIPT_PROTOTYPE),
            classLoader,
            logger)

    private fun compileScriptFile(scriptFile: File, classPath: ClassPath, classLoader: ClassLoader) =
        compileKotlinScript(scriptFile, scriptDefinitionFromTemplate(classPath), classLoader, logger)

    private fun executeScriptWithContextClassLoader(classLoader: ClassLoader, scriptClass: Class<*>, target: Any) {
        withContextClassLoader(classLoader) {
            executeScriptOf(scriptClass, target)
        }
    }

    private fun executeScriptOf(scriptClass: Class<*>, target: Any) {
        try {
            scriptClass.getConstructor(Project::class.java).newInstance(target)
        } catch(e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun tempBuildscriptFileFor(buildscript: String) =
        createTempFile("buildscript", ".gradle.kts").apply {
            writeText(buildscript)
        }

    private fun defaultClassPath(): ClassPath =
        DefaultClassPath.of(
            selectGradleApiJars(classPathRegistry))
}

inline fun withContextClassLoader(classLoader: ClassLoader, block: () -> Unit) {
    val currentThread = Thread.currentThread()
    val previous = currentThread.contextClassLoader
    try {
        currentThread.contextClassLoader = classLoader
        block()
    } finally {
        currentThread.contextClassLoader = previous
    }
}
