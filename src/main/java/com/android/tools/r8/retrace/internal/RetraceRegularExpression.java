// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace.internal;

import static com.android.tools.r8.retrace.internal.RetraceUtils.methodDescriptionFromRetraceMethod;

import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.retrace.RetraceClassResult;
import com.android.tools.r8.retrace.RetraceFieldResult;
import com.android.tools.r8.retrace.RetraceFrameResult;
import com.android.tools.r8.retrace.RetraceFrameResult.Element;
import com.android.tools.r8.retrace.RetraceSourceFileResult;
import com.android.tools.r8.retrace.RetracedClass;
import com.android.tools.r8.retrace.RetracedField;
import com.android.tools.r8.retrace.RetracedField.KnownRetracedField;
import com.android.tools.r8.retrace.RetracedMethod;
import com.android.tools.r8.retrace.internal.RetraceRegularExpression.ClassNameGroup.ClassNameGroupHandler;
import com.android.tools.r8.utils.Box;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.StringUtils;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RetraceRegularExpression {

  private final RetracerImpl retracer;
  private final List<String> stackTrace;
  private final DiagnosticsHandler diagnosticsHandler;
  private final String regularExpression;

  private static final int NO_MATCH = -1;

  private final SourceFileLineNumberGroup sourceFileLineNumberGroup =
      new SourceFileLineNumberGroup();
  private final TypeNameGroup typeNameGroup = new TypeNameGroup();
  private final BinaryNameGroup binaryNameGroup = new BinaryNameGroup();
  private final SourceFileGroup sourceFileGroup = new SourceFileGroup();
  private final LineNumberGroup lineNumberGroup = new LineNumberGroup();
  private final FieldOrReturnTypeGroup fieldOrReturnTypeGroup = new FieldOrReturnTypeGroup();
  private final MethodArgumentsGroup methodArgumentsGroup = new MethodArgumentsGroup();

  private final MethodNameGroup methodNameGroup;
  private final FieldNameGroup fieldNameGroup;

  private static final String CAPTURE_GROUP_PREFIX = "captureGroup";
  private static final int FIRST_CAPTURE_GROUP_INDEX = 0;

  public RetraceRegularExpression(
      RetracerImpl retracer,
      List<String> stackTrace,
      DiagnosticsHandler diagnosticsHandler,
      String regularExpression,
      boolean isVerbose) {
    this.retracer = retracer;
    this.stackTrace = stackTrace;
    this.diagnosticsHandler = diagnosticsHandler;
    this.regularExpression = regularExpression;
    methodNameGroup = new MethodNameGroup(isVerbose);
    fieldNameGroup = new FieldNameGroup(isVerbose);
  }

  public RetraceCommandLineResult retrace() {
    List<RegularExpressionGroupHandler> handlers = new ArrayList<>();
    StringBuilder refinedRegularExpressionBuilder = new StringBuilder();
    registerGroups(
        this.regularExpression,
        refinedRegularExpressionBuilder,
        handlers,
        FIRST_CAPTURE_GROUP_INDEX);
    String refinedRegularExpression = refinedRegularExpressionBuilder.toString();
    Pattern compiledPattern = Pattern.compile(refinedRegularExpression);
    List<String> result = new ArrayList<>();
    for (String string : stackTrace) {
      Matcher matcher = compiledPattern.matcher(string);
      if (!matcher.matches()) {
        result.add(string);
        continue;
      }
      // Iterate through handlers to set contexts. That will allow us to process all handlers from
      // left to right.
      RetraceStringContext initialContext = RetraceStringContext.empty();
      for (RegularExpressionGroupHandler handler : handlers) {
        initialContext = handler.buildInitial(initialContext, matcher, retracer);
      }
      final RetraceString initialRetraceString = RetraceString.start(initialContext);
      List<RetraceString> retracedStrings = Lists.newArrayList(initialRetraceString);
      for (RegularExpressionGroupHandler handler : handlers) {
        retracedStrings = handler.handleMatch(string, retracedStrings, matcher, retracer);
      }
      if (retracedStrings.isEmpty()) {
        // We could not find a match. Output the identity.
        result.add(string);
      }
      boolean isAmbiguous = retracedStrings.size() > 1 && retracedStrings.get(0).isAmbiguous();
      if (isAmbiguous) {
        retracedStrings.sort(new RetraceLineComparator());
      }
      RetracedClass previousContext = null;
      for (RetraceString retracedString : retracedStrings) {
        String finalString = retracedString.builder.build(string);
        if (!isAmbiguous) {
          result.add(finalString);
          continue;
        }
        assert retracedString.getClassContext() != null;
        RetracedClass currentContext = retracedString.getClassContext().getRetracedClass();
        if (currentContext.equals(previousContext)) {
          int firstNonWhitespaceCharacter = StringUtils.firstNonWhitespaceCharacter(finalString);
          finalString =
              finalString.substring(0, firstNonWhitespaceCharacter)
                  + "<OR> "
                  + finalString.substring(firstNonWhitespaceCharacter);
        }
        previousContext = currentContext;
        result.add(finalString);
      }
    }
    return new RetraceCommandLineResult(result);
  }

  static class RetraceLineComparator extends AmbiguousComparator<RetraceString> {

    RetraceLineComparator() {
      super(
          (line, t) -> {
            switch (t) {
              case CLASS:
                return line.getClassContext().getRetracedClass().getTypeName();
              case METHOD:
                return line.getMethodContext().getTopFrame().getMethodName();
              case SOURCE:
                return line.getSource();
              case LINE:
                return line.getLineNumber() + "";
              default:
                assert false;
            }
            throw new RuntimeException("Comparator key is unknown");
          });
    }
  }

  private int registerGroups(
      String regularExpression,
      StringBuilder refinedRegularExpression,
      List<RegularExpressionGroupHandler> handlers,
      int captureGroupIndex) {
    int lastCommittedIndex = 0;
    RegularExpressionGroupHandler lastHandler = null;
    boolean seenPercentage = false;
    boolean escaped = false;
    for (int i = 0; i < regularExpression.length(); i++) {
      if (seenPercentage) {
        assert !escaped;
        final RegularExpressionGroup group = getGroupFromVariable(regularExpression.charAt(i));
        refinedRegularExpression.append(regularExpression, lastCommittedIndex, i - 1);
        if (group.isSynthetic()) {
          captureGroupIndex =
              registerGroups(
                  group.subExpression(), refinedRegularExpression, handlers, captureGroupIndex);
        } else {
          String captureGroupName = CAPTURE_GROUP_PREFIX + (captureGroupIndex++);
          refinedRegularExpression
              .append("(?<")
              .append(captureGroupName)
              .append(">")
              .append(group.subExpression())
              .append(")");
          final RegularExpressionGroupHandler handler = group.createHandler(captureGroupName);
          // If we see a pattern as %c.%m or %C/%m, then register the groups to allow delaying
          // writing of the class string until we have the fully qualified member.
          if (lastHandler != null
              && handler.isQualifiedHandler()
              && lastHandler.isClassNameGroupHandler()
              && lastCommittedIndex == i - 3
              && isTypeOrBinarySeparator(regularExpression, lastCommittedIndex, i - 2)) {
            final ClassNameGroupHandler classNameGroupHandler =
                lastHandler.asClassNameGroupHandler();
            final QualifiedRegularExpressionGroupHandler qualifiedHandler =
                handler.asQualifiedHandler();
            classNameGroupHandler.setQualifiedHandler(qualifiedHandler);
            qualifiedHandler.setClassNameGroupHandler(classNameGroupHandler);
          }
          lastHandler = handler;
          handlers.add(handler);
        }
        lastCommittedIndex = i + 1;
        seenPercentage = false;
      } else {
        seenPercentage = !escaped && regularExpression.charAt(i) == '%';
        escaped = !escaped && regularExpression.charAt(i) == '\\';
      }
    }
    refinedRegularExpression.append(
        regularExpression, lastCommittedIndex, regularExpression.length());
    return captureGroupIndex;
  }

  private boolean isTypeOrBinarySeparator(String regularExpression, int startIndex, int endIndex) {
    assert endIndex < regularExpression.length();
    if (startIndex + 1 != endIndex) {
      return false;
    }
    if (regularExpression.charAt(startIndex) != '\\') {
      return false;
    }
    return regularExpression.charAt(startIndex + 1) == '.'
        || regularExpression.charAt(startIndex + 1) == '/';
  }

  private RegularExpressionGroup getGroupFromVariable(char variable) {
    switch (variable) {
      case 'c':
        return typeNameGroup;
      case 'C':
        return binaryNameGroup;
      case 'm':
        return methodNameGroup;
      case 'f':
        return fieldNameGroup;
      case 's':
        return sourceFileGroup;
      case 'l':
        return lineNumberGroup;
      case 'S':
        return sourceFileLineNumberGroup;
      case 't':
        return fieldOrReturnTypeGroup;
      case 'a':
        return methodArgumentsGroup;
      default:
        throw new Unreachable("Unexpected variable: " + variable);
    }
  }

  static class RetraceStringContext {
    private final RetraceClassResult.Element classContext;
    private final RetraceFrameResult.Element methodContext;
    private final RetracedClass qualifiedClassContext;
    private final RetracedMethod qualifiedMethodContext;
    private final String methodName;
    private final int minifiedLineNumber;
    private final int originalLineNumber;
    private final String source;
    private final boolean isAmbiguous;

    private RetraceStringContext(
        RetraceClassResult.Element classContext,
        RetraceFrameResult.Element methodContext,
        RetracedClass qualifiedClassContext,
        RetracedMethod qualifiedMethodContext,
        String methodName,
        int minifiedLineNumber,
        int originalLineNumber,
        String source,
        boolean isAmbiguous) {
      this.classContext = classContext;
      this.methodContext = methodContext;
      this.qualifiedClassContext = qualifiedClassContext;
      this.qualifiedMethodContext = qualifiedMethodContext;
      this.methodName = methodName;
      this.minifiedLineNumber = minifiedLineNumber;
      this.originalLineNumber = originalLineNumber;
      this.source = source;
      this.isAmbiguous = isAmbiguous;
    }

    private static RetraceStringContext empty() {
      return new RetraceStringContext(
          null, null, null, null, null, NO_MATCH, NO_MATCH, null, false);
    }

    private RetraceStringContext withClassContext(
        RetraceClassResult.Element classContext, RetracedClass qualifiedContext) {
      return new RetraceStringContext(
          classContext,
          methodContext,
          qualifiedContext,
          qualifiedMethodContext,
          methodName,
          minifiedLineNumber,
          originalLineNumber,
          source,
          isAmbiguous);
    }

    private RetraceStringContext withMethodName(String methodName) {
      return new RetraceStringContext(
          classContext,
          methodContext,
          qualifiedClassContext,
          qualifiedMethodContext,
          methodName,
          minifiedLineNumber,
          originalLineNumber,
          source,
          isAmbiguous);
    }

    private RetraceStringContext withMethodContext(Element methodContext, boolean isAmbiguous) {
      return new RetraceStringContext(
          classContext,
          methodContext,
          qualifiedClassContext,
          qualifiedMethodContext,
          methodName,
          minifiedLineNumber,
          originalLineNumber,
          source,
          isAmbiguous);
    }

    private RetraceStringContext withQualifiedClassContext(RetracedClass qualifiedContext) {
      return new RetraceStringContext(
          classContext,
          methodContext,
          qualifiedContext,
          qualifiedMethodContext,
          methodName,
          minifiedLineNumber,
          originalLineNumber,
          source,
          isAmbiguous);
    }

    private RetraceStringContext withQualifiedMethodContext(RetracedMethod qualifiedContext) {
      return new RetraceStringContext(
          classContext,
          methodContext,
          qualifiedContext.getHolderClass(),
          qualifiedContext,
          methodName,
          minifiedLineNumber,
          originalLineNumber,
          source,
          isAmbiguous);
    }

    public RetraceStringContext withSource(String source) {
      return new RetraceStringContext(
          classContext,
          methodContext,
          qualifiedClassContext,
          qualifiedMethodContext,
          methodName,
          minifiedLineNumber,
          originalLineNumber,
          source,
          isAmbiguous);
    }

    public RetraceStringContext withLineNumbers(int minifiedLineNumber, int originalLineNumber) {
      return new RetraceStringContext(
          classContext,
          methodContext,
          qualifiedClassContext,
          qualifiedMethodContext,
          methodName,
          minifiedLineNumber,
          originalLineNumber,
          source,
          isAmbiguous);
    }
  }

  static class RetraceStringBuilder {

    private final StringBuilder retracedString;
    private int lastCommittedIndex;

    private RetraceStringBuilder(String retracedString, int lastCommittedIndex) {
      this.retracedString = new StringBuilder(retracedString);
      this.lastCommittedIndex = lastCommittedIndex;
    }

    private void appendRetracedString(
        String source, String stringToAppend, int originalFromIndex, int originalToIndex) {
      retracedString.append(source, lastCommittedIndex, originalFromIndex);
      retracedString.append(stringToAppend);
      lastCommittedIndex = originalToIndex;
    }

    private String build(String source) {
      return retracedString.append(source, lastCommittedIndex, source.length()).toString();
    }
  }

  private static class RetraceString {

    private final RetraceStringBuilder builder;
    private final RetraceStringContext context;

    private RetraceString(RetraceStringBuilder builder, RetraceStringContext context) {
      this.builder = builder;
      this.context = context;
    }

    private RetraceClassResult.Element getClassContext() {
      return context.classContext;
    }

    private RetraceFrameResult.Element getMethodContext() {
      return context.methodContext;
    }

    private String getSource() {
      return context.source;
    }

    private int getLineNumber() {
      return context.originalLineNumber;
    }

    private boolean isAmbiguous() {
      return context.isAmbiguous;
    }

    private static RetraceString start(RetraceStringContext initialContext) {
      return new RetraceString(new RetraceStringBuilder("", 0), initialContext);
    }

    private RetraceString updateContext(
        Function<RetraceStringContext, RetraceStringContext> update) {
      return new RetraceString(builder, update.apply(context));
    }

    private RetraceString duplicate(RetraceStringContext newContext) {
      return new RetraceString(
          new RetraceStringBuilder(builder.retracedString.toString(), builder.lastCommittedIndex),
          newContext);
    }

    private RetraceString appendRetracedString(
        String source, String stringToAppend, int originalFromIndex, int originalToIndex) {
      builder.appendRetracedString(source, stringToAppend, originalFromIndex, originalToIndex);
      return this;
    }
  }

  private interface RegularExpressionGroupHandler {

    List<RetraceString> handleMatch(
        String original, List<RetraceString> strings, Matcher matcher, RetracerImpl retracer);

    default RetraceStringContext buildInitial(
        RetraceStringContext context, Matcher matcher, RetracerImpl retracer) {
      return context;
    }

    default boolean isClassNameGroupHandler() {
      return false;
    }

    default ClassNameGroupHandler asClassNameGroupHandler() {
      return null;
    }

    default boolean isQualifiedHandler() {
      return false;
    }

    default QualifiedRegularExpressionGroupHandler asQualifiedHandler() {
      return null;
    }
  }

  private interface QualifiedRegularExpressionGroupHandler extends RegularExpressionGroupHandler {

    @Override
    default boolean isQualifiedHandler() {
      return true;
    }

    @Override
    default QualifiedRegularExpressionGroupHandler asQualifiedHandler() {
      return this;
    }

    void setClassNameGroupHandler(ClassNameGroupHandler handler);
  }

  private abstract static class RegularExpressionGroup {

    abstract String subExpression();

    abstract RegularExpressionGroupHandler createHandler(String captureGroup);

    boolean isSynthetic() {
      return false;
    }
  }

  // TODO(b/145731185): Extend support for identifiers with strings inside back ticks.
  private static final String javaIdentifierSegment =
      "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";

  private static final String METHOD_NAME_REGULAR_EXPRESSION =
      "(?:(" + javaIdentifierSegment + "|\\<init\\>|\\<clinit\\>))";

  abstract static class ClassNameGroup extends RegularExpressionGroup {

    abstract String getClassName(RetracedClass classReference);

    abstract ClassReference classFromMatch(String match);

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return new ClassNameGroupHandler(this, captureGroup);
    }

    static class ClassNameGroupHandler implements RegularExpressionGroupHandler {

      private RetraceClassResultImpl retraceClassResult = null;
      private final ClassNameGroup classNameGroup;
      private final String captureGroup;
      private RegularExpressionGroupHandler qualifiedHandler;

      public ClassNameGroupHandler(ClassNameGroup classNameGroup, String captureGroup) {
        this.classNameGroup = classNameGroup;
        this.captureGroup = captureGroup;
      }

      @Override
      public List<RetraceString> handleMatch(
          String original, List<RetraceString> strings, Matcher matcher, RetracerImpl retracer) {
        final int startOfGroup = matcher.start(captureGroup);
        if (startOfGroup == NO_MATCH) {
          return strings;
        }
        String typeName = matcher.group(captureGroup);
        RetraceClassResultImpl retraceResult =
            retraceClassResult == null
                ? retracer.retraceClass(classNameGroup.classFromMatch(typeName))
                : retraceClassResult;
        assert !retraceResult.isAmbiguous();
        List<RetraceString> retraceStrings = new ArrayList<>(strings.size());
        for (RetraceString retraceString : strings) {
          retraceResult.forEach(
              element -> {
                RetraceString newRetraceString =
                    retraceString.updateContext(
                        context -> context.withClassContext(element, element.getRetracedClass()));
                retraceStrings.add(newRetraceString);
                if (qualifiedHandler == null) {
                  // If there is no qualified handler, commit right away.
                  newRetraceString.builder.appendRetracedString(
                      original,
                      classNameGroup.getClassName(element.getRetracedClass()),
                      startOfGroup,
                      matcher.end(captureGroup));
                }
              });
        }
        return retraceStrings;
      }

      void commitClassName(
          String original,
          RetraceString retraceString,
          RetracedClass qualifiedContext,
          Matcher matcher) {
        if (matcher.start(captureGroup) == NO_MATCH) {
          return;
        }
        retraceString.builder.appendRetracedString(
            original,
            classNameGroup.getClassName(qualifiedContext),
            matcher.start(captureGroup),
            matcher.end(captureGroup));
      }

      @Override
      public RetraceStringContext buildInitial(
          RetraceStringContext context, Matcher matcher, RetracerImpl retracer) {
        // Reset the local class context since this the same handler is used for multiple lines.
        retraceClassResult = null;
        if (matcher.start(captureGroup) == NO_MATCH || context.classContext != null) {
          return context;
        }
        String typeName = matcher.group(captureGroup);
        retraceClassResult = retracer.retraceClass(classNameGroup.classFromMatch(typeName));
        assert !retraceClassResult.isAmbiguous();
        Box<RetraceStringContext> box = new Box<>();
        retraceClassResult.forEach(
            element -> box.set(context.withClassContext(element, element.getRetracedClass())));
        return box.get();
      }

      public void setQualifiedHandler(RegularExpressionGroupHandler handler) {
        assert handler.isQualifiedHandler();
        this.qualifiedHandler = handler;
      }

      @Override
      public boolean isClassNameGroupHandler() {
        return true;
      }

      @Override
      public ClassNameGroupHandler asClassNameGroupHandler() {
        return this;
      }
    }
  }

  private static class TypeNameGroup extends ClassNameGroup {

    @Override
    String subExpression() {
      return "(" + javaIdentifierSegment + "\\.)*" + javaIdentifierSegment;
    }

    @Override
    String getClassName(RetracedClass classReference) {
      return classReference.getTypeName();
    }

    @Override
    ClassReference classFromMatch(String match) {
      return Reference.classFromTypeName(match);
    }
  }

  private static class BinaryNameGroup extends ClassNameGroup {

    @Override
    String subExpression() {
      return "(?:" + javaIdentifierSegment + "\\/)*" + javaIdentifierSegment;
    }

    @Override
    String getClassName(RetracedClass classReference) {
      return classReference.getBinaryName();
    }

    @Override
    ClassReference classFromMatch(String match) {
      return Reference.classFromBinaryName(match);
    }
  }

  private static class MethodNameGroup extends RegularExpressionGroup {

    private final boolean printVerbose;

    public MethodNameGroup(boolean printVerbose) {
      this.printVerbose = printVerbose;
    }

    @Override
    String subExpression() {
      return METHOD_NAME_REGULAR_EXPRESSION;
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return new QualifiedRegularExpressionGroupHandler() {

        private ClassNameGroupHandler classNameGroupHandler;

        @Override
        public void setClassNameGroupHandler(ClassNameGroupHandler handler) {
          classNameGroupHandler = handler;
        }

        @Override
        public List<RetraceString> handleMatch(
            String original, List<RetraceString> strings, Matcher matcher, RetracerImpl retracer) {
          final int startOfGroup = matcher.start(captureGroup);
          if (startOfGroup == NO_MATCH) {
            if (classNameGroupHandler != null) {
              for (RetraceString string : strings) {
                classNameGroupHandler.commitClassName(
                    original, string, string.context.qualifiedClassContext, matcher);
              }
            }
            return strings;
          }
          String methodName = matcher.group(captureGroup);
          List<RetraceString> retracedStrings = new ArrayList<>();
          for (RetraceString retraceString : strings) {
            retraceMethodForString(
                retraceString,
                methodName,
                (element, newContext) -> {
                  element.visitFrames(
                      (method, ignoredPosition) -> {
                        RetraceString newRetraceString =
                            retraceString.duplicate(newContext.withQualifiedMethodContext(method));
                        if (classNameGroupHandler != null) {
                          classNameGroupHandler.commitClassName(
                              original, newRetraceString, method.getHolderClass(), matcher);
                        }
                        retracedStrings.add(
                            newRetraceString.appendRetracedString(
                                original,
                                printVerbose
                                    ? methodDescriptionFromRetraceMethod(method, false, true)
                                    : method.getMethodName(),
                                startOfGroup,
                                matcher.end(captureGroup)));
                      });
                });
          }
          return retracedStrings;
        }

        @Override
        public RetraceStringContext buildInitial(
            RetraceStringContext context, Matcher matcher, RetracerImpl retracer) {
          final int startOfGroup = matcher.start(captureGroup);
          if (startOfGroup == NO_MATCH || context.methodName != null) {
            return context;
          }
          return context.withMethodName(matcher.group(captureGroup));
        }
      };
    }

    private static void retraceMethodForString(
        RetraceString retraceString,
        String methodName,
        BiConsumer<Element, RetraceStringContext> process) {
      if (retraceString.context.classContext == null) {
        return;
      }
      RetraceClassResult.Element classContext = retraceString.getClassContext();
      RetraceFrameResult retraceFrameResult =
          retraceString.context.minifiedLineNumber > NO_MATCH
              ? classContext.lookupFrame(methodName, retraceString.context.minifiedLineNumber)
              : classContext.lookupFrame(methodName);
      retraceFrameResult.forEach(
          element -> {
            process.accept(
                element,
                retraceString.context.withMethodContext(element, retraceFrameResult.isAmbiguous()));
          });
    }
  }

  private static class FieldNameGroup extends RegularExpressionGroup {

    private final boolean printVerbose;

    public FieldNameGroup(boolean printVerbose) {
      this.printVerbose = printVerbose;
    }

    @Override
    String subExpression() {
      return javaIdentifierSegment;
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return new QualifiedRegularExpressionGroupHandler() {

        private ClassNameGroupHandler classNameGroupHandler;

        @Override
        public void setClassNameGroupHandler(ClassNameGroupHandler handler) {
          classNameGroupHandler = handler;
        }

        @Override
        public List<RetraceString> handleMatch(
            String original, List<RetraceString> strings, Matcher matcher, RetracerImpl retracer) {
          final int startOfGroup = matcher.start(captureGroup);
          if (startOfGroup == NO_MATCH) {
            if (classNameGroupHandler != null) {
              for (RetraceString string : strings) {
                classNameGroupHandler.commitClassName(
                    original, string, string.context.qualifiedClassContext, matcher);
              }
            }
            return strings;
          }
          String fieldName = matcher.group(captureGroup);
          List<RetraceString> retracedStrings = new ArrayList<>();
          for (RetraceString retraceString : strings) {
            if (retraceString.getClassContext() == null) {
              assert classNameGroupHandler == null;
              return strings;
            }
            final RetraceFieldResult retraceFieldResult =
                retraceString.getClassContext().lookupField(fieldName);
            assert !retraceFieldResult.isAmbiguous();
            retraceFieldResult.forEach(
                element -> {
                  if (classNameGroupHandler != null) {
                    classNameGroupHandler.commitClassName(
                        original, retraceString, element.getField().getHolderClass(), matcher);
                  }
                  retracedStrings.add(
                      retraceString
                          .updateContext(
                              context ->
                                  context.withQualifiedClassContext(
                                      element.getField().getHolderClass()))
                          .appendRetracedString(
                              original,
                              getFieldString(element.getField()),
                              startOfGroup,
                              matcher.end(captureGroup)));
                });
          }
          return retracedStrings;
        }
      };
    }

    private String getFieldString(RetracedField fieldReference) {
      if (!printVerbose || fieldReference.isUnknown()) {
        return fieldReference.getFieldName();
      }
      assert fieldReference.isKnown();
      KnownRetracedField knownRef = fieldReference.asKnown();
      return knownRef.getFieldType().getTypeName() + " " + fieldReference.getFieldName();
    }
  }

  private static class SourceFileGroup extends RegularExpressionGroup {

    @Override
    String subExpression() {
      return "(?:(\\w*[\\. ])?(\\w*)?)";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (original, strings, matcher, retracer) -> {
        final int startOfGroup = matcher.start(captureGroup);
        if (startOfGroup == NO_MATCH) {
          return strings;
        }
        String fileName = matcher.group(captureGroup);
        List<RetraceString> retracedStrings = null;
        for (RetraceString retraceString : strings) {
          if (retraceString.context.classContext == null) {
            return strings;
          }
          if (retracedStrings == null) {
            retracedStrings = new ArrayList<>();
          }
          RetraceSourceFileResult sourceFileResult =
              retraceString.getMethodContext() != null
                  ? retraceString
                      .getMethodContext()
                      .retraceSourceFile(retraceString.context.qualifiedMethodContext, fileName)
                  : RetraceUtils.getSourceFile(
                      retraceString.getClassContext(),
                      retraceString.context.qualifiedClassContext,
                      fileName,
                      retracer);
          retracedStrings.add(
              retraceString
                  .updateContext(context -> context.withSource(sourceFileResult.getFilename()))
                  .appendRetracedString(
                      original,
                      sourceFileResult.getFilename(),
                      startOfGroup,
                      matcher.end(captureGroup)));
        }
        return retracedStrings;
      };
    }
  }

  private static class LineNumberGroup extends RegularExpressionGroup {

    @Override
    String subExpression() {
      return "\\d*";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return new RegularExpressionGroupHandler() {
        @Override
        public List<RetraceString> handleMatch(
            String original, List<RetraceString> strings, Matcher matcher, RetracerImpl retracer) {
          final int startOfGroup = matcher.start(captureGroup);
          if (startOfGroup == NO_MATCH) {
            return strings;
          }
          String lineNumberAsString = matcher.group(captureGroup);
          if (lineNumberAsString.isEmpty()) {
            return strings;
          }
          int lineNumber = Integer.parseInt(lineNumberAsString);
          List<RetraceString> retracedStrings = new ArrayList<>();
          for (RetraceString retraceString : strings) {
            Element methodContext = retraceString.context.methodContext;
            if (methodContext == null) {
              if (retraceString.context.classContext == null
                  || retraceString.context.methodName == null) {
                // We have no way of retracing the line number.
                retracedStrings.add(retraceString);
                continue;
              }
              // This situation arises when we have a matched pattern as %l..%c.%m where the
              // line number handler is defined before the methodname handler.
              MethodNameGroup.retraceMethodForString(
                  retraceString,
                  retraceString.context.methodName,
                  (element, newContext) -> {
                    // The same method can be represented multiple times if it has multiple
                    // mappings.
                    element.visitFrames(
                        (method, ignoredPosition) -> {
                          int originalPosition = method.getOriginalPositionOrDefault(lineNumber);
                          retracedStrings.add(
                              retraceString
                                  .duplicate(
                                      retraceString
                                          .context
                                          .withQualifiedMethodContext(method)
                                          .withLineNumbers(lineNumber, originalPosition))
                                  .appendRetracedString(
                                      original,
                                      originalPosition + "",
                                      startOfGroup,
                                      matcher.end(captureGroup)));
                        });
                  });
              continue;
            }
            // If the method context is unknown, do nothing.
            if (methodContext.isUnknown()) {
              retracedStrings.add(retraceString);
              continue;
            }
            int originalLineNumber =
                retraceString.context.qualifiedMethodContext.getOriginalPositionOrDefault(
                    lineNumber);
            retracedStrings.add(
                retraceString
                    .updateContext(
                        context -> context.withLineNumbers(lineNumber, originalLineNumber))
                    .appendRetracedString(
                        original,
                        originalLineNumber + "",
                        startOfGroup,
                        matcher.end(captureGroup)));
          }
          return retracedStrings;
        }

        @Override
        public RetraceStringContext buildInitial(
            RetraceStringContext context, Matcher matcher, RetracerImpl retracer) {
          if (matcher.start(captureGroup) == NO_MATCH || context.minifiedLineNumber > NO_MATCH) {
            return context;
          }
          String lineNumberAsString = matcher.group(captureGroup);
          return context.withLineNumbers(
              lineNumberAsString.isEmpty() ? NO_MATCH : Integer.parseInt(lineNumberAsString),
              NO_MATCH);
        }
      };
    }
  }

  private static class SourceFileLineNumberGroup extends RegularExpressionGroup {

    @Override
    String subExpression() {
      return "%s(?::%l)?";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      throw new Unreachable("Should never be called");
    }

    @Override
    boolean isSynthetic() {
      return true;
    }
  }

  private static final String JAVA_TYPE_REGULAR_EXPRESSION =
      "(" + javaIdentifierSegment + "\\.)*" + javaIdentifierSegment + "[\\[\\]]*";

  private static class FieldOrReturnTypeGroup extends RegularExpressionGroup {

    @Override
    String subExpression() {
      return JAVA_TYPE_REGULAR_EXPRESSION;
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (original, strings, matcher, retracer) -> {
        final int startOfGroup = matcher.start(captureGroup);
        if (startOfGroup == NO_MATCH) {
          return strings;
        }
        String typeName = matcher.group(captureGroup);
        String descriptor = DescriptorUtils.javaTypeToDescriptor(typeName);
        if (!DescriptorUtils.isDescriptor(descriptor) && !"V".equals(descriptor)) {
          return strings;
        }
        TypeReference typeReference = Reference.returnTypeFromDescriptor(descriptor);
        RetraceTypeResultImpl retracedType = retracer.retraceType(typeReference);
        assert !retracedType.isAmbiguous();
        for (RetraceString retraceString : strings) {
          retracedType.forEach(
              element -> {
                RetracedTypeImpl retracedReference = element.getType();
                retraceString.appendRetracedString(
                    original,
                    retracedReference.isVoid() ? "void" : retracedReference.getTypeName(),
                    startOfGroup,
                    matcher.end(captureGroup));
              });
        }
        return strings;
      };
    }
  }

  private static class MethodArgumentsGroup extends RegularExpressionGroup {

    @Override
    String subExpression() {
      return "((" + JAVA_TYPE_REGULAR_EXPRESSION + "\\,)*" + JAVA_TYPE_REGULAR_EXPRESSION + ")?";
    }

    @Override
    RegularExpressionGroupHandler createHandler(String captureGroup) {
      return (original, strings, matcher, retracer) -> {
        final int startOfGroup = matcher.start(captureGroup);
        if (startOfGroup == NO_MATCH) {
          return strings;
        }
        final String formals =
            Arrays.stream(matcher.group(captureGroup).split(","))
                .map(
                    typeName -> {
                      typeName = typeName.trim();
                      if (typeName.isEmpty()) {
                        return null;
                      }
                      String descriptor = DescriptorUtils.javaTypeToDescriptor(typeName);
                      if (!DescriptorUtils.isDescriptor(descriptor) && !"V".equals(descriptor)) {
                        return typeName;
                      }
                      final RetraceTypeResultImpl retraceResult =
                          retracer.retraceType(Reference.returnTypeFromDescriptor(descriptor));
                      assert !retraceResult.isAmbiguous();
                      final Box<RetracedTypeImpl> elementBox = new Box<>();
                      retraceResult.forEach(element -> elementBox.set(element.getType()));
                      return elementBox.get().getTypeName();
                    })
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
        for (RetraceString string : strings) {
          string.appendRetracedString(original, formals, startOfGroup, matcher.end(captureGroup));
        }
        return strings;
      };
    }
  }
}