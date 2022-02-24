// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.apimodel;

import static com.android.tools.r8.apimodel.ApiModelingTestHelper.setMockApiLevelForClass;
import static com.android.tools.r8.apimodel.ApiModelingTestHelper.setMockApiLevelForMethod;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.D8TestCompileResult;
import com.android.tools.r8.SingleTestRunResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ToolHelper.DexVm.Version;
import com.android.tools.r8.testing.AndroidBuildVersion;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.codeinspector.FoundClassSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ApiModelOutlinePackagePrivateTest extends TestBase {

  private static final AndroidApiLevel classApiLevel = AndroidApiLevel.G;
  private static final AndroidApiLevel methodApiLevel = AndroidApiLevel.G_MR1;

  @Parameter public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  private boolean willInvokeLibraryMethods() {
    return parameters.isDexRuntime()
        && parameters.getApiLevel().isGreaterThanOrEqualTo(classApiLevel);
  }

  @Test
  public void testD8BootClassPath() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    assumeTrue(parameters.getDexRuntimeVersion().isOlderThan(Version.V12_0_0));
    compileOnD8()
        .addBootClasspathClasses(LibraryClass.class)
        .run(parameters.getRuntime(), Main.class)
        .apply(this::checkResultOnBootClassPath);
  }

  @Test
  public void testD8RuntimeClasspath() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    compileOnD8()
        .addRunClasspathClasses(LibraryClass.class)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLinesIf(!willInvokeLibraryMethods(), "Not calling API")
        .assertSuccessWithOutputLinesIf(willInvokeLibraryMethods(), "LibraryClass::addedOn10");
  }

  private D8TestCompileResult compileOnD8() throws Exception {
    return testForD8(parameters.getBackend())
        .addLibraryClasses(LibraryClass.class)
        .addProgramClasses(Main.class)
        .addAndroidBuildVersion()
        .setMinApi(parameters.getApiLevel())
        .compile();
  }

  @Test
  public void testR8() throws Exception {
    // TODO(b/197078995): Make this work on 12+.
    assumeFalse(
        parameters.isDexRuntime()
            && parameters.getDexRuntimeVersion().isNewerThanOrEqual(Version.V12_0_0));
    testForR8(parameters.getBackend())
        .addLibraryClasses(LibraryClass.class)
        .addDefaultRuntimeLibrary(parameters)
        .addProgramClasses(Main.class)
        .addKeepMainRule(Main.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepAttributeInnerClassesAndEnclosingMethod()
        .addAndroidBuildVersion()
        .apply(setMockApiLevelForClass(LibraryClass.class, classApiLevel))
        .apply(
            setMockApiLevelForMethod(
                LibraryClass.class.getDeclaredMethod("addedOn10"), methodApiLevel))
        .apply(ApiModelingTestHelper::enableOutliningOfMethods)
        .apply(ApiModelingTestHelper::disableStubbingOfClasses)
        .compile()
        .inspect(
            inspector -> {
              // Assert that we did not outline any methods.
              assertEquals(
                  0,
                  inspector.allClasses().stream()
                      .filter(FoundClassSubject::isCompilerSynthesized)
                      .count());
            })
        .applyIf(willInvokeLibraryMethods(), b -> b.addBootClasspathClasses(LibraryClass.class))
        .run(parameters.getRuntime(), Main.class)
        .apply(this::checkResultOnBootClassPath);
  }

  private void checkResultOnBootClassPath(SingleTestRunResult<?> runResult) {
    runResult
        .assertSuccessWithOutputLinesIf(!willInvokeLibraryMethods(), "Not calling API")
        // We are expecting an IllegalAccessError since LibraryClass is on bootclasspath and
        // for Main to call the package private method it has to be loaded by the same class loader.
        .assertFailureWithErrorThatThrowsIf(willInvokeLibraryMethods(), IllegalAccessError.class);
  }

  // Only present from api level 9.
  public static class LibraryClass {

    static void addedOn10() {
      System.out.println("LibraryClass::addedOn10");
    }
  }

  public static class Main {

    public static void main(String[] args) {
      if (AndroidBuildVersion.VERSION >= 10) {
        LibraryClass.addedOn10();
      } else {
        System.out.println("Not calling API");
      }
    }
  }
}