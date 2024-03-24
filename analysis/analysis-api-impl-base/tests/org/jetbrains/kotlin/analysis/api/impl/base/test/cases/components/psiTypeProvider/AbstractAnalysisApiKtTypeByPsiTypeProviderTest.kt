/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.components.psiTypeProvider

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiBasedTest
import org.jetbrains.kotlin.analysis.test.framework.project.structure.KtTestModule
import org.jetbrains.kotlin.analysis.test.framework.project.structure.ktTestModuleStructure
import org.jetbrains.kotlin.analysis.test.framework.services.expressionMarkerProvider
import org.jetbrains.kotlin.analysis.test.framework.utils.executeOnPooledThreadInReadAction
import org.jetbrains.kotlin.analysis.utils.printer.parentOfType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.types.Variance

abstract class AbstractAnalysisApiKtTypeByPsiTypeProviderTest : AbstractAnalysisApiBasedTest() {
    override fun doTestByMainFile(mainFile: KtFile, mainModule: KtTestModule, testServices: TestServices) {
        val (psiMethod, useSitePosition) = getTestDataContext(testServices)

        val actual = buildString {
            executeOnPooledThreadInReadAction {
                analyseForTest(mainFile) {
                    val psiType = psiMethod.returnType
                    testServices.assertions.assertNotNull(psiType)
                    val ktType = psiType!!.asKtType(useSitePosition ?: psiMethod)!!
                    appendLine("PsiType: ${AnalysisApiPsiTypeProviderTestUtils.render(psiType)}")
                    appendLine("KtType: ${AnalysisApiPsiTypeProviderTestUtils.render(ktType, Variance.OUT_VARIANCE)}")
                }
            }
        }

        testServices.assertions.assertEqualsToTestDataFileSibling(actual)
    }
}

private data class TestDataContext(val targetMethod: PsiMethod, val useSitePosition: PsiElement?)

private fun getTestDataContext(testServices: TestServices): TestDataContext {
    var psiMethod: PsiMethod? = null
    var useSitePosition: PsiElement? = null

    testServices.ktTestModuleStructure.mainModules.forEach { ktTestModule ->
        val psiFiles = ktTestModule.files
        for (psiFile in psiFiles) {
            val targetOffset = testServices.expressionMarkerProvider.getCaretPositionOrNull(psiFile)
            if (targetOffset != null) {
                if (psiMethod != null) error("Only one target method is expected")
                psiMethod = psiFile.findElementAt(targetOffset)?.parentOfType<PsiMethod>(withSelf = true)
            }

            val useSiteOffset = testServices.expressionMarkerProvider.getCaretPositionOrNull(psiFile, caretTag = "useSite")
            if (useSiteOffset != null) {
                if (useSitePosition != null) error("Only one target method is expected")
                useSitePosition = psiFile.findElementAt(useSiteOffset)?.parentOfType<PsiElement>(withSelf = true)
            }
        }
    }

    return TestDataContext(psiMethod ?: error("Target method is not found"), useSitePosition)
}
