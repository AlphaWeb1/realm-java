/*
 * Copyright 2018 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.transformer

import com.android.SdkConstants
import com.android.build.api.transform.Status
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformOutputProvider
import io.realm.annotations.RealmClass
import javassist.CtClass
import javassist.NotFoundException
import org.gradle.api.Project
import java.io.File
import java.util.jar.JarFile

class IncrementalBuild(project: Project, outputProvider: TransformOutputProvider, transform: RealmTransformer)
    : TransformBuildTemplate(project, outputProvider, transform) {

    override fun prepareOutputClasses(inputs: MutableCollection<TransformInput>) {
        this.inputs = inputs;
        findClassNames(inputs, outputClassNames, outputReferencedClassNames) // Output files
        logger.debug("Incremental build. Files being processed: ${outputClassNames.size}.")
        logger.debug("Incremental files: ${outputClassNames.joinToString(",")}")
    }

    /**
     * Go through the transform input in order to find all files we need to transform.
     *
     * @param inputs set of input files
     * @param directoryFiles the set of files in directories getting compiled. These are candidates for the transformer.
     * @param referencedFiles the set of files that are possible referenced but never transformed (required by JavaAssist).
     * @param isIncremental `true` if build is incremental.
     */
    override fun findClassNames(inputs: Collection<TransformInput>,
                                directoryFiles: MutableSet<String>,
                                referencedFiles: MutableSet<String>) {
        inputs.forEach {
            // Files in directories are files we most likely want to transform
             it.directoryInputs.forEach {
                val dirPath: String = it.file.absolutePath
                it.changedFiles.entries.forEach {
                    if (it.value == Status.NOTCHANGED || it.value == Status.REMOVED) {
                        return
                    }
                    val filePath: String = it.key.absolutePath
                    if (filePath.endsWith(SdkConstants.DOT_CLASS)) {
                        val className = filePath
                                .substring(dirPath.length + 1, filePath.length - SdkConstants.DOT_CLASS.length)
                                .replace(File.separatorChar, '.')
                        directoryFiles.add(className)
                    }
                }
            }

            // Files in Jars are always treated as referenced input. They should already have been
            // modified by the transformer in the project that built the jar.
            it.jarInputs.forEach {
                if (it.status == Status.REMOVED) {
                    return
                }

                val jarFile = JarFile(it.file)
                jarFile.entries()
                        .toList()
                        .filter {
                            !it.isDirectory && it.name.endsWith(SdkConstants.DOT_CLASS)
                        }
                        .forEach {
                            val path: String = it.name
                            // The jar might not using File.separatorChar as the path separator. So we just replace both `\` and
                            // `/`. It depends on how the jar file was created.
                            // See http://stackoverflow.com/questions/13846000/file-separators-of-path-name-of-zipentry
                            val className: String = path
                                    .substring(0, path.length - SdkConstants.DOT_CLASS.length)
                                    .replace('/', '.')
                                    .replace('\\', '.')
                            referencedFiles.add(className)
                        }
                jarFile.close() // Crash transformer if this fails
            }
        }
    }

    override fun findModelClasses(classNames: Set<String>): Collection<CtClass> {
        val realmObjectProxyInterface: CtClass = classPool.get("io.realm.internal.RealmObjectProxy")
        // For incremental builds we need to determine if a class is a model class file
        // based on information in the file itself. This require checks that are only
        // possible once we loaded the CtClass from the ClassPool and is slower
        // than the approach used when doing full builds.
        return classNames
                // Map strings to CtClass'es.
                .map { classPool.getCtClass(it) }
                // Model classes either have the @RealmClass annotation directly (if implementing RealmModel)
                // or their superclass has it (if extends RealmObject). The annotation processor
                // will have ensured the annotation is only present in these cases.
                .filter {
                    var result: Boolean
                    if (it.hasAnnotation(RealmClass::class.java)) {
                        result = true
                    } else {
                        try {
                            result = it.superclass?.hasAnnotation(RealmClass::class.java) == true
                        } catch (e: NotFoundException) {
                            // Can happen if the super class is part of the `android.jar` which might
                            // not have been loaded. In any case, any base class part of Android cannot
                            // be a Realm model class.
                            result = false
                        }
                    }
                    return@filter result
                }
                // Proxy classes are generated by the Realm Annotation Processor and might accidentally
                // parse the above check (e.g. if the model class has the @RealmClass annotation), so
                // ignore them.
                .filter { !isSubtypeOf(it, realmObjectProxyInterface) }
                // Unfortunately the RealmObject base class parses all above checks, so explicitly
                // ignore it.
                .filter { !it.name.equals("io.realm.RealmObject") }
    }
}