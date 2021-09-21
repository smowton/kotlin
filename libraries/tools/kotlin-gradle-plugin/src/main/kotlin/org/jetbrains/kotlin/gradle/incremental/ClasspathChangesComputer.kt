/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.incremental

import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.incremental.*
import org.jetbrains.kotlin.incremental.storage.FileToCanonicalPathConverter
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import java.io.File
import java.util.*
import kotlin.collections.LinkedHashMap

/** Computes [ClasspathChanges] between two [ClasspathSnapshot]s .*/
object ClasspathChangesComputer {

    fun compute(
        currentClasspathEntrySnapshotFiles: List<File>,
        previousClasspathEntrySnapshotFiles: List<File>,
        unchangedCurrentClasspathEntrySnapshotFiles: List<File>
    ): ClasspathChanges {
        // To improve performance, we will compute changes for the changed snapshot files only, ignoring unchanged ones. (Duplicate classes
        // will make this a bit tricky, but it will be dealt with below.)
        // First, align unchanged snapshot files in the current classpath with unchanged snapshot files in the previous classpath. Gradle
        // has this information, but doesn't expose it, so we have to reconstruct it here.
        val unchangedCurrentToPreviousAlignment: Map<File, File> =
            alignUnchangedSnapshotFiles(unchangedCurrentClasspathEntrySnapshotFiles, previousClasspathEntrySnapshotFiles)

        // Use sets to make presence checks faster
        val unchangedCurrentFiles: Set<File> = unchangedCurrentToPreviousAlignment.keys
        val unchangedPreviousFiles: Set<File> = unchangedCurrentToPreviousAlignment.values.toSet()

        // We will split the current files into 2 groups:
        //    1a) Unchanged current files
        //    1b) Added files
        // We will split the previous files into 2 groups:
        //    2a) Unchanged previous files
        //    2b) Removed files
        // If the classpath doesn't contain duplicate classes, comparing (1b) with (2b) would be enough.
        // However, if the classpath contains duplicate classes, comparing (1b) with (2b) would not be enough.
        // Therefore, to deal with duplicate classes while still being able to compare (1b) with (2b), we will find snapshot files in groups
        // (1a) and (2a) that have duplicate classes with groups (1b) or (2b) and add them to groups (1b) and (2b). Duplicate classes in
        // groups (1b) and (2b) will then be handled in a separate step (see ClasspathChangesComputer.getNonDuplicateClassSnapshots).
        val addedFiles: List<File> = currentClasspathEntrySnapshotFiles.filter { it !in unchangedCurrentFiles }
        val removedFiles: List<File> = previousClasspathEntrySnapshotFiles.filter { it !in unchangedPreviousFiles }

        val adjustedAddedFiles = addedFiles.toMutableSet()
        val adjustedRemovedFiles = removedFiles.toMutableSet()
        unchangedCurrentToPreviousAlignment.forEach { (unchangedCurrentFile, unchangedPreviousFile) ->
            if (unchangedCurrentFile.containsDuplicatesWith(addedFiles) || unchangedPreviousFile.containsDuplicatesWith(removedFiles)) {
                adjustedAddedFiles.add(unchangedCurrentFile)
                adjustedRemovedFiles.add(unchangedPreviousFile)
            }
        }

        // Keep the original order of added/removed files as it is important for the handling of duplicate classes.
        val finalAddedFiles: List<File> = currentClasspathEntrySnapshotFiles.filter { it in adjustedAddedFiles }
        val finalRemovedFiles: List<File> = previousClasspathEntrySnapshotFiles.filter { it in adjustedRemovedFiles }

        val changedCurrentSnapshot = ClasspathSnapshotSerializer.load(finalAddedFiles)
        val changedPreviousSnapshot = ClasspathSnapshotSerializer.load(finalRemovedFiles)

        return compute(changedCurrentSnapshot, changedPreviousSnapshot)
    }

    /**
     * Maps the unchanged snapshot files of the current build to the unchanged snapshot files of the previous build (selected from all the
     * snapshot files of the previous build).
     */
    private fun alignUnchangedSnapshotFiles(unchangedCurrentSnapshotFiles: List<File>, previousSnapshotFiles: List<File>): Map<File, File> {
        var index = 0
        return unchangedCurrentSnapshotFiles.associateWith { unchangedCurrentFile ->
            var candidates = previousSnapshotFiles.subList(index, previousSnapshotFiles.size).filter { previousFile ->
                unchangedCurrentFile.lastModified() == previousFile.lastModified() && unchangedCurrentFile.length() == previousFile.length()
            }
            if (candidates.size > 1) {
                candidates = candidates.filter { candidate ->
                    unchangedCurrentFile.readBytes().contentEquals(candidate.readBytes())
                }
            }
            check(candidates.isNotEmpty()) {
                "Can't find previous snapshot file of unchanged current snapshot file '${unchangedCurrentFile.path}'"
            }
            // If there are multiple candidates, select the first one. (It doesn't have to match Gradle's alignment as long as it's still a
            // correct alignment.)
            val unchangedPreviousFile = candidates.first()
            while (previousSnapshotFiles[index] != unchangedPreviousFile) {
                index++
            }
            index++
            unchangedPreviousFile
        }
    }

    /** Returns `true` if this snapshot file contains a duplicate class with another snapshot file in the given set. */
    @Suppress("unused", "UNUSED_PARAMETER")
    private fun File.containsDuplicatesWith(otherSnapshotFiles: List<File>): Boolean {
        // TODO: Implement and optimize this method
        // Existing approach (with `kotlin.incremental.useClasspathSnapshot=false`) doesn't seem to handle duplicate classes, so it is
        // probably not a regression that we are not handling duplicate classes here yet.
        return false
    }

    fun compute(currentClasspathSnapshot: ClasspathSnapshot, previousClasspathSnapshot: ClasspathSnapshot): ClasspathChanges {
        val currentClassSnapshots = currentClasspathSnapshot.getNonDuplicateClassSnapshots()
        val previousClassSnapshots = previousClasspathSnapshot.getNonDuplicateClassSnapshots()

        return compute(currentClassSnapshots, previousClassSnapshots)
    }

    /**
     * Computes changes between two lists of [ClassSnapshot]s.
     *
     * Each list must not contain duplicate classes.
     */
    fun compute(currentClassSnapshots: List<ClassSnapshot>, previousClassSnapshots: List<ClassSnapshot>): ClasspathChanges {
        if (currentClassSnapshots.any { it is ContentHashJavaClassSnapshot }
            || previousClassSnapshots.any { it is ContentHashJavaClassSnapshot }) {
            return ClasspathChanges.NotAvailable.UnableToCompute
        }

        val workingDir =
            FileUtil.createTempDirectory(this::class.java.simpleName, "_WorkingDir_${UUID.randomUUID()}", /* deleteOnExit */ true)
        val incrementalJvmCache = IncrementalJvmCache(workingDir, /* targetOutputDir */ null, FileToCanonicalPathConverter)

        // Step 1:
        //   - Add previous class snapshots to incrementalJvmCache.
        //   - Internally, incrementalJvmCache maintains a set of dirty classes to detect removed classes. Add previous classes to this set
        //     to detect removed classes later (see step 2).
        //   - The final ChangesCollector result will contain all symbols in the previous classes (we actually don't need them, but it's
        //     part of the API's effects).
        val unusedChangesCollector = ChangesCollector()
        for (previousSnapshot in previousClassSnapshots) {
            when (previousSnapshot) {
                is KotlinClassSnapshot -> {
                    incrementalJvmCache.saveClassToCache(
                        kotlinClassInfo = previousSnapshot.classInfo,
                        sourceFiles = null,
                        changesCollector = unusedChangesCollector
                    )
                    incrementalJvmCache.markDirty(previousSnapshot.classInfo.className)
                }
                is RegularJavaClassSnapshot -> {
                    incrementalJvmCache.saveJavaClassProto(
                        source = null,
                        serializedJavaClass = previousSnapshot.serializedJavaClass,
                        collector = unusedChangesCollector
                    )
                    incrementalJvmCache.markDirty(JvmClassName.byClassId(previousSnapshot.serializedJavaClass.classId))
                }
                is EmptyJavaClassSnapshot -> {
                    // Nothing to process as these classes don't impact the result.
                }
                is ContentHashJavaClassSnapshot -> {
                    error("Unexpected type (it should have been handled earlier): ${previousSnapshot.javaClass.name}")
                }
            }
        }

        // Step 2:
        //   - Add current class snapshots to incrementalJvmCache. This will overwrite any previous class snapshots that have the same
        //     `JvmClassName`. The previous class snapshots that are not overwritten will be detected as removed class snapshots and will be
        //     removed from incrementalJvmCache in step 3.
        //   - Internally, incrementalJvmCache will mark these classes as non-dirty. That means unchanged/modified classes that were marked
        //     as dirty in step 1 will now be non-dirty (added classes will also be marked as non-dirty even though they were not marked as
        //     dirty before). After this, the remaining dirty classes will be removed classes.
        //   - The intermediate ChangesCollector result will contain all symbols in added classes and changed (added/modified/removed)
        //     symbols in modified classes. We will collect all symbols in removed classes in step 3.
        val changesCollector = ChangesCollector()
        for (currentSnapshot in currentClassSnapshots) {
            when (currentSnapshot) {
                is KotlinClassSnapshot -> {
                    incrementalJvmCache.saveClassToCache(
                        kotlinClassInfo = currentSnapshot.classInfo,
                        sourceFiles = null,
                        changesCollector = changesCollector
                    )
                }
                is RegularJavaClassSnapshot -> {
                    incrementalJvmCache.saveJavaClassProto(
                        source = null,
                        serializedJavaClass = currentSnapshot.serializedJavaClass,
                        collector = changesCollector
                    )
                }
                is EmptyJavaClassSnapshot -> {
                    // Nothing to process as these classes don't impact the result.
                }
                is ContentHashJavaClassSnapshot -> {
                    error("Unexpected type (it should have been handled earlier): ${currentSnapshot.javaClass.name}")
                }
            }
        }

        // Step 3:
        //   - Detect removed classes: they are the remaining dirty classes.
        //   - Remove previous class snapshots of removed classes from incrementalJvmCache.
        //   - The final ChangesCollector result will contain all symbols in added classes, changed (added/modified/removed) symbols in
        //     modified classes, and all symbols in removed classes.
        incrementalJvmCache.clearCacheForRemovedClasses(changesCollector)

        // Normalize the changes and clean up
        val dirtyData = changesCollector.getDirtyData(listOf(incrementalJvmCache), EmptyICReporter)
        workingDir.deleteRecursively()

        return ClasspathChanges.Available(
            lookupSymbols = LinkedHashSet(dirtyData.dirtyLookupSymbols),
            fqNames = LinkedHashSet(dirtyData.dirtyClassesFqNames)
        )
    }

    /**
     * Returns all [ClassSnapshot]s in this [ClasspathSnapshot].
     *
     * If there are duplicate classes on the classpath, retain only the first one to match the compiler's behavior.
     */
    private fun ClasspathSnapshot.getNonDuplicateClassSnapshots(): List<ClassSnapshot> {
        val classSnapshots = LinkedHashMap<String, ClassSnapshot>(classpathEntrySnapshots.sumOf { it.classSnapshots.size })
        for (classpathEntrySnapshot in classpathEntrySnapshots) {
            for ((unixStyleRelativePath, classSnapshot) in classpathEntrySnapshot.classSnapshots) {
                classSnapshots.putIfAbsent(unixStyleRelativePath, classSnapshot)
            }
        }
        return classSnapshots.values.toList()
    }
}
