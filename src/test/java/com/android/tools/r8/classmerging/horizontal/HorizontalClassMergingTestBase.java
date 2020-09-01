// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.classmerging.horizontal;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.BooleanUtils;
import java.util.List;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class HorizontalClassMergingTestBase extends TestBase {
  protected final TestParameters parameters;
  protected final boolean enableHorizontalClassMerging;

  protected HorizontalClassMergingTestBase(
      TestParameters parameters, boolean enableHorizontalClassMerging) {
    this.parameters = parameters;
    this.enableHorizontalClassMerging = enableHorizontalClassMerging;
  }

  @Parameterized.Parameters(name = "{0}, horizontalClassMerging:{1}")
  public static List<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimesAndApiLevels().build(), BooleanUtils.values());
  }
}
