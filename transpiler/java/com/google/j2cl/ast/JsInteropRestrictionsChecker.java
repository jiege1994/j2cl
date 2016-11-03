/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.ast;

import static java.util.stream.Collectors.joining;

import com.google.j2cl.ast.common.HasJsNameInfo;
import com.google.j2cl.ast.common.JsUtils;
import com.google.j2cl.common.J2clUtils;
import com.google.j2cl.errors.Problems;
import com.google.j2cl.errors.Problems.Messages;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks and throws errors for invalid JsInterop constructs. */
public class JsInteropRestrictionsChecker {
  private final Problems problems;

  public JsInteropRestrictionsChecker(Problems problems) {
    this.problems = problems;
  }

  public static void check(CompilationUnit compilationUnit, Problems problems) {
    new JsInteropRestrictionsChecker(problems).checkCompilationUnit(compilationUnit);
  }

  private void checkCompilationUnit(CompilationUnit compilationUnit) {
    for (Type type : compilationUnit.getTypes()) {
      checkType(type);
    }
  }

  private void checkType(Type type) {
    if (type.getDescriptor().isJsType()) {
      if (!checkJsType(type)) {
        return;
      }
      checkJsName(type.getDescriptor());
      checkJsNamespace(type.getDescriptor());
    }

    if (type.getDescriptor().isNative()) {
      if (!checkNativeJsType(type)) {
        return;
      }
    }

    if (type.getDescriptor().isJsFunctionInterface()) {
      checkJsFunctionInterface(type);
    } else if (type.getDescriptor().isJsFunctionImplementation()) {
      checkJsFunctionImplementation(type);
    } else {
      checkJsFunctionSubtype(type);
    }

    for (Field field : type.getFields()) {
      checkField(field);
    }
    for (Method method : type.getMethods()) {
      if (method.isSynthetic()) {
        continue;
      }
      checkMethod(method);
    }
    checkTypeMemberCollisions(type);
    // TODO: do other checks.
  }

  private void checkTypeMemberCollisions(Type type) {
    // Native JS members are allowed to collide.
    if (type.getDescriptor().isNative()) {
      return;
    }

    Map<String, MemberDescriptor> memberDescriptorsByKey = new HashMap<>();
    List<Member> members = type.getMembers();
    for (Member member : members) {
      if (!(member instanceof Field) && !(member instanceof Method)) {
        continue;
      }
      MemberDescriptor memberDescriptor =
          member instanceof Field
              ? ((Field) member).getDescriptor()
              : ((Method) member).getDescriptor();

      // TODO(b/27597597): analyze static exports as well.
      if (memberDescriptor.isStatic()) {
        continue;
      }
      // Constructors are subject to a separate check and should not be duplicatively examined here.
      if (memberDescriptor instanceof MethodDescriptor
          && ((MethodDescriptor) memberDescriptor).isConstructor()) {
        continue;
      }
      // Only look at unobfuscated JsInterop things.
      if (memberDescriptor.getJsInfo().equals(JsInfo.NONE)
          || memberDescriptor.getJsInfo().getJsMemberType().equals(JsMemberType.JS_FUNCTION)
          || memberDescriptor.getJsInfo().isJsOverlay()) {
        continue;
      }
      // Native JS members are allowed to collide.
      if (memberDescriptor.isNative()) {
        continue;
      }

      // If a collision has occurred examine it more closely.
      if (memberDescriptorsByKey.containsKey(getMemberKey(memberDescriptor))) {
        MemberDescriptor collidingMemberDescriptor =
            memberDescriptorsByKey.get(getMemberKey(memberDescriptor));
        Set<JsMemberType> jsMemberTypes = EnumSet.noneOf(JsMemberType.class);
        jsMemberTypes.add(collidingMemberDescriptor.getJsInfo().getJsMemberType());
        jsMemberTypes.add(memberDescriptor.getJsInfo().getJsMemberType());

        // A getter/setter pair can both use the same name.
        if (jsMemberTypes.contains(JsMemberType.GETTER)
            && jsMemberTypes.contains(JsMemberType.SETTER)) {
          continue;
        }

        problems.error(
            Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
            "'%s' and '%s' cannot both use the same JavaScript name '%s'.",
            getReadableDescription(memberDescriptor),
            getReadableDescription(collidingMemberDescriptor),
            getMemberKey(memberDescriptor));
      }

      memberDescriptorsByKey.put(getMemberKey(memberDescriptor), memberDescriptor);
    }
  }

  private boolean checkJsType(Type type) {
    if (type.getDescriptor().isLocal()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "Local class '%s' cannot be a JsType.",
          getReadableDescription(type.getDescriptor()));
      return false;
    }
    return true;
  }

  private void checkField(Field field) {
    if (field.getDescriptor().getEnclosingClassTypeDescriptor().isNative()) {
      checkFieldOfNativeJsType(field);
    }
    if (field.getDescriptor().isJsOverlay()) {
      checkJsOverlay(field);
    }
    checkMemberQualifiedJsName(field.getDescriptor());
    // TODO: do other checks.
  }

  private void checkMethod(Method method) {
    if (method.getDescriptor().getEnclosingClassTypeDescriptor().isNative()) {
      checkMethodOfNativeJsType(method);
    }
    if (method.getDescriptor().isJsOverlay()) {
      checkJsOverlay(method);
    }
    checkMemberQualifiedJsName(method.getDescriptor());
    checkMethodParameters(method);
    // TODO: do other checks.
  }

  private void checkMemberQualifiedJsName(MemberDescriptor memberDescriptor) {
    if (memberDescriptor instanceof MethodDescriptor) {
      if (((MethodDescriptor) memberDescriptor).isConstructor()) {
        // Constructors always inherit their name and namespace from the enclosing type.
        // The corresponding checks are done for the type separately.
        return;
      }
    }

    checkJsName(memberDescriptor);

    if (memberDescriptor.getJsNamespace() == null) {
      return;
    }

    if (memberDescriptor
        .getJsNamespace()
        .equals(memberDescriptor.getEnclosingClassTypeDescriptor().getQualifiedName())) {
      // Namespace set by the enclosing type has already been checked.
      return;
    }

    if (memberDescriptor.isPolymorphic()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "Instance member '%s' cannot declare a namespace.",
          getReadableDescription(memberDescriptor));
      return;
    }

    checkJsNamespace(memberDescriptor);
  }

  private void checkJsOverlay(Field field) {
    FieldDescriptor fieldDescriptor = field.getDescriptor();
    String readableDescription = getReadableDescription(fieldDescriptor);
    if (!fieldDescriptor.getEnclosingClassTypeDescriptor().isNative()
        && !fieldDescriptor.getEnclosingClassTypeDescriptor().isJsFunctionInterface()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsOverlay '%s' can only be declared in a native type or @JsFunction interface.",
          readableDescription);
    }
    if (!fieldDescriptor.isStatic()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsOverlay field '%s' can only be static.",
          readableDescription);
    }
  }

  private void checkJsOverlay(Method method) {
    MethodDescriptor methodDescriptor = method.getDescriptor();
    String readableDescription = getReadableDescription(methodDescriptor);
    if (!methodDescriptor.getEnclosingClassTypeDescriptor().isNative()
        && !methodDescriptor.getEnclosingClassTypeDescriptor().isJsFunctionInterface()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsOverlay '%s' can only be declared in a native type or @JsFunction interface.",
          readableDescription);
    }
    if (method.isOverride()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsOverlay method '%s' cannot override a supertype method.",
          readableDescription);
      return;
    }
    if (method.isNative()
        || (!methodDescriptor.getEnclosingClassTypeDescriptor().isFinal()
            && !method.isFinal()
            && !method.getDescriptor().isStatic()
            && !method.getDescriptor().getVisibility().isPrivate()
            && !method.getDescriptor().isDefault())) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsOverlay method '%s' cannot be non-final nor native.",
          readableDescription);
    }
  }

  private boolean checkNativeJsType(Type type) {
    TypeDescriptor typeDescriptor = type.getDescriptor();
    String readableDescription = getReadableDescription(typeDescriptor);

    if (typeDescriptor.isEnumOrSubclass()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "Enum '%s' cannot be a native JsType.",
          readableDescription);
      return false;
    }
    if (typeDescriptor.isInstanceNestedClass()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "Non static inner class '%s' cannot be a native JsType.",
          readableDescription);
      return false;
    }

    TypeDescriptor superTypeDescriptor = type.getSuperTypeDescriptor();
    if (superTypeDescriptor != null
        && !superTypeDescriptor.equalsIgnoreNullability(TypeDescriptors.get().javaLangObject)
        && !superTypeDescriptor.isNative()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "Native JsType '%s' can only extend native JsType classes.",
          readableDescription);
    }
    for (TypeDescriptor interfaceType : type.getSuperInterfaceTypeDescriptors()) {
      if (!interfaceType.isNative()) {
        problems.error(
            Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
            "Native JsType '%s' can only %s native JsType interfaces.",
            readableDescription,
            type.isInterface() ? "extend" : "implement");
      }
    }

    if (type.hasInstanceInitializerBlocks()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "Native JsType '%s' cannot have initializer.",
          readableDescription);
    }

    return true;
  }

  private void checkMethodOfNativeJsType(Method method) {
    if (method.getDescriptor().isJsOverlay()) {
      return;
    }
    String readableDescription = getReadableDescription(method.getDescriptor());
    JsMemberType jsMemberType = method.getDescriptor().getJsInfo().getJsMemberType();
    switch (jsMemberType) {
      case CONSTRUCTOR:
        if (!isConstructorEmpty(method)) {
          problems.error(
              Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
              "Native JsType constructor '%s' cannot have non-empty method body.",
              readableDescription);
        }
        break;
      case METHOD:
      case GETTER:
      case SETTER:
      case PROPERTY:
        if (!method.isAbstract() && !method.isNative()) {
          problems.error(
              Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
              "Native JsType method '%s' should be native or abstract.",
              readableDescription);
        }
        break;
      case NONE:
        problems.error(
            Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
            "Native JsType member '%s' cannot have @JsIgnore.",
            readableDescription);
        break;
      default:
        break;
    }
  }

  private void checkFieldOfNativeJsType(Field field) {
    if (field.getDescriptor().isJsOverlay()) {
      return;
    }
    String readableDescription = getReadableDescription(field.getDescriptor());
    if (field.getDescriptor().isJsProperty()) {
      if (field.hasInitializer()) {
        problems.error(
            Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
            "Native JsType field '%s' cannot have initializer.",
            readableDescription);
      }
    } else {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "Native JsType member '%s' cannot have @JsIgnore.",
          readableDescription);
    }
  }

  private void checkJsFunctionInterface(Type type) {
    String readableDescription = getReadableDescription(type.getDescriptor());
    if (!type.getSuperInterfaceTypeDescriptors().isEmpty()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsFunction '%s' cannot extend other interfaces.",
          readableDescription);
    }

    if (type.getDescriptor().isJsType()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "'%s' cannot be both a JsFunction and a JsType at the same time.",
          readableDescription);
    }
  }

  private void checkJsFunctionImplementation(Type type) {
    String readableDescription = getReadableDescription(type.getDescriptor());
    if (type.getSuperInterfaceTypeDescriptors().size() != 1) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsFunction implementation '%s' cannot implement more than one interface.",
          readableDescription);
    }

    if (type.getDescriptor().isJsType()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "'%s' cannot be both a JsFunction implementation and a JsType at the same time.",
          readableDescription);
    }

    if (!type.getSuperTypeDescriptor()
        .equalsIgnoreNullability(TypeDescriptors.get().javaLangObject)) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsFunction implementation '%s' cannot extend a class.",
          readableDescription);
    }
  }

  private void checkJsFunctionSubtype(Type type) {
    TypeDescriptor superClassTypeDescriptor = type.getSuperTypeDescriptor();
    if (superClassTypeDescriptor != null && superClassTypeDescriptor.isJsFunctionImplementation()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "'%s' cannot extend JsFunction implementation '%s'.",
          getReadableDescription(type.getDescriptor()),
          getReadableDescription(superClassTypeDescriptor));
    }
    for (TypeDescriptor superInterface : type.getSuperInterfaceTypeDescriptors()) {
      if (superInterface.isJsFunctionInterface()) {
        problems.error(
            Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
            "'%s' cannot extend JsFunction '%s'.",
            getReadableDescription(type.getDescriptor()),
            getReadableDescription(superInterface));
      }
    }
  }

  private void checkJsName(HasJsNameInfo item) {
    String jsName = item.getJsName();
    if (jsName == null) {
      return;
    }
    if (jsName.isEmpty()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "'%s' cannot have an empty name.",
          getReadableDescription(item));
    } else if ((item.isNative() && !JsUtils.isValidJsQualifiedName(jsName))
        || (!item.isNative() && !JsUtils.isValidJsIdentifier(jsName))) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "'%s' has invalid name '%s'.",
          getReadableDescription(item),
          jsName);
    }
  }

  private void checkJsNamespace(HasJsNameInfo item) {
    String jsNamespace = item.getJsNamespace();
    if (jsNamespace == null || JsUtils.isGlobal(jsNamespace)) {
      return;
    }
    if (jsNamespace.isEmpty()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "'%s' cannot have an empty namespace.",
          getReadableDescription(item));
    } else if (!JsUtils.isValidJsQualifiedName(item.getJsNamespace())) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "'%s' has invalid namespace '%s'.",
          getReadableDescription(item),
          item.getJsNamespace());
    }
  }

  private void checkMethodParameters(Method method) {
    // TODO(rluble): When overriding is included in the AST representation, add the relevant checks,
    // i.e. that a parameter can not change from optional into non optional in an override.
    boolean hasOptionalParameters = false;
    MethodDescriptor methodDescriptor = method.getDescriptor();

    int numberOfParameters = method.getParameters().size();
    for (int i = 0; i < numberOfParameters; i++) {
      int lastParameter = numberOfParameters - 1;
      boolean isVarargsParameter = (i == lastParameter) && methodDescriptor.isJsMethodVarargs();

      if (method.isParameterOptional(i)) {
        if (methodDescriptor.getParameterTypeDescriptors().get(i).isPrimitive()) {
          problems.error(
              Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
              "JsOptional parameter '%s' in method '%s' cannot be of a primitive type.",
              method.getParameters().get(i).getName(),
              getReadableDescription(methodDescriptor));
        }
        if (isVarargsParameter) {
          problems.error(
              Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
              "JsOptional parameter '%s' in method '%s' cannot be a varargs parameter.",
              method.getParameters().get(i).getName(),
              getReadableDescription(methodDescriptor));
        }
        hasOptionalParameters = true;
        continue;
      }
      if (hasOptionalParameters && !isVarargsParameter) {
        problems.error(
            Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
            "JsOptional parameter '%s' in method '%s' cannot precede parameters that are not "
                + "JsOptional.",
            method.getParameters().get(i - 1).getName(),
            getReadableDescription(methodDescriptor));
        break;
      }
    }
    if (hasOptionalParameters
        && !methodDescriptor.isJsMethod()
        && !methodDescriptor.isJsConstructor()
        && !methodDescriptor.isJsFunction()) {
      problems.error(
          Messages.ERR_JSINTEROP_RESTRICTIONS_ERROR,
          "JsOptional parameter in '%s' can only be declared in a JsMethod, a JsConstructor or a "
              + "JsFunction.",
          getReadableDescription(methodDescriptor));
    }
  }

  /**
   * Returns true if the constructor method is locally empty (allows calls to super constructor).
   */
  private static boolean isConstructorEmpty(Method constructor) {
    List<Statement> statements = constructor.getBody().getStatements();
    return statements.isEmpty() || (statements.size() == 1 && AstUtils.hasSuperCall(constructor));
  }

  /**
   * Returns true if the type does not have any static initialization blocks and does not do any
   * initializations on static fields except for compile time constants.
   */
  private static boolean isClinitEmpty(Type type) {
    if (type.hasStaticInitializerBlocks()) {
      return false;
    }
    for (Field staticField : type.getStaticFields()) {
      if (staticField.hasInitializer() && !staticField.isCompileTimeConstant()) {
        return false;
      }
    }
    return true;
  }

  private String getMemberKey(MemberDescriptor memberDescriptor) {
    return (memberDescriptor.isStatic() ? "static " : "") + memberDescriptor.getJsName();
  }

  private String getReadableDescription(HasJsNameInfo hasJsNameInfo) {
    if (hasJsNameInfo instanceof FieldDescriptor) {
      return getReadableDescription((FieldDescriptor) hasJsNameInfo);
    } else if (hasJsNameInfo instanceof MethodDescriptor) {
      return getReadableDescription((MethodDescriptor) hasJsNameInfo);
    } else if (hasJsNameInfo instanceof TypeDescriptor) {
      return getReadableDescription((TypeDescriptor) hasJsNameInfo);
    }
    return null;
  }

  private String getReadableDescription(FieldDescriptor fieldDescriptor) {
    return J2clUtils.format(
        "%s %s.%s",
        getReadableDescription(fieldDescriptor.getTypeDescriptor()),
        getReadableDescription(fieldDescriptor.getEnclosingClassTypeDescriptor()),
        fieldDescriptor.getName());
  }

  private String getReadableDescription(MethodDescriptor methodDescriptor) {
    return J2clUtils.format(
        "%s%s.%s(%s)",
        methodDescriptor.isConstructor()
            ? ""
            : getReadableDescription(methodDescriptor.getReturnTypeDescriptor()) + " ",
        getReadableDescription(methodDescriptor.getEnclosingClassTypeDescriptor()),
        methodDescriptor.getName(),
        methodDescriptor
            .getDeclarationMethodDescriptor()
            .getParameterTypeDescriptors()
            .stream()
            .map(type -> getReadableDescription(type.getRawTypeDescriptor()))
            .collect(joining(", ")));
  }

  private String getReadableDescription(TypeDescriptor typeDescriptor) {
    // TODO: Actually provide a real readable description.
    return typeDescriptor.getSimpleName();
  }
}
