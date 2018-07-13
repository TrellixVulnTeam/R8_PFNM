// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils.dexinspector;

import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.naming.MemberNaming.Signature;

public class AbsentFieldSubject extends FieldSubject {

  @Override
  public boolean isPublic() {
    return false;
  }

  @Override
  public boolean isStatic() {
    return false;
  }

  @Override
  public boolean isFinal() {
    return false;
  }

  @Override
  public boolean isPresent() {
    return false;
  }

  @Override
  public boolean isRenamed() {
    return false;
  }

  @Override
  public Signature getOriginalSignature() {
    return null;
  }

  @Override
  public Signature getFinalSignature() {
    return null;
  }

  @Override
  public boolean hasExplicitStaticValue() {
    return false;
  }

  @Override
  public DexValue getStaticValue() {
    return null;
  }

  @Override
  public DexEncodedField getField() {
    return null;
  }

  @Override
  public String getOriginalSignatureAttribute() {
    return null;
  }

  @Override
  public String getFinalSignatureAttribute() {
    return null;
  }
}
