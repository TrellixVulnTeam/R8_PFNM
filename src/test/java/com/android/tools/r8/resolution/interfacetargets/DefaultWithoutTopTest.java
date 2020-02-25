// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.resolution.interfacetargets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NeverMerge;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.LookupResult;
import com.android.tools.r8.graph.ResolutionResult;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class DefaultWithoutTopTest extends TestBase {

  private static final String[] EXPECTED = new String[] {"J.foo"};

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public DefaultWithoutTopTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testDynamicLookupTargets() throws Exception {
    assumeTrue(parameters.useRuntimeAsNoneRuntime());
    AppView<AppInfoWithLiveness> appView =
        computeAppViewWithLiveness(
            buildClasses(I.class, J.class, Main.class)
                .addClassProgramData(setAImplementsIAndJ())
                .build(),
            Main.class);
    AppInfoWithLiveness appInfo = appView.appInfo();
    DexMethod method = buildNullaryVoidMethod(I.class, "foo", appInfo.dexItemFactory());
    ResolutionResult resolutionResult = appInfo.resolveMethod(method.holder, method);
    DexProgramClass context =
        appView.definitionForProgramType(buildType(Main.class, appInfo.dexItemFactory()));
    LookupResult lookupResult = resolutionResult.lookupVirtualDispatchTargets(context, appView);
    assertTrue(lookupResult.isLookupResultSuccess());
    Set<String> targets =
        lookupResult.asLookupResultSuccess().getMethodTargets().stream()
            .map(DexEncodedMethod::qualifiedName)
            .collect(Collectors.toSet());
    ImmutableSet<String> expected = ImmutableSet.of(J.class.getTypeName() + ".foo");
    assertEquals(expected, targets);
  }

  @Test
  public void testRuntime() throws IOException, CompilationFailedException, ExecutionException {
    testForRuntime(parameters)
        .addProgramClasses(I.class, J.class, Main.class)
        .addProgramClassFileData(setAImplementsIAndJ())
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(EXPECTED);
  }

  @Test
  public void testR8() throws IOException, CompilationFailedException, ExecutionException {
    testForR8(parameters.getBackend())
        .addProgramClasses(I.class, J.class, Main.class)
        .addProgramClassFileData(setAImplementsIAndJ())
        .addKeepMainRule(Main.class)
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(EXPECTED);
  }

  @Test
  public void testDynamicLookupTargetsWithIndirectDefault() throws Exception {
    assumeTrue(parameters.useRuntimeAsNoneRuntime());
    AppView<AppInfoWithLiveness> appView =
        computeAppViewWithLiveness(
            buildClasses(I.class, J.class, K.class, Main.class)
                .addClassProgramData(setAimplementsIandK())
                .build(),
            Main.class);
    AppInfoWithLiveness appInfo = appView.appInfo();
    DexMethod method = buildNullaryVoidMethod(I.class, "foo", appInfo.dexItemFactory());
    ResolutionResult resolutionResult = appInfo.resolveMethod(method.holder, method);
    DexProgramClass context =
        appView.definitionForProgramType(buildType(Main.class, appInfo.dexItemFactory()));
    LookupResult lookupResult = resolutionResult.lookupVirtualDispatchTargets(context, appView);
    assertTrue(lookupResult.isLookupResultSuccess());
    Set<String> targets =
        lookupResult.asLookupResultSuccess().getMethodTargets().stream()
            .map(DexEncodedMethod::qualifiedName)
            .collect(Collectors.toSet());
    ImmutableSet<String> expected = ImmutableSet.of(J.class.getTypeName() + ".foo");
    assertEquals(expected, targets);
  }

  @Test
  public void testRuntimeWithIndirectDefault()
      throws IOException, CompilationFailedException, ExecutionException {
    testForRuntime(parameters)
        .addProgramClasses(I.class, J.class, K.class, Main.class)
        .addProgramClassFileData(setAimplementsIandK())
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(EXPECTED);
  }

  @Test
  public void testR8WithIndirectDefault()
      throws IOException, CompilationFailedException, ExecutionException {
    testForR8(parameters.getBackend())
        .addProgramClasses(I.class, J.class, K.class, Main.class)
        .addProgramClassFileData(setAimplementsIandK())
        .addKeepMainRule(Main.class)
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines(EXPECTED);
  }

  private byte[] setAImplementsIAndJ() throws IOException {
    return transformer(A.class).setImplements(I.class, J.class).transform();
  }

  private byte[] setAimplementsIandK() throws IOException {
    return transformer(A.class).setImplements(I.class, K.class).transform();
  }

  @NeverMerge
  public interface I {
    void foo();
  }

  @NeverMerge
  public interface J {
    @NeverInline
    default void foo() {
      System.out.println("J.foo");
    }
  }

  public interface K extends J {}

  @NeverClassInline
  public static class A implements J /* I, J or I, K */ {}

  public static class Main {

    public static void main(String[] args) {
      ((I) new A()).foo();
    }
  }
}
