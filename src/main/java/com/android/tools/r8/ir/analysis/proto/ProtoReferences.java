// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.proto;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.code.Value;

public class ProtoReferences {

  private final DexItemFactory dexItemFactory;

  public final DexType enumLiteMapType;
  public final DexType extendableMessageType;
  public final DexType extensionDescriptorType;
  public final DexType extensionRegistryLiteType;
  public final DexType generatedExtensionType;
  public final DexType generatedMessageLiteType;
  public final DexType generatedMessageLiteBuilderType;
  public final DexType generatedMessageLiteExtendableBuilderType;
  public final DexType generatedMessageLiteExtendableMessageType;
  public final DexType rawMessageInfoType;
  public final DexType messageLiteType;
  public final DexType methodToInvokeType;
  public final DexType wireFormatFieldType;

  public final GeneratedExtensionMethods generatedExtensionMethods;
  public final GeneratedMessageLiteMethods generatedMessageLiteMethods;
  public final GeneratedMessageLiteBuilderMethods generatedMessageLiteBuilderMethods;
  public final GeneratedMessageLiteExtendableBuilderMethods
      generatedMessageLiteExtendableBuilderMethods;
  public final MethodToInvokeMembers methodToInvokeMembers;

  public final DexString defaultInstanceFieldName;
  public final DexString dynamicMethodName;
  public final DexString findLiteExtensionByNumberName;
  public final DexString newBuilderMethodName;

  public final DexString protobufPackageDescriptorPrefix;

  public final DexProto dynamicMethodProto;
  public final DexProto findLiteExtensionByNumberProto;

  public final DexMethod dynamicMethod;
  public final DexMethod newMessageInfoMethod;
  public final DexMethod rawMessageInfoConstructor;

  public ProtoReferences(DexItemFactory factory) {
    dexItemFactory = factory;

    // Types.
    enumLiteMapType = factory.createType("Lcom/google/protobuf/Internal$EnumLiteMap;");
    extendableMessageType =
        factory.createType("Lcom/google/protobuf/GeneratedMessageLite$ExtendableMessage;");
    extensionDescriptorType =
        factory.createType("Lcom/google/protobuf/GeneratedMessageLite$ExtensionDescriptor;");
    extensionRegistryLiteType = factory.createType("Lcom/google/protobuf/ExtensionRegistryLite;");
    generatedExtensionType =
        factory.createType("Lcom/google/protobuf/GeneratedMessageLite$GeneratedExtension;");
    generatedMessageLiteType = factory.createType("Lcom/google/protobuf/GeneratedMessageLite;");
    generatedMessageLiteBuilderType =
        factory.createType("Lcom/google/protobuf/GeneratedMessageLite$Builder;");
    generatedMessageLiteExtendableBuilderType =
        factory.createType("Lcom/google/protobuf/GeneratedMessageLite$ExtendableBuilder;");
    generatedMessageLiteExtendableMessageType =
        factory.createType("Lcom/google/protobuf/GeneratedMessageLite$ExtendableMessage;");
    rawMessageInfoType = factory.createType("Lcom/google/protobuf/RawMessageInfo;");
    messageLiteType = factory.createType("Lcom/google/protobuf/MessageLite;");
    methodToInvokeType =
        factory.createType("Lcom/google/protobuf/GeneratedMessageLite$MethodToInvoke;");
    wireFormatFieldType = factory.createType("Lcom/google/protobuf/WireFormat$FieldType;");

    // Names.
    defaultInstanceFieldName = factory.createString("DEFAULT_INSTANCE");
    dynamicMethodName = factory.createString("dynamicMethod");
    findLiteExtensionByNumberName = factory.createString("findLiteExtensionByNumber");
    newBuilderMethodName = factory.createString("newBuilder");

    // Other names.
    protobufPackageDescriptorPrefix = factory.createString("Lcom/google/protobuf/");

    // Protos.
    dynamicMethodProto =
        factory.createProto(
            factory.objectType, methodToInvokeType, factory.objectType, factory.objectType);
    findLiteExtensionByNumberProto =
        factory.createProto(generatedExtensionType, messageLiteType, factory.intType);

    // Methods.
    dynamicMethod =
        factory.createMethod(generatedMessageLiteType, dynamicMethodProto, dynamicMethodName);
    newMessageInfoMethod =
        factory.createMethod(
            generatedMessageLiteType,
            factory.createProto(
                factory.objectType, messageLiteType, factory.stringType, factory.objectArrayType),
            factory.createString("newMessageInfo"));
    rawMessageInfoConstructor =
        factory.createMethod(
            rawMessageInfoType,
            factory.createProto(
                factory.voidType, messageLiteType, factory.stringType, factory.objectArrayType),
            factory.constructorMethodName);

    generatedExtensionMethods = new GeneratedExtensionMethods(factory);
    generatedMessageLiteMethods = new GeneratedMessageLiteMethods(factory);
    generatedMessageLiteBuilderMethods = new GeneratedMessageLiteBuilderMethods(factory);
    generatedMessageLiteExtendableBuilderMethods =
        new GeneratedMessageLiteExtendableBuilderMethods(factory);
    methodToInvokeMembers = new MethodToInvokeMembers(factory);
  }

  public DexField getDefaultInstanceField(DexProgramClass holder) {
    return dexItemFactory.createField(holder.type, holder.type, defaultInstanceFieldName);
  }

  public boolean isAbstractGeneratedMessageLiteBuilder(DexProgramClass clazz) {
    return clazz.type == generatedMessageLiteBuilderType
        || clazz.type == generatedMessageLiteExtendableBuilderType;
  }

  public boolean isDynamicMethod(DexMethod method) {
    return method.name == dynamicMethodName && method.proto == dynamicMethodProto;
  }

  public boolean isDynamicMethod(DexEncodedMethod encodedMethod) {
    return isDynamicMethod(encodedMethod.method);
  }

  public boolean isDynamicMethod(ProgramMethod method) {
    return isDynamicMethod(method.getReference());
  }

  public boolean isDynamicMethodBridge(DexMethod method) {
    return method == generatedMessageLiteMethods.dynamicMethodBridgeMethod
        || method == generatedMessageLiteMethods.dynamicMethodBridgeMethodWithObject;
  }

  public boolean isDynamicMethodBridge(DexEncodedMethod method) {
    return isDynamicMethodBridge(method.method);
  }

  public boolean isDynamicMethodBridge(ProgramMethod method) {
    return isDynamicMethodBridge(method.getReference());
  }

  public boolean isFindLiteExtensionByNumberMethod(DexMethod method) {
    return method.proto == findLiteExtensionByNumberProto
        && method.name.startsWith(findLiteExtensionByNumberName)
        && method.holder != extensionRegistryLiteType;
  }

  public boolean isFindLiteExtensionByNumberMethod(ProgramMethod method) {
    return isFindLiteExtensionByNumberMethod(method.getReference());
  }

  public boolean isGeneratedMessageLiteBuilder(DexProgramClass clazz) {
    return (clazz.superType == generatedMessageLiteBuilderType
            || clazz.superType == generatedMessageLiteExtendableBuilderType)
        && !isAbstractGeneratedMessageLiteBuilder(clazz);
  }

  public boolean isMessageInfoConstructionMethod(DexMethod method) {
    return method.match(newMessageInfoMethod) || method == rawMessageInfoConstructor;
  }

  public boolean isProtoLibraryClass(DexProgramClass clazz) {
    return clazz.type.descriptor.startsWith(protobufPackageDescriptorPrefix);
  }

  public class GeneratedExtensionMethods {

    public final DexMethod constructor;
    public final DexMethod constructorWithClass;

    private GeneratedExtensionMethods(DexItemFactory dexItemFactory) {
      constructor =
          dexItemFactory.createMethod(
              generatedExtensionType,
              dexItemFactory.createProto(
                  dexItemFactory.voidType,
                  messageLiteType,
                  dexItemFactory.objectType,
                  messageLiteType,
                  extensionDescriptorType),
              dexItemFactory.constructorMethodName);
      constructorWithClass =
          dexItemFactory.createMethod(
              generatedExtensionType,
              dexItemFactory.createProto(
                  dexItemFactory.voidType,
                  messageLiteType,
                  dexItemFactory.objectType,
                  messageLiteType,
                  extensionDescriptorType,
                  dexItemFactory.classType),
              dexItemFactory.constructorMethodName);
    }

    public boolean isConstructor(DexMethod method) {
      return method == constructor || method == constructorWithClass;
    }
  }

  public class GeneratedMessageLiteMethods {

    public final DexMethod createBuilderMethod;
    public final DexMethod dynamicMethodBridgeMethod;
    public final DexMethod dynamicMethodBridgeMethodWithObject;
    public final DexMethod isInitializedMethod;
    public final DexMethod newRepeatedGeneratedExtension;
    public final DexMethod newSingularGeneratedExtension;

    private GeneratedMessageLiteMethods(DexItemFactory dexItemFactory) {
      createBuilderMethod =
          dexItemFactory.createMethod(
              generatedMessageLiteType,
              dexItemFactory.createProto(generatedMessageLiteBuilderType),
              "createBuilder");
      dynamicMethodBridgeMethod =
          dexItemFactory.createMethod(
              generatedMessageLiteType,
              dexItemFactory.createProto(dexItemFactory.objectType, methodToInvokeType),
              "dynamicMethod");
      dynamicMethodBridgeMethodWithObject =
          dexItemFactory.createMethod(
              generatedMessageLiteType,
              dexItemFactory.createProto(
                  dexItemFactory.objectType, methodToInvokeType, dexItemFactory.objectType),
              "dynamicMethod");
      isInitializedMethod =
          dexItemFactory.createMethod(
              generatedMessageLiteType,
              dexItemFactory.createProto(dexItemFactory.booleanType),
              "isInitialized");
      newRepeatedGeneratedExtension =
          dexItemFactory.createMethod(
              generatedMessageLiteType,
              dexItemFactory.createProto(
                  generatedExtensionType,
                  messageLiteType,
                  messageLiteType,
                  enumLiteMapType,
                  dexItemFactory.intType,
                  wireFormatFieldType,
                  dexItemFactory.booleanType,
                  dexItemFactory.classType),
              "newRepeatedGeneratedExtension");
      newSingularGeneratedExtension =
          dexItemFactory.createMethod(
              generatedMessageLiteType,
              dexItemFactory.createProto(
                  generatedExtensionType,
                  messageLiteType,
                  dexItemFactory.objectType,
                  messageLiteType,
                  enumLiteMapType,
                  dexItemFactory.intType,
                  wireFormatFieldType,
                  dexItemFactory.classType),
              "newSingularGeneratedExtension");
    }
  }

  public class GeneratedMessageLiteBuilderMethods {

    public final DexMethod buildPartialMethod;
    public final DexMethod constructorMethod;

    private GeneratedMessageLiteBuilderMethods(DexItemFactory dexItemFactory) {
      buildPartialMethod =
          dexItemFactory.createMethod(
              generatedMessageLiteBuilderType,
              dexItemFactory.createProto(generatedMessageLiteType),
              "buildPartial");
      constructorMethod =
          dexItemFactory.createMethod(
              generatedMessageLiteBuilderType,
              dexItemFactory.createProto(dexItemFactory.voidType, generatedMessageLiteType),
              dexItemFactory.constructorMethodName);
    }
  }

  public class GeneratedMessageLiteExtendableBuilderMethods {

    public final DexMethod buildPartialMethod;
    public final DexMethod constructorMethod;

    private GeneratedMessageLiteExtendableBuilderMethods(DexItemFactory dexItemFactory) {
      buildPartialMethod =
          dexItemFactory.createMethod(
              generatedMessageLiteExtendableBuilderType,
              dexItemFactory.createProto(extendableMessageType),
              "buildPartial");
      constructorMethod =
          dexItemFactory.createMethod(
              generatedMessageLiteExtendableBuilderType,
              dexItemFactory.createProto(
                  dexItemFactory.voidType, generatedMessageLiteExtendableMessageType),
              dexItemFactory.constructorMethodName);
    }
  }

  public class MethodToInvokeMembers {

    public final DexField buildMessageInfoField;
    public final DexField getDefaultInstanceField;
    public final DexField getMemoizedIsInitializedField;
    public final DexField getParserField;
    public final DexField newBuilderField;
    public final DexField newMutableInstanceField;
    public final DexField setMemoizedIsInitializedField;

    private MethodToInvokeMembers(DexItemFactory dexItemFactory) {
      buildMessageInfoField =
          dexItemFactory.createField(methodToInvokeType, methodToInvokeType, "BUILD_MESSAGE_INFO");
      getDefaultInstanceField =
          dexItemFactory.createField(
              methodToInvokeType, methodToInvokeType, "GET_DEFAULT_INSTANCE");
      getMemoizedIsInitializedField =
          dexItemFactory.createField(
              methodToInvokeType, methodToInvokeType, "GET_MEMOIZED_IS_INITIALIZED");
      getParserField =
          dexItemFactory.createField(methodToInvokeType, methodToInvokeType, "GET_PARSER");
      newBuilderField =
          dexItemFactory.createField(methodToInvokeType, methodToInvokeType, "NEW_BUILDER");
      newMutableInstanceField =
          dexItemFactory.createField(
              methodToInvokeType, methodToInvokeType, "NEW_MUTABLE_INSTANCE");
      setMemoizedIsInitializedField =
          dexItemFactory.createField(
              methodToInvokeType, methodToInvokeType, "SET_MEMOIZED_IS_INITIALIZED");
    }

    public boolean isNewMutableInstanceEnum(DexField field) {
      return field == newMutableInstanceField;
    }

    public boolean isNewMutableInstanceEnum(Value value) {
      Value root = value.getAliasedValue();
      return !root.isPhi()
          && root.definition.isStaticGet()
          && isNewMutableInstanceEnum(root.definition.asStaticGet().getField());
    }

    public boolean isMethodToInvokeWithSimpleBody(DexField field) {
      return field == getDefaultInstanceField
          || field == getMemoizedIsInitializedField
          || field == newBuilderField
          || field == newMutableInstanceField
          || field == setMemoizedIsInitializedField;
    }

    public boolean isMethodToInvokeWithNonSimpleBody(DexField field) {
      return field == buildMessageInfoField || field == getParserField;
    }
  }
}
