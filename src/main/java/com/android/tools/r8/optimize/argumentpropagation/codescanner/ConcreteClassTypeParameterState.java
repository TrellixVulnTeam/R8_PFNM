// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.optimize.argumentpropagation.codescanner;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.analysis.type.DynamicType;
import com.android.tools.r8.ir.analysis.type.Nullability;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.Action;
import com.android.tools.r8.utils.SetUtils;
import java.util.Collections;
import java.util.Set;

public class ConcreteClassTypeParameterState extends ConcreteReferenceTypeParameterState {

  private AbstractValue abstractValue;
  private DynamicType dynamicType;

  public ConcreteClassTypeParameterState(MethodParameter inParameter) {
    this(AbstractValue.bottom(), DynamicType.bottom(), SetUtils.newHashSet(inParameter));
  }

  public ConcreteClassTypeParameterState(AbstractValue abstractValue, DynamicType dynamicType) {
    this(abstractValue, dynamicType, Collections.emptySet());
  }

  public ConcreteClassTypeParameterState(
      AbstractValue abstractValue, DynamicType dynamicType, Set<MethodParameter> inParameters) {
    super(inParameters);
    this.abstractValue = abstractValue;
    this.dynamicType = dynamicType;
    assert !isEffectivelyBottom() : "Must use BottomClassTypeParameterState instead";
    assert !isEffectivelyUnknown() : "Must use UnknownParameterState instead";
  }

  @Override
  public ParameterState clearInParameters() {
    if (hasInParameters()) {
      if (abstractValue.isBottom()) {
        assert dynamicType.isBottom();
        return bottomClassTypeParameter();
      }
      internalClearInParameters();
    }
    assert !isEffectivelyBottom();
    return this;
  }

  @Override
  public AbstractValue getAbstractValue(AppView<AppInfoWithLiveness> appView) {
    return abstractValue;
  }

  @Override
  public DynamicType getDynamicType() {
    return dynamicType;
  }

  @Override
  public Nullability getNullability() {
    return getDynamicType().getDynamicUpperBoundType().nullability();
  }

  @Override
  public ConcreteParameterStateKind getKind() {
    return ConcreteParameterStateKind.CLASS;
  }

  @Override
  public boolean isClassParameter() {
    return true;
  }

  @Override
  public ConcreteClassTypeParameterState asClassParameter() {
    return this;
  }

  public boolean isEffectivelyBottom() {
    return abstractValue.isBottom() && dynamicType.isBottom() && !hasInParameters();
  }

  public boolean isEffectivelyUnknown() {
    return abstractValue.isUnknown() && dynamicType.isUnknown();
  }

  @Override
  public ParameterState mutableCopy() {
    return new ConcreteClassTypeParameterState(abstractValue, dynamicType, copyInParameters());
  }

  @Override
  public ParameterState mutableJoin(
      AppView<AppInfoWithLiveness> appView,
      ConcreteReferenceTypeParameterState parameterState,
      Action onChangedAction) {
    boolean allowNullOrAbstractValue = true;
    boolean allowNonConstantNumbers = false;
    AbstractValue oldAbstractValue = abstractValue;
    abstractValue =
        abstractValue.join(
            parameterState.getAbstractValue(appView),
            appView.abstractValueFactory(),
            allowNullOrAbstractValue,
            allowNonConstantNumbers);
    // TODO(b/190154391): Take in the static type as an argument, and unset the dynamic type if it
    //  equals the static type.
    DynamicType oldDynamicType = dynamicType;
    dynamicType = dynamicType.join(appView, parameterState.getDynamicType());
    if (abstractValue.isUnknown() && dynamicType.isUnknown()) {
      return unknown();
    }
    boolean inParametersChanged = mutableJoinInParameters(parameterState);
    if (widenInParameters()) {
      return unknown();
    }
    if (abstractValue != oldAbstractValue
        || !dynamicType.equals(oldDynamicType)
        || inParametersChanged) {
      onChangedAction.execute();
    }
    return this;
  }
}
