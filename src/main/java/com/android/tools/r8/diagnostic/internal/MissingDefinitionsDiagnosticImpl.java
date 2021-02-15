// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.diagnostic.internal;

import com.android.tools.r8.diagnostic.MissingDefinitionInfo;
import com.android.tools.r8.diagnostic.MissingDefinitionsDiagnostic;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramDerivedContext;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.Reference;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;

public class MissingDefinitionsDiagnosticImpl implements MissingDefinitionsDiagnostic {

  private final SortedMap<ClassReference, MissingClassAccessContexts> missingClasses;

  private MissingDefinitionsDiagnosticImpl(
      SortedMap<ClassReference, MissingClassAccessContexts> missingClasses) {
    assert !missingClasses.isEmpty();
    this.missingClasses = missingClasses;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Deprecated
  public Set<ClassReference> getMissingClasses() {
    return missingClasses.keySet();
  }

  @Override
  public Collection<MissingDefinitionInfo> getMissingDefinitions() {
    throw new Unimplemented();
  }

  /** A missing class(es) failure can generally not be attributed to a single origin. */
  @Override
  public Origin getOrigin() {
    return Origin.unknown();
  }

  /** A missing class(es) failure can generally not be attributed to a single position. */
  @Override
  public Position getPosition() {
    return Position.UNKNOWN;
  }

  @Override
  public String getDiagnosticMessage() {
    StringBuilder builder = new StringBuilder();
    Iterator<Entry<ClassReference, MissingClassAccessContexts>> missingClassesIterator =
        missingClasses.entrySet().iterator();

    // The diagnostic is always non-empty.
    assert missingClassesIterator.hasNext();

    // Write first line.
    writeMissingClass(builder.append("Missing class "), missingClassesIterator.next());

    // Write remaining lines with line separator before.
    missingClassesIterator.forEachRemaining(
        missingClassInfo ->
            writeMissingClass(
                builder.append(System.lineSeparator()).append("Missing class "), missingClassInfo));

    return builder.toString();
  }

  private static void writeMissingClass(
      StringBuilder builder, Entry<ClassReference, MissingClassAccessContexts> missingClassInfo) {
    writeMissingClass(builder, missingClassInfo.getKey(), missingClassInfo.getValue());
  }

  private static void writeMissingClass(
      StringBuilder builder, ClassReference missingClass, MissingClassAccessContexts contexts) {
    builder
        .append(missingClass.getTypeName())
        .append(contexts.getReferencedFromMessageSuffix(missingClass));
  }

  public static class Builder {

    private ImmutableSortedMap.Builder<ClassReference, MissingClassAccessContexts>
        missingClassesBuilder =
            ImmutableSortedMap.orderedBy(Comparator.comparing(ClassReference::getDescriptor));

    public Builder addMissingClasses(Map<DexType, Set<ProgramDerivedContext>> missingClasses) {
      missingClasses.forEach(
          (missingClass, contexts) ->
              missingClassesBuilder.put(
                  Reference.classFromDescriptor(missingClass.toDescriptorString()),
                  MissingClassAccessContexts.builder().addAll(contexts).build()));
      return this;
    }

    public MissingDefinitionsDiagnostic build() {
      return new MissingDefinitionsDiagnosticImpl(missingClassesBuilder.build());
    }
  }
}