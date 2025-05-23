// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.uast.kotlin

import com.intellij.lang.Language
import com.intellij.openapi.extensions.InternalIgnoreDependencyViolation
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.annotations.JVM_STATIC_ANNOTATION_FQ_NAME
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.uast.*
import org.jetbrains.uast.analysis.UastAnalysisPlugin
import org.jetbrains.uast.kotlin.KotlinConverter.convertDeclaration
import org.jetbrains.uast.kotlin.KotlinConverter.convertDeclarationOrElement
import org.jetbrains.uast.kotlin.psi.UastFakeSourceLightPrimaryConstructor
import org.jetbrains.uast.util.ClassSet
import org.jetbrains.uast.util.ClassSetsWrapper

@InternalIgnoreDependencyViolation
class KotlinUastLanguagePlugin : UastLanguagePlugin {
    override val priority = 10

    override val language: Language
        get() = KotlinLanguage.INSTANCE

    override fun isFileSupported(fileName: String): Boolean {
        return fileName.endsWith(".kt", false) || fileName.endsWith(".kts", false)
    }

    private val PsiElement.isJvmOrCommonElement: Boolean
        get() {
            val resolveProvider: KotlinUastResolveProviderService = project.getService(KotlinUastResolveProviderService::class.java)!!
            return resolveProvider.isJvmOrCommonElement(this)
        }

    override fun convertElement(element: PsiElement, parent: UElement?, requiredType: Class<out UElement>?): UElement? {
        val requiredTypes = elementTypes(requiredType)
        return if (!canConvert(element, requiredTypes) || !element.isJvmOrCommonElement) null
        else convertDeclarationOrElement(element, parent, requiredTypes)
    }

    override fun convertElementWithParent(element: PsiElement, requiredType: Class<out UElement>?): UElement? {
        val requiredTypes = elementTypes(requiredType)
        return when {
            !canConvert(element, requiredTypes) || !element.isJvmOrCommonElement -> null
            element is PsiFile || element is KtLightClassForFacade -> convertDeclaration(element, null, requiredTypes)
            else -> convertDeclarationOrElement(element, null, requiredTypes)
        }
    }

    override fun getMethodCallExpression(
        element: PsiElement,
        containingClassFqName: String?,
        methodName: String
    ): UastLanguagePlugin.ResolvedMethod? {
        if (element !is KtCallExpression) return null
        val resolvedCall = element.getResolvedCall(element.analyze()) ?: return null
        val resultingDescriptor = resolvedCall.resultingDescriptor
        if (resultingDescriptor !is FunctionDescriptor || resultingDescriptor.name.asString() != methodName) return null

        val parent = element.parent
        val parentUElement = convertElementWithParent(parent, null) ?: return null

        val uExpression = KotlinUFunctionCallExpression(element, parentUElement)
        val method = uExpression.resolve() ?: return null
        if (method.name != methodName) return null
        return UastLanguagePlugin.ResolvedMethod(uExpression, method)
    }

    override fun getConstructorCallExpression(
        element: PsiElement,
        fqName: String
    ): UastLanguagePlugin.ResolvedConstructor? {
        if (element !is KtCallExpression) return null
        val resolvedCall = element.getResolvedCall(element.analyze()) ?: return null
        val resultingDescriptor = resolvedCall.resultingDescriptor
        if (resultingDescriptor !is ConstructorDescriptor
            || resultingDescriptor.returnType.constructor.declarationDescriptor?.name?.asString() != fqName
        ) {
            return null
        }

        val parent = KotlinConverter.unwrapElements(element.parent) ?: return null
        val parentUElement = convertElementWithParent(parent, null) ?: return null

        val uExpression = KotlinUFunctionCallExpression(element, parentUElement)
        val method = uExpression.resolve() ?: return null
        val containingClass = method.containingClass ?: return null
        return UastLanguagePlugin.ResolvedConstructor(uExpression, method, containingClass)
    }

    override fun isExpressionValueUsed(element: UExpression): Boolean {
        return when (element) {
            is KotlinUSimpleReferenceExpression.KotlinAccessorCallExpression -> element.setterValue != null
            is KotlinAbstractUExpression -> {
                val ktElement = element.sourcePsi as? KtElement ?: return false
                ktElement.analyze()[BindingContext.USED_AS_EXPRESSION, ktElement] ?: false
            }
            else -> false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : UElement> convertElement(element: PsiElement, parent: UElement?, expectedTypes: Array<out Class<out T>>): T? {
        val nonEmptyExpectedTypes = expectedTypes.nonEmptyOr(DEFAULT_TYPES_LIST)
        return if (!canConvert(element, nonEmptyExpectedTypes) || !element.isJvmOrCommonElement) null
        else convertDeclarationOrElement(element, parent, nonEmptyExpectedTypes) as? T
    }

    override fun <T : UElement> convertElementWithParent(element: PsiElement, requiredTypes: Array<out Class<out T>>): T? {
        return convertElement(element, null, requiredTypes)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : UElement> convertToAlternatives(element: PsiElement, requiredTypes: Array<out Class<out T>>): Sequence<T> =
        if (!element.isJvmOrCommonElement) emptySequence() else when {
            element is KtFile -> KotlinConverter.convertKtFile(element, null, requiredTypes) as Sequence<T>
            (element is KtProperty && !element.isLocal) ->
                KotlinConverter.convertNonLocalProperty(element, null, requiredTypes) as Sequence<T>
            element is KtNamedFunction && element.isJvmStatic() ->
                KotlinConverter.convertJvmStaticMethod(element, null, requiredTypes) as Sequence<T>
            element is KtParameter -> KotlinConverter.convertParameter(element, null, requiredTypes) as Sequence<T>
            element is KtClassOrObject -> KotlinConverter.convertClassOrObject(element, null, requiredTypes) as Sequence<T>
            element is UastFakeSourceLightPrimaryConstructor ->
                KotlinConverter.convertFakeLightConstructorAlternatives(element, null, requiredTypes) as Sequence<T>
            else -> sequenceOf(convertElementWithParent(element, requiredTypes.nonEmptyOr(DEFAULT_TYPES_LIST)) as? T).filterNotNull()
        }

    private fun KtNamedFunction.isJvmStatic() = annotationEntries.any {
        it.shortName?.asString() == JVM_STATIC_ANNOTATION_FQ_NAME.shortName().asString()
                && analyze()[BindingContext.ANNOTATION, it]?.fqName == JVM_STATIC_ANNOTATION_FQ_NAME
    }

    override fun getPossiblePsiSourceTypes(vararg uastTypes: Class<out UElement>): ClassSet<PsiElement> =
        when (uastTypes.size) {
            0 -> getPossibleSourceTypes(UElement::class.java)
            1 -> getPossibleSourceTypes(uastTypes.single())
            else -> ClassSetsWrapper(Array(uastTypes.size) { getPossibleSourceTypes(uastTypes[it]) })
        }

    override val analysisPlugin: UastAnalysisPlugin?
        get() = UastAnalysisPlugin.byLanguage(KotlinLanguage.INSTANCE)

    override fun getContainingAnnotationEntry(uElement: UElement?, annotationsHint: Collection<String>): Pair<UAnnotation, String?>? {
        val sourcePsi = uElement?.sourcePsi ?: return null

        val parent = sourcePsi.parent ?: return null
        if (parent is KtAnnotationEntry) {
            if (!isOneOfNames(parent, annotationsHint)) return null

            return super.getContainingAnnotationEntry(uElement, annotationsHint)
        }

        val annotationEntry = parent.getParentOfType<KtAnnotationEntry>(true, KtDeclaration::class.java)
        if (annotationEntry == null) return null

        if (!isOneOfNames(annotationEntry, annotationsHint)) return null

        return super.getContainingAnnotationEntry(uElement, annotationsHint)
    }

    private fun isOneOfNames(annotationEntry: KtAnnotationEntry, annotations: Collection<String>): Boolean {
        if (annotations.isEmpty()) return true
        val shortName = annotationEntry.shortName?.identifier ?: return false

        for (annotation in annotations) {
            if (StringUtil.getShortName(annotation) == shortName) {
                return true
            }
        }
        return false
    }
}
