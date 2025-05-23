// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.siyeh.ig;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Iconable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * @deprecated Should not be used, as the whole {@link InspectionGadgetsFix} hierarchy. Use {@link LocalQuickFix} directly.
 */
@Deprecated
public class DelegatingFix extends InspectionGadgetsFix implements Iconable, PriorityAction {

  protected final LocalQuickFix delegate;

  public DelegatingFix(LocalQuickFix delegate) {
    this.delegate = delegate;
  }

  @Override
  public @NotNull String getName() {
    return delegate.getName();
  }

  @Override
  public @NotNull String getFamilyName() {
    return delegate.getFamilyName();
  }

  @Override
  protected void doFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
    delegate.applyFix(project, descriptor);
  }

  @Override
  public boolean startInWriteAction() {
    return delegate.startInWriteAction();
  }

  @Override
  public boolean availableInBatchMode() {
    return delegate.availableInBatchMode();
  }

  @Override
  public Icon getIcon(int flags) {
    return delegate instanceof Iconable ? ((Iconable)delegate).getIcon(flags) : null;
  }

  @Override
  public @NotNull Priority getPriority() {
    return delegate instanceof PriorityAction ? ((PriorityAction)delegate).getPriority() : Priority.NORMAL;
  }

  @Override
  public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project,
                                                       @NotNull ProblemDescriptor previewDescriptor) {
    return delegate.generatePreview(project, previewDescriptor);
  }
}