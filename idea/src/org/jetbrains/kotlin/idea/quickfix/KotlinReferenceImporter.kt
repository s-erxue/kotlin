/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.ReferenceImporter
import com.intellij.codeInsight.daemon.impl.DaemonListeners
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.actions.KotlinAddImportAction
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.targetDescriptors
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.JetFile
import org.jetbrains.kotlin.psi.JetSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.elementsInRange
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

public class KotlinReferenceImporter : ReferenceImporter {
    override fun autoImportReferenceAtCursor(editor: Editor, file: PsiFile)
            = autoImportReferenceAtCursor(editor, file, allowCaretNearRef = false)

    override fun autoImportReferenceAt(editor: Editor, file: PsiFile, offset: Int): Boolean {
        if (file !is JetFile) return false

        val nameExpression = file.findElementAt(offset)?.parent as? JetSimpleNameExpression ?: return false

        return nameExpression.autoImport(editor, file)
    }

    companion object {

        // TODO: use in table cell
        // TODO: DependencyValidationManager
        // TODO: filter out non-top level
        public fun autoImportReferenceAtCursor(editor: Editor, file: PsiFile, allowCaretNearRef: Boolean): Boolean {
            if (file !is JetFile) return false

            val caretOffset = editor.caretModel.offset
            val document = editor.document
            val lineNumber = document.getLineNumber(caretOffset)
            val startOffset = document.getLineStartOffset(lineNumber)
            val endOffset = document.getLineEndOffset(lineNumber)

            val elements = file.elementsInRange(TextRange(startOffset, endOffset))
                    .flatMap { it.collectDescendantsOfType<JetSimpleNameExpression>() }
            for (element in elements) {
                if (!allowCaretNearRef && element.endOffset == caretOffset) continue

                if (element.autoImport(editor, file)) {
                    return true
                }
            }

            return false
        }

        protected fun hasUnresolvedImportWhichCanImport(file: JetFile, name: String): Boolean {
            return file.importDirectives.any {
                it.targetDescriptors().isEmpty() && (it.isAllUnder || it.importPath?.importedName?.asString() == name)
            }
        }

        private fun JetSimpleNameExpression.autoImport(editor: Editor, file: JetFile): Boolean {
            if (!CodeInsightSettings.getInstance().ADD_UNAMBIGIOUS_IMPORTS_ON_THE_FLY) return false
            if (!DaemonListeners.canChangeFileSilently(file)) return false
            if (hasUnresolvedImportWhichCanImport(file, getReferencedName())) return false

            val bindingContext = analyze(BodyResolveMode.PARTIAL)
            if (mainReference.resolveToDescriptors(bindingContext).isNotEmpty()) return false

            val suggestions = AutoImportFix.computeSuggestions(this)

            if (suggestions.distinctBy { it.importableFqName!! }.size() != 1) return false

            var result = false
            CommandProcessor.getInstance().runUndoTransparentAction {
                result = KotlinAddImportAction(project, editor, this, suggestions).execute()
            }
            return result
        }
    }
}