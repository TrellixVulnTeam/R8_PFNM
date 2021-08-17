// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;
import static com.android.tools.r8.utils.AndroidApiLevel.minApiLevelIfEnabledOrUnknown;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.GenericSignature.FieldTypeSignature;
import com.android.tools.r8.graph.InitClassLens;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Visibility;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class ClassInitFieldSynthesizer {

  private final AppView<AppInfoWithLiveness> appView;
  private final DexField clinitField;
  private final InitClassLens.Builder lensBuilder = InitClassLens.builder();

  public ClassInitFieldSynthesizer(AppView<AppInfoWithLiveness> appView) {
    this.appView = appView;
    this.clinitField = appView.dexItemFactory().objectMembers.clinitField;
  }

  public void run(ExecutorService executorService) throws ExecutionException {
    ThreadUtils.processMap(
        appView.appInfo().initClassReferences, this::synthesizeClassInitField, executorService);
    appView.setInitClassLens(lensBuilder.build());
  }

  private void synthesizeClassInitField(DexType type, Visibility minimumRequiredVisibility) {
    DexProgramClass clazz = asProgramClassOrNull(appView.definitionFor(type));
    if (clazz == null) {
      assert false;
      return;
    }
    // Use an existing static field if there is one.
    DexEncodedField encodedClinitField = null;
    for (DexEncodedField staticField : clazz.staticFields()) {
      // Don't consider dead fields.
      if (staticField.getOptimizationInfo().isDead()) {
        continue;
      }
      // We need the field to be accessible from the contexts in which it is accessed.
      if (!isMinimumRequiredVisibility(staticField, minimumRequiredVisibility)) {
        continue;
      }
      // When compiling for dex, we can't use wide fields since we've only allocated a single
      // register for the out-value of each ClassInit instruction
      if (staticField.getReference().type.isWideType()) {
        continue;
      }
      if (encodedClinitField == null) {
        encodedClinitField = staticField;
      } else {
        // Prefer the field that is most visible.
        if (staticField.getAccessFlags().getVisibilityOrdinal()
            > encodedClinitField.getAccessFlags().getVisibilityOrdinal()) {
          encodedClinitField = staticField;
        }
      }
      if (encodedClinitField.isPublic()) {
        break;
      }
    }
    if (encodedClinitField == null) {
      FieldAccessFlags accessFlags =
          FieldAccessFlags.fromSharedAccessFlags(
              Constants.ACC_SYNTHETIC
                  | Constants.ACC_FINAL
                  | Constants.ACC_PUBLIC
                  | Constants.ACC_STATIC);
      boolean deprecated = false;
      encodedClinitField =
          new DexEncodedField(
              appView.dexItemFactory().createField(clazz.type, clinitField.type, clinitField.name),
              accessFlags,
              FieldTypeSignature.noSignature(),
              DexAnnotationSet.empty(),
              null,
              deprecated,
              DexEncodedField.D8_R8_SYNTHESIZED,
              minApiLevelIfEnabledOrUnknown(appView));
      clazz.appendStaticField(encodedClinitField);
    }
    lensBuilder.map(type, encodedClinitField.getReference());
  }

  private boolean isMinimumRequiredVisibility(
      DexEncodedField field, Visibility minimumRequiredVisibility) {
    if (field.isPublic()) {
      return true;
    }
    switch (minimumRequiredVisibility) {
      case PROTECTED:
        return field.isProtected();
      case PACKAGE_PRIVATE:
        return field.isPackagePrivate() || field.isProtected();
      case PUBLIC:
        return false;
      default:
        throw new Unreachable();
    }
  }
}
