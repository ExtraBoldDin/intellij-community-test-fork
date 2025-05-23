// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diff.fragments;

import com.intellij.openapi.diagnostic.LoggerRt;
import org.jetbrains.annotations.NonNls;

public class DiffFragmentImpl implements DiffFragment {
  private static final LoggerRt LOG = LoggerRt.getInstance(DiffFragmentImpl.class);

  private final int myStartOffset1;
  private final int myEndOffset1;
  private final int myStartOffset2;
  private final int myEndOffset2;

  public DiffFragmentImpl(int startOffset1,
                          int endOffset1,
                          int startOffset2,
                          int endOffset2) {
    myStartOffset1 = startOffset1;
    myEndOffset1 = endOffset1;
    myStartOffset2 = startOffset2;
    myEndOffset2 = endOffset2;

    if (myStartOffset1 == myEndOffset1 &&
        myStartOffset2 == myEndOffset2) {
      LOG.error("DiffFragmentImpl should not be empty: " + toString());
    }
    if (myStartOffset1 > myEndOffset1 ||
        myStartOffset2 > myEndOffset2) {
      LOG.error("DiffFragmentImpl is invalid: " + toString());
    }
  }

  @Override
  public int getStartOffset1() {
    return myStartOffset1;
  }

  @Override
  public int getEndOffset1() {
    return myEndOffset1;
  }

  @Override
  public int getStartOffset2() {
    return myStartOffset2;
  }

  @Override
  public int getEndOffset2() {
    return myEndOffset2;
  }

  @Override
  public final boolean equals(Object o) {
    if (!(o instanceof DiffFragmentImpl)) return false;

    DiffFragmentImpl fragment = (DiffFragmentImpl)o;
    return myStartOffset1 == fragment.myStartOffset1 &&
           myEndOffset1 == fragment.myEndOffset1 &&
           myStartOffset2 == fragment.myStartOffset2 &&
           myEndOffset2 == fragment.myEndOffset2;
  }

  @Override
  public int hashCode() {
    int result = myStartOffset1;
    result = 31 * result + myEndOffset1;
    result = 31 * result + myStartOffset2;
    result = 31 * result + myEndOffset2;
    return result;
  }

  @Override
  public @NonNls String toString() {
    return "DiffFragmentImpl [" + myStartOffset1 + ", " + myEndOffset1 + ") - [" + myStartOffset2 + ", " + myEndOffset2 + ")";
  }
}
