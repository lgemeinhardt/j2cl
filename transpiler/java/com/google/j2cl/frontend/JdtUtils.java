/*
 * Copyright 2015 Google Inc.
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
package com.google.j2cl.frontend;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.j2cl.ast.ArrayLength;
import com.google.j2cl.ast.ArrayTypeDescriptor;
import com.google.j2cl.ast.AstUtilConstants;
import com.google.j2cl.ast.BinaryOperator;
import com.google.j2cl.ast.DeclaredTypeDescriptor;
import com.google.j2cl.ast.Expression;
import com.google.j2cl.ast.FieldAccess;
import com.google.j2cl.ast.FieldDescriptor;
import com.google.j2cl.ast.IntersectionTypeDescriptor;
import com.google.j2cl.ast.JsEnumInfo;
import com.google.j2cl.ast.JsInfo;
import com.google.j2cl.ast.JsMemberType;
import com.google.j2cl.ast.Kind;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.MethodDescriptor.ParameterDescriptor;
import com.google.j2cl.ast.PostfixOperator;
import com.google.j2cl.ast.PrefixOperator;
import com.google.j2cl.ast.PrimitiveTypeDescriptor;
import com.google.j2cl.ast.PrimitiveTypes;
import com.google.j2cl.ast.TypeDeclaration;
import com.google.j2cl.ast.TypeDescriptor;
import com.google.j2cl.ast.TypeDescriptors;
import com.google.j2cl.ast.TypeVariable;
import com.google.j2cl.ast.Variable;
import com.google.j2cl.ast.Visibility;
import com.google.j2cl.common.SourcePosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;

/** Utility functions to manipulate JDT internal representations. */
class JdtUtils {
  // JdtUtil members are all package private. Code outside frontend should not be aware of the
  // dependency on JDT.
  public static String getCompilationUnitPackageName(CompilationUnit compilationUnit) {
    return compilationUnit.getPackage() == null
        ? ""
        : compilationUnit.getPackage().getName().getFullyQualifiedName();
  }

  public static FieldDescriptor createFieldDescriptor(IVariableBinding variableBinding) {
    checkArgument(!isArrayLengthBinding(variableBinding));

    boolean isStatic = isStatic(variableBinding);
    Visibility visibility = getVisibility(variableBinding);
    DeclaredTypeDescriptor enclosingTypeDescriptor =
        createDeclaredTypeDescriptor(variableBinding.getDeclaringClass());
    String fieldName = variableBinding.getName();

    TypeDescriptor thisTypeDescriptor =
        createTypeDescriptorWithNullability(
            variableBinding.getType(), variableBinding.getAnnotations());

    if (variableBinding.isEnumConstant()) {
      // Enum fields are always non-nullable.
      thisTypeDescriptor = thisTypeDescriptor.toNonNullable();
    }

    FieldDescriptor declarationFieldDescriptor = null;
    if (variableBinding.getVariableDeclaration() != variableBinding) {
      declarationFieldDescriptor = createFieldDescriptor(variableBinding.getVariableDeclaration());
    }

    JsInfo jsInfo = JsInteropUtils.getJsInfo(variableBinding);
    boolean isCompileTimeConstant = variableBinding.getConstantValue() != null;
    boolean isFinal = JdtUtils.isFinal(variableBinding);
    return FieldDescriptor.newBuilder()
        .setEnclosingTypeDescriptor(enclosingTypeDescriptor)
        .setName(fieldName)
        .setTypeDescriptor(thisTypeDescriptor)
        .setStatic(isStatic)
        .setVisibility(visibility)
        .setJsInfo(jsInfo)
        .setFinal(isFinal)
        .setCompileTimeConstant(isCompileTimeConstant)
        .setDeclarationFieldDescriptor(declarationFieldDescriptor)
        .setEnumConstant(variableBinding.isEnumConstant())
        .setUnusableByJsSuppressed(
            JsInteropAnnotationUtils.isUnusableByJsSuppressed(variableBinding))
        .setDeprecated(isDeprecated(variableBinding))
        .build();
  }

  public static Variable createVariable(
      SourcePosition sourcePosition, IVariableBinding variableBinding) {
    String name = variableBinding.getName();
    TypeDescriptor typeDescriptor =
        variableBinding.isParameter()
            ? createTypeDescriptorWithNullability(
                variableBinding.getType(), variableBinding.getAnnotations())
            : createTypeDescriptor(variableBinding.getType());
    boolean isFinal = isFinal(variableBinding);
    boolean isParameter = variableBinding.isParameter();
    boolean isUnusableByJsSuppressed =
        JsInteropAnnotationUtils.isUnusableByJsSuppressed(variableBinding);
    return Variable.newBuilder()
        .setName(name)
        .setTypeDescriptor(typeDescriptor)
        .setFinal(isFinal)
        .setParameter(isParameter)
        .setUnusableByJsSuppressed(isUnusableByJsSuppressed)
        .setSourcePosition(sourcePosition)
        .build();
  }

  public static BinaryOperator getBinaryOperator(InfixExpression.Operator operator) {
    switch (operator.toString()) {
      case "*":
        return BinaryOperator.TIMES;
      case "/":
        return BinaryOperator.DIVIDE;
      case "%":
        return BinaryOperator.REMAINDER;
      case "+":
        return BinaryOperator.PLUS;
      case "-":
        return BinaryOperator.MINUS;
      case "<<":
        return BinaryOperator.LEFT_SHIFT;
      case ">>":
        return BinaryOperator.RIGHT_SHIFT_SIGNED;
      case ">>>":
        return BinaryOperator.RIGHT_SHIFT_UNSIGNED;
      case "<":
        return BinaryOperator.LESS;
      case ">":
        return BinaryOperator.GREATER;
      case "<=":
        return BinaryOperator.LESS_EQUALS;
      case ">=":
        return BinaryOperator.GREATER_EQUALS;
      case "==":
        return BinaryOperator.EQUALS;
      case "!=":
        return BinaryOperator.NOT_EQUALS;
      case "^":
        return BinaryOperator.BIT_XOR;
      case "&":
        return BinaryOperator.BIT_AND;
      case "|":
        return BinaryOperator.BIT_OR;
      case "&&":
        return BinaryOperator.CONDITIONAL_AND;
      case "||":
        return BinaryOperator.CONDITIONAL_OR;
      default:
        return null;
    }
  }

  public static BinaryOperator getBinaryOperator(Assignment.Operator operator) {
    switch (operator.toString()) {
      case "=":
        return BinaryOperator.ASSIGN;
      case "+=":
        return BinaryOperator.PLUS_ASSIGN;
      case "-=":
        return BinaryOperator.MINUS_ASSIGN;
      case "*=":
        return BinaryOperator.TIMES_ASSIGN;
      case "/=":
        return BinaryOperator.DIVIDE_ASSIGN;
      case "&=":
        return BinaryOperator.BIT_AND_ASSIGN;
      case "|=":
        return BinaryOperator.BIT_OR_ASSIGN;
      case "^=":
        return BinaryOperator.BIT_XOR_ASSIGN;
      case "%=":
        return BinaryOperator.REMAINDER_ASSIGN;
      case "<<=":
        return BinaryOperator.LEFT_SHIFT_ASSIGN;
      case ">>=":
        return BinaryOperator.RIGHT_SHIFT_SIGNED_ASSIGN;
      case ">>>=":
        return BinaryOperator.RIGHT_SHIFT_UNSIGNED_ASSIGN;
      default:
        return null;
    }
  }

  public static IMethodBinding getMethodBinding(
      ITypeBinding typeBinding, String methodName, ITypeBinding... parameterTypes) {

    Queue<ITypeBinding> deque = new ArrayDeque<>();
    deque.add(typeBinding);

    while (!deque.isEmpty()) {
      typeBinding = deque.poll();
      for (IMethodBinding methodBinding : typeBinding.getDeclaredMethods()) {
        if (methodBinding.getName().equals(methodName)
            && Arrays.equals(methodBinding.getParameterTypes(), parameterTypes)) {
          return methodBinding;
        }
      }
      ITypeBinding superclass = typeBinding.getSuperclass();
      if (superclass != null) {
        deque.add(superclass);
      }

      ITypeBinding[] superInterfaces = typeBinding.getInterfaces();
      if (superInterfaces != null) {
        for (ITypeBinding superInterface : superInterfaces) {
          deque.add(superInterface);
        }
      }
    }
    return null;
  }

  public static PrefixOperator getPrefixOperator(PrefixExpression.Operator operator) {
    switch (operator.toString()) {
      case "++":
        return PrefixOperator.INCREMENT;
      case "--":
        return PrefixOperator.DECREMENT;
      case "+":
        return PrefixOperator.PLUS;
      case "-":
        return PrefixOperator.MINUS;
      case "~":
        return PrefixOperator.COMPLEMENT;
      case "!":
        return PrefixOperator.NOT;
      default:
        return null;
    }
  }

  public static PostfixOperator getPostfixOperator(PostfixExpression.Operator operator) {
    switch (operator.toString()) {
      case "++":
        return PostfixOperator.INCREMENT;
      case "--":
        return PostfixOperator.DECREMENT;
      default:
        return null;
    }
  }

  public static Expression createFieldAccess(Expression qualifier, IVariableBinding fieldBinding) {
    if (isArrayLengthBinding(fieldBinding)) {
      return ArrayLength.newBuilder().setArrayExpression(qualifier).build();
    }

    return FieldAccess.Builder.from(JdtUtils.createFieldDescriptor(fieldBinding))
        .setQualifier(qualifier)
        .build();
  }

  private static boolean isArrayLengthBinding(IVariableBinding variableBinding) {
    return variableBinding.getName().equals("length")
        && variableBinding.isField()
        && variableBinding.getDeclaringClass() == null;
  }

  /** Returns true if the binding is annotated with @UncheckedCast. */
  public static boolean hasUncheckedCastAnnotation(IBinding binding) {
    return JdtAnnotationUtils.hasAnnotation(binding, "javaemul.internal.annotations.UncheckedCast");
  }

  /** Helper method to work around JDT habit of returning raw collections. */
  @SuppressWarnings("rawtypes")
  public static <T> List<T> asTypedList(List jdtRawCollection) {
    @SuppressWarnings("unchecked")
    List<T> typedList = jdtRawCollection;
    return typedList;
  }

  /**
   * Returns the method binding of the immediately enclosing method, whether that be an actual
   * method or a lambda expression.
   */
  public static IMethodBinding findCurrentMethodBinding(org.eclipse.jdt.core.dom.ASTNode node) {
    while (true) {
      if (node == null) {
        return null;
      } else if (node instanceof MethodDeclaration) {
        return ((MethodDeclaration) node).resolveBinding();
      } else if (node instanceof LambdaExpression) {
        return ((LambdaExpression) node).resolveMethodBinding();
      }
      node = node.getParent();
    }
  }

  /** Creates a DeclaredTypeDescriptor from a JDT TypeBinding. */
  public static DeclaredTypeDescriptor createDeclaredTypeDescriptor(ITypeBinding typeBinding) {
    return createTypeDescriptor(typeBinding, DeclaredTypeDescriptor.class);
  }

  /** Creates a DeclaredTypeDescriptor from a JDT TypeBinding. */
  private static <T extends TypeDescriptor> T createTypeDescriptor(
      ITypeBinding typeBinding, Class<T> clazz) {
    return clazz.cast(createTypeDescriptor(typeBinding));
  }

  /** Creates a TypeDescriptor from a JDT TypeBinding. */
  public static TypeDescriptor createTypeDescriptor(ITypeBinding typeBinding) {
    return createTypeDescriptorWithNullability(typeBinding, new IAnnotationBinding[0]);
  }

  /**
   * Creates a type descriptor for the given type binding, taking into account nullability.
   *
   * @param typeBinding the type binding, used to create the type descriptor.
   * @param elementAnnotations the annotations on the element
   */
  private static TypeDescriptor createTypeDescriptorWithNullability(
      ITypeBinding typeBinding, IAnnotationBinding[] elementAnnotations) {
    if (typeBinding == null) {
      return null;
    }

    if (typeBinding.isPrimitive()) {
      return PrimitiveTypes.get(typeBinding.getName());
    }

    if (isIntersectionType(typeBinding)) {
      return createIntersectionType(typeBinding);
    }

    if (typeBinding.isNullType()) {
      return TypeDescriptors.get().javaLangObject;
    }

    if (typeBinding.isTypeVariable() || typeBinding.isCapture() || typeBinding.isWildcardType()) {
      return createTypeVariable(typeBinding);
    }

    boolean isNullable = isNullable(typeBinding, elementAnnotations);
    if (typeBinding.isArray()) {
      TypeDescriptor componentTypeDescriptor = createTypeDescriptor(typeBinding.getComponentType());
      return ArrayTypeDescriptor.newBuilder()
          .setComponentTypeDescriptor(componentTypeDescriptor)
          .setNullable(isNullable)
          .build();
    }

    return withNullability(createDeclaredType(typeBinding), isNullable);
  }

  private static TypeDescriptor createTypeVariable(ITypeBinding typeBinding) {
    Supplier<TypeDescriptor> boundTypeDescriptorFactory = () -> getTypeBound(typeBinding);

    return TypeVariable.newBuilder()
        .setBoundTypeDescriptorSupplier(boundTypeDescriptorFactory)
        .setEnclosingTypeDescriptorSupplier(
            () -> (DeclaredTypeDescriptor) createTypeDescriptor(typeBinding.getDeclaringClass()))
        .setWildcardOrCapture(typeBinding.isWildcardType() || typeBinding.isCapture())
        .setUniqueKey(typeBinding.getKey())
        .setClassComponents(getClassComponentsForTypeVariable(typeBinding))
        .build();
  }

  private static TypeDescriptor getTypeBound(ITypeBinding typeBinding) {
    // TODO(b/67858399): should be using typeBinding.getTypeBounds() but it returns empty for
    // wildcards in the current version of JDT.

    List<ITypeBinding> bounds = Lists.newArrayList(typeBinding.getInterfaces());
    if (typeBinding.getSuperclass() != JdtUtils.javaLangObjectTypeBinding.get()) {
      bounds.add(0, typeBinding.getSuperclass());
    }
    if (bounds.isEmpty()) {
      return TypeDescriptors.get().javaLangObject;
    }
    if (bounds.size() == 1) {
      return createTypeDescriptor(bounds.get(0));
    }
    return IntersectionTypeDescriptor.newBuilder()
        .setIntersectionTypeDescriptors(createTypeDescriptors(bounds, DeclaredTypeDescriptor.class))
        .build();
  }

  private static DeclaredTypeDescriptor withNullability(
      DeclaredTypeDescriptor typeDescriptor, boolean nullable) {
    return nullable ? typeDescriptor.toNullable() : typeDescriptor.toNonNullable();
  }

  /**
   * Returns whether the given type binding should be nullable, according to the annotations on it
   * and if nullability is enabled for the package containing the binding.
   */
  private static boolean isNullable(
      ITypeBinding typeBinding, IAnnotationBinding[] elementAnnotations) {
    checkArgument(!typeBinding.isPrimitive());

    if (typeBinding.getQualifiedName().equals("java.lang.Void")) {
      // Void is always nullable.
      return true;
    }

    if (JsInteropAnnotationUtils.hasJsNonNullAnnotation(typeBinding)) {
      return false;
    }

    // TODO(b/70164536): Deprecate non J2CL-specific nullability annotations.
    Iterable<IAnnotationBinding> allAnnotations =
        Iterables.concat(
            Arrays.asList(elementAnnotations),
            Arrays.asList(typeBinding.getTypeAnnotations()),
            Arrays.asList(typeBinding.getAnnotations()));
    for (IAnnotationBinding annotation : allAnnotations) {
      String annotationName = annotation.getName();

      if (annotationName.equalsIgnoreCase("Nonnull")) {
        return false;
      }
    }

    return true;
  }

  /**
   * In case the given type binding is nested, return the outermost possible enclosing type binding.
   */
  private static ITypeBinding toTopLevelTypeBinding(ITypeBinding typeBinding) {
    ITypeBinding topLevelClass = typeBinding;
    while (topLevelClass.getDeclaringClass() != null) {
      topLevelClass = topLevelClass.getDeclaringClass();
    }
    return topLevelClass;
  }

  private static boolean isIntersectionType(ITypeBinding binding) {
    return binding.isIntersectionType()
        // JDT returns true for isIntersectionType() for type variables, wildcards and captures
        // with intersection type bounds.
        && !binding.isCapture()
        && !binding.isTypeVariable()
        && !binding.isWildcardType();
  }

  private static ImmutableList<String> getClassComponentsForTypeVariable(ITypeBinding typeBinding) {
    if (typeBinding.isWildcardType() || typeBinding.isCapture()) {
      return ImmutableList.of("?");
    }
    checkArgument(typeBinding.isTypeVariable());
    if (typeBinding.getDeclaringClass() != null) {
      // This is a class-level type variable. Use its name prefixed with "C_" as its simple
      // name component and gather enclosing components from the enclosing class hierarchy.
      return ImmutableList.<String>builder()
          .addAll(getClassComponents(typeBinding.getDeclaringClass()))
          .add(AstUtilConstants.TYPE_VARIABLE_IN_TYPE_PREFIX + typeBinding.getName())
          .build();
    } else {
      // This is a method-level type variable. Use its simple name prefixed with "M_") as its
      // simple name component, replace the immediate enclosing component with
      // <EnclosingClass>_<EnclosingComponent> and then continue normally through the enclosing
      // class hierarchy.
      return ImmutableList.<String>builder()
          .addAll(
              getClassComponents(
                  typeBinding.getDeclaringMethod().getDeclaringClass().getDeclaringClass()))
          .add(
              typeBinding.getDeclaringMethod().getDeclaringClass().getName()
                  + "_"
                  + typeBinding.getDeclaringMethod().getName())
          .add(AstUtilConstants.TYPE_VARIABLE_IN_METHOD_PREFIX + typeBinding.getName())
          .build();
    }
  }

  private static List<String> getClassComponents(ITypeBinding typeBinding) {
    List<String> classComponents = new ArrayList<>();
    ITypeBinding currentType = typeBinding;
    while (currentType != null) {
      checkArgument(
          !currentType.isTypeVariable()
              && !currentType.isWildcardType()
              && !currentType.isCapture());
      String simpleName;
      if (currentType.isLocal()) {
        // JDT binary name for local class is like package.components.EnclosingClass$1SimpleName
        // Extract the generated name by taking the part after the binary name of the declaring
        // class.
        String binaryName = getBinaryNameFromTypeBinding(currentType);
        String declaringClassPrefix =
            getBinaryNameFromTypeBinding(currentType.getDeclaringClass()) + "$";
        checkState(binaryName.startsWith(declaringClassPrefix));
        simpleName = binaryName.substring(declaringClassPrefix.length());
      } else {
        simpleName = currentType.getErasure().getName();
      }
      classComponents.add(0, simpleName);
      currentType = currentType.getDeclaringClass();
    }
    return classComponents;
  }

  /**
   * Returns the binary name for a type binding.
   *
   * <p>NOTE: This accounts for the cases that JDT does not assign binary names, which are those of
   * unreachable local or anonymous classes.
   */
  private static String getBinaryNameFromTypeBinding(ITypeBinding typeBinding) {
    String binaryName = typeBinding.getBinaryName();
    if (binaryName == null && (typeBinding.isLocal() || typeBinding.isAnonymous())) {
      // Local and anonymous classes in unreachable code have null binary name.

      // The code here is a HACK that relies on the way that JDT synthesizes keys. Keys for
      // unreachable classes have the closest enclosing reachable class key as a prefix (minus the
      // ending semicolon)
      ITypeBinding closestReachableExclosingClass = typeBinding.getDeclaringClass();
      while (closestReachableExclosingClass.getBinaryName() == null) {
        closestReachableExclosingClass = closestReachableExclosingClass.getDeclaringClass();
      }
      String parentKey = closestReachableExclosingClass.getKey();
      String key = typeBinding.getKey();
      return getBinaryNameFromTypeBinding(typeBinding.getDeclaringClass())
          + "$$Unreachable"
          // remove the parent prefix and the ending semicolon
          + key.substring(parentKey.length() - 1, key.length() - 1);
    }
    return binaryName;
  }

  private static List<TypeDescriptor> getTypeArgumentTypeDescriptors(ITypeBinding typeBinding) {
    return getTypeArgumentTypeDescriptors(typeBinding, TypeDescriptor.class);
  }

  private static <T extends TypeDescriptor> List<T> getTypeArgumentTypeDescriptors(
      ITypeBinding typeBinding, Class<T> clazz) {
    ImmutableList.Builder<T> typeArgumentDescriptorsBuilder = ImmutableList.builder();
    if (typeBinding.isParameterizedType()) {
      typeArgumentDescriptorsBuilder.addAll(
          createTypeDescriptors(typeBinding.getTypeArguments(), clazz));
    } else {
      typeArgumentDescriptorsBuilder.addAll(
          createTypeDescriptors(typeBinding.getTypeParameters(), clazz));
    }

    // DO NOT USE getDeclaringMethod(). getDeclaringMethod() returns a synthetic static method
    // in the declaring class, instead of the proper lambda method with the declaring method
    // enclosing it when the declaration is inside a lambda. If the declaring method declares a
    // type variable, it would get lost.
    IBinding declarationBinding = getDeclaringMethodOrFieldBinding(typeBinding);
    if (declarationBinding instanceof IMethodBinding) {
      typeArgumentDescriptorsBuilder.addAll(
          createTypeDescriptors(((IMethodBinding) declarationBinding).getTypeParameters(), clazz));
    }

    if (capturesEnclosingInstance(typeBinding.getTypeDeclaration())) {
      // Find type parameters in the enclosing scope and copy them over as well.
      createDeclaredTypeDescriptor(typeBinding.getDeclaringClass()).getTypeArgumentDescriptors()
          .stream()
          .map(clazz::cast)
          .forEach(typeArgumentDescriptorsBuilder::add);
    }

    return typeArgumentDescriptorsBuilder.build();
  }

  public static Visibility getVisibility(IBinding binding) {
    return getVisibility(binding.getModifiers());
  }

  private static Visibility getVisibility(int modifiers) {
    if (Modifier.isPublic(modifiers)) {
      return Visibility.PUBLIC;
    } else if (Modifier.isProtected(modifiers)) {
      return Visibility.PROTECTED;
    } else if (Modifier.isPrivate(modifiers)) {
      return Visibility.PRIVATE;
    } else {
      return Visibility.PACKAGE_PRIVATE;
    }
  }

  private static boolean isDeprecated(IBinding binding) {
    return JdtAnnotationUtils.hasAnnotation(binding, Deprecated.class.getName());
  }

  private static boolean isDefaultMethod(IMethodBinding binding) {
    return Modifier.isDefault(binding.getModifiers());
  }

  private static boolean isAbstract(IBinding binding) {
    return Modifier.isAbstract(binding.getModifiers());
  }

  private static boolean isFinal(IBinding binding) {
    return Modifier.isFinal(binding.getModifiers());
  }

  public static boolean isStatic(IBinding binding) {
    if (binding instanceof IVariableBinding) {
      IVariableBinding variableBinding = (IVariableBinding) binding;
      if (!variableBinding.isField() || variableBinding.getDeclaringClass().isInterface()) {
        // Interface fields and variables are implicitly static.
        return true;
      }
    }
    return Modifier.isStatic(binding.getModifiers());
  }

  public static boolean isStatic(BodyDeclaration bodyDeclaration) {
    return Modifier.isStatic(bodyDeclaration.getModifiers());
  }

  private static boolean isEnumSyntheticMethod(IMethodBinding methodBinding) {
    // Enum synthetic methods are not marked as such because per JLS 13.1 these methods are
    // implicitly declared but are not marked as synthetic.
    return methodBinding.getDeclaringClass().isEnum()
        && (isEnumValuesMethod(methodBinding) || isEnumValueOfMethod(methodBinding));
  }

  private static boolean isEnumValuesMethod(IMethodBinding methodBinding) {
    return methodBinding.getName().equals("values")
        && methodBinding.getParameterTypes().length == 0;
  }

  private static boolean isEnumValueOfMethod(IMethodBinding methodBinding) {
    return methodBinding.getName().equals("valueOf")
        && methodBinding.getParameterTypes().length == 1
        && methodBinding.getParameterTypes()[0].getQualifiedName().equals("java.lang.String");
  }

  /**
   * Returns true if instances of this type capture its outer instances; i.e. if it is an non static
   * member class, or an anonymous or local class defined in an instance context.
   */
  public static boolean capturesEnclosingInstance(ITypeBinding typeBinding) {
    if (!typeBinding.isClass() || !typeBinding.isNested()) {
      // Only non-top level classes (excludes Enums, Interfaces etc.) can capture outer instances.
      return false;
    }

    if (typeBinding.isLocal()) {
      // Local types (which include anonymous classes in JDT) are static only if they are declared
      // in a static context; i.e. if the member where they are declared is static.
      return !isStatic(getDeclaringMethodOrFieldBinding(typeBinding));
    } else {
      checkArgument(typeBinding.isMember());
      // Member classes must be marked explicitly static.
      return !isStatic(typeBinding);
    }
  }

  /**
   * Returns the declaring member binding if any, skipping lambdas which are returned by JDT as
   * declaring members but are not.
   */
  private static IBinding getDeclaringMethodOrFieldBinding(ITypeBinding typeBinding) {
    IBinding declarationBinding = typeBinding.getTypeDeclaration().getDeclaringMember();

    // Skip all lambda method bindings.
    while (isLambdaBinding(declarationBinding)) {
      declarationBinding = ((IMethodBinding) declarationBinding).getDeclaringMember();
    }
    return declarationBinding;
  }

  private static boolean isLambdaBinding(IBinding binding) {
    return binding instanceof IMethodBinding
        && ((IMethodBinding) binding).getDeclaringMember() != null;
  }

  /** Create a MethodDescriptor directly based on the given JDT method binding. */
  public static MethodDescriptor createMethodDescriptor(IMethodBinding methodBinding) {
    DeclaredTypeDescriptor enclosingTypeDescriptor =
        createDeclaredTypeDescriptor(methodBinding.getDeclaringClass());

    boolean isStatic = isStatic(methodBinding);
    Visibility visibility = getVisibility(methodBinding);
    boolean isDefault = isDefaultMethod(methodBinding);
    JsInfo jsInfo = computeJsInfo(methodBinding);

    boolean isNative =
        Modifier.isNative(methodBinding.getModifiers())
            || (!jsInfo.isJsOverlay()
                && enclosingTypeDescriptor.isNative()
                && isAbstract(methodBinding));

    boolean isConstructor = methodBinding.isConstructor();
    String methodName = methodBinding.getName();

    TypeDescriptor returnTypeDescriptor =
        createTypeDescriptorWithNullability(
            methodBinding.getReturnType(), methodBinding.getAnnotations());

    MethodDescriptor declarationMethodDescriptor = null;
    if (methodBinding.getMethodDeclaration() != methodBinding) {
      declarationMethodDescriptor = createMethodDescriptor(methodBinding.getMethodDeclaration());
    }

    // generate type parameters declared in the method.
    Iterable<TypeVariable> typeParameterTypeDescriptors =
        FluentIterable.from(methodBinding.getTypeParameters())
            .transform(JdtUtils::createTypeDescriptor)
            .transform(typeDescriptor -> (TypeVariable) typeDescriptor);

    ImmutableList.Builder<ParameterDescriptor> parameterDescriptorBuilder = ImmutableList.builder();
    for (int i = 0; i < methodBinding.getParameterTypes().length; i++) {
      parameterDescriptorBuilder.add(
          ParameterDescriptor.newBuilder()
              .setTypeDescriptor(
                  createTypeDescriptorWithNullability(
                      methodBinding.getParameterTypes()[i],
                      methodBinding.getParameterAnnotations(i)))
              .setJsOptional(JsInteropUtils.isJsOptional(methodBinding, i))
              .setVarargs(
                  i == methodBinding.getParameterTypes().length - 1 && methodBinding.isVarargs())
              .setDoNotAutobox(JsInteropUtils.isDoNotAutobox(methodBinding, i))
              .build());
    }

    if (enclosingTypeDescriptor.getTypeDeclaration().isAnonymous()
        && isConstructor
        && enclosingTypeDescriptor.getSuperTypeDescriptor().hasJsConstructor()) {
      jsInfo = JsInfo.Builder.from(jsInfo).setJsMemberType(JsMemberType.CONSTRUCTOR).build();
    }
    // JDT does not provide method bindings for any bridge methods so the current one must not be a
    // bridge.
    boolean isBridge = false;
    return MethodDescriptor.newBuilder()
        .setEnclosingTypeDescriptor(enclosingTypeDescriptor)
        .setName(isConstructor ? null : methodName)
        .setParameterDescriptors(parameterDescriptorBuilder.build())
        .setDeclarationMethodDescriptor(declarationMethodDescriptor)
        .setReturnTypeDescriptor(returnTypeDescriptor)
        .setTypeParameterTypeDescriptors(typeParameterTypeDescriptors)
        .setJsInfo(jsInfo)
        .setJsFunction(JsInteropUtils.isOrOverridesJsFunctionMethod(methodBinding))
        .setVisibility(visibility)
        .setStatic(isStatic)
        .setConstructor(isConstructor)
        .setNative(isNative)
        .setFinal(JdtUtils.isFinal(methodBinding))
        .setDefaultMethod(isDefault)
        .setAbstract(Modifier.isAbstract(methodBinding.getModifiers()))
        .setSynthetic(methodBinding.isSynthetic())
        .setEnumSyntheticMethod(isEnumSyntheticMethod(methodBinding))
        .setBridge(isBridge)
        .setUnusableByJsSuppressed(JsInteropAnnotationUtils.isUnusableByJsSuppressed(methodBinding))
        .setDeprecated(isDeprecated(methodBinding))
        .build();
  }

  /** Checks overriding chain to compute JsInfo. */
  private static JsInfo computeJsInfo(IMethodBinding methodBinding) {
    JsInfo originalJsInfo = JsInteropUtils.getJsInfo(methodBinding);
    if (originalJsInfo.isJsOverlay()) {
      return originalJsInfo;
    }

    List<JsInfo> inheritedJsInfoList = new ArrayList<>();

    // Add the JsInfo of the method and all the overridden methods to the list.
    if (originalJsInfo.getJsMemberType() != JsMemberType.NONE) {
      inheritedJsInfoList.add(originalJsInfo);
    }
    for (IMethodBinding overriddenMethod : getOverriddenMethods(methodBinding)) {
      JsInfo inheritedJsInfo = JsInteropUtils.getJsInfo(overriddenMethod);
      if (inheritedJsInfo.getJsMemberType() != JsMemberType.NONE) {
        inheritedJsInfoList.add(inheritedJsInfo);
      }
    }

    if (inheritedJsInfoList.isEmpty()) {
      return originalJsInfo;
    }

    // TODO(b/67778330): Make the handling of @JsProperty consistent with the handling of @JsMethod.
    if (inheritedJsInfoList.get(0).getJsMemberType() == JsMemberType.METHOD) {
      // Return the first JsInfo with a Js name specified.
      for (JsInfo inheritedJsInfo : inheritedJsInfoList) {
        if (inheritedJsInfo.getJsName() != null) {
          // Don't inherit @JsAsync annotation from overridden methods.
          return JsInfo.Builder.from(inheritedJsInfo)
              .setJsAsync(originalJsInfo.isJsAsync())
              .build();
        }
      }
    }

    // Don't inherit @JsAsync annotation from overridden methods.
    return JsInfo.Builder.from(inheritedJsInfoList.get(0))
        .setJsAsync(originalJsInfo.isJsAsync())
        .build();
  }

  public static Set<IMethodBinding> getOverriddenMethods(IMethodBinding methodBinding) {
    return getOverriddenMethodsInType(methodBinding, methodBinding.getDeclaringClass());
  }

  private static Set<IMethodBinding> getOverriddenMethodsInType(
      IMethodBinding methodBinding, ITypeBinding typeBinding) {
    Set<IMethodBinding> overriddenMethods = new HashSet<>();
    for (IMethodBinding declaredMethod : typeBinding.getDeclaredMethods()) {
      if (methodBinding.overrides(declaredMethod) && !methodBinding.isConstructor()) {
        checkArgument(!Modifier.isStatic(methodBinding.getModifiers()));
        overriddenMethods.add(declaredMethod);
      }
    }
    // Recurse into immediate super class and interfaces for overridden method.
    if (typeBinding.getSuperclass() != null) {
      overriddenMethods.addAll(
          getOverriddenMethodsInType(methodBinding, typeBinding.getSuperclass()));
    }
    for (ITypeBinding interfaceBinding : typeBinding.getInterfaces()) {
      overriddenMethods.addAll(getOverriddenMethodsInType(methodBinding, interfaceBinding));
    }

    ITypeBinding javaLangObjectTypeBinding = JdtUtils.javaLangObjectTypeBinding.get();
    if (typeBinding != javaLangObjectTypeBinding) {
      for (IMethodBinding objectMethodBinding : javaLangObjectTypeBinding.getDeclaredMethods()) {
        if (!isPolymorphic(objectMethodBinding)) {
          continue;
        }
        checkState(!getVisibility(objectMethodBinding).isPackagePrivate());
        if (methodBinding.isSubsignature(objectMethodBinding)) {
          overriddenMethods.add(objectMethodBinding);
        }
      }
    }

    return overriddenMethods;
  }

  private static boolean isPolymorphic(IMethodBinding methodBinding) {
    return !methodBinding.isConstructor()
        && !isStatic(methodBinding)
        && !Modifier.isPrivate(methodBinding.getModifiers());
  }

  private static boolean isLocal(ITypeBinding typeBinding) {
    return typeBinding.isLocal();
  }

  /** Returns the MethodDescriptor for the JsFunction method. */
  private static MethodDescriptor getJsFunctionMethodDescriptor(ITypeBinding typeBinding) {
    if (JsInteropUtils.isJsFunction(typeBinding)
        && typeBinding.getFunctionalInterfaceMethod() != null) {
      // typeBinding.getFunctionalInterfaceMethod returns in some cases the method declaration
      // instead of the method with the corresponding parameterization. Note: this is observed in
      // the case when a type is parameterized with a wildcard, e.g. JsFunction<?>.
      IMethodBinding jsFunctionMethodBinding =
          Arrays.stream(typeBinding.getDeclaredMethods())
              .filter(
                  methodBinding ->
                      methodBinding.getMethodDeclaration()
                          == typeBinding.getFunctionalInterfaceMethod().getMethodDeclaration())
              .findFirst()
              .get();
      return createMethodDescriptor(jsFunctionMethodBinding);
    }

    // Find implementation method that corresponds to JsFunction.
    Optional<ITypeBinding> jsFunctionInterface =
        Arrays.stream(typeBinding.getInterfaces()).filter(JsInteropUtils::isJsFunction).findFirst();

    return jsFunctionInterface
        .map(ITypeBinding::getFunctionalInterfaceMethod)
        .flatMap(jsFunctionMethod -> getOverrideInType(typeBinding, jsFunctionMethod))
        .orElse(null);
  }

  private static Optional<MethodDescriptor> getOverrideInType(
      ITypeBinding typeBinding, IMethodBinding methodBinding) {
    return Arrays.stream(typeBinding.getDeclaredMethods())
        .filter(m -> m.overrides(methodBinding))
        .findFirst()
        .map(JdtUtils::createMethodDescriptor);
  }

  private static <T extends TypeDescriptor> ImmutableList<T> createTypeDescriptors(
      List<ITypeBinding> typeBindings, Class<T> clazz) {
    return typeBindings
        .stream()
        .map(typeBinding -> createTypeDescriptor(typeBinding, clazz))
        .collect(toImmutableList());
  }

  private static <T extends TypeDescriptor> ImmutableList<T> createTypeDescriptors(
      ITypeBinding[] typeBindings, Class<T> clazz) {
    return createTypeDescriptors(Arrays.asList(typeBindings), clazz);
  }

  private static ThreadLocal<ITypeBinding> javaLangObjectTypeBinding = new ThreadLocal<>();

  public static void initWellKnownTypes(AST ast, Iterable<ITypeBinding> typeBindings) {
    javaLangObjectTypeBinding.set(ast.resolveWellKnownType("java.lang.Object"));

    if (TypeDescriptors.isInitialized()) {
      return;
    }
    TypeDescriptors.Builder builder = new TypeDescriptors.Builder();
    for (PrimitiveTypeDescriptor typeDescriptor : PrimitiveTypes.TYPES) {
      addPrimitive(ast, builder, typeDescriptor);
    }
    // Add well-known, non-primitive types.
    for (ITypeBinding typeBinding : typeBindings) {
      builder.addReferenceType(createDeclaredTypeDescriptor(typeBinding));
    }
    builder.init();
  }

  private static void addPrimitive(
      AST ast, TypeDescriptors.Builder builder, PrimitiveTypeDescriptor typeDescriptor) {
    DeclaredTypeDescriptor boxedType =
        createDeclaredTypeDescriptor(ast.resolveWellKnownType(typeDescriptor.getBoxedClassName()));
    builder.addPrimitiveBoxedTypeDescriptorPair(typeDescriptor, boxedType);
  }

  /**
   * Since we don't have access to the enclosing class, the proper package and naming cannot be
   * computed here. Instead we have an early normalization pass that traverses the intersection
   * types and sets the correct package and binaryName etc.
   */
  private static final TypeDescriptor createIntersectionType(ITypeBinding typeBinding) {
    checkArgument(isIntersectionType(typeBinding));
    List<DeclaredTypeDescriptor> intersectedTypeDescriptors =
        createTypeDescriptors(typeBinding.getTypeBounds(), DeclaredTypeDescriptor.class);
    return IntersectionTypeDescriptor.newBuilder()
        .setIntersectionTypeDescriptors(intersectedTypeDescriptors)
        .build();
  }

  /**
   * This cache is a Hashtable so is already synchronized and safe to use from multiple threads. We
   * don't need a separate cache for each thread (like interners have) since JDT's ITypeBinding
   * instances (which we are using as keys) are unique per JDT parse.
   */
  @SuppressWarnings("JdkObsolete")
  private static Map<ITypeBinding, DeclaredTypeDescriptor>
      cachedDeclaredTypeDescriptorByTypeBinding = new Hashtable<>();

  // This is only used by TypeProxyUtils, and cannot be used elsewhere. Because to create a
  // TypeDescriptor from a TypeBinding, it should go through the path to check array type.
  private static DeclaredTypeDescriptor createDeclaredType(final ITypeBinding typeBinding) {
    if (cachedDeclaredTypeDescriptorByTypeBinding.containsKey(typeBinding)) {
      return cachedDeclaredTypeDescriptorByTypeBinding.get(typeBinding);
    }

    checkArgument(!typeBinding.isArray());
    checkArgument(!typeBinding.isPrimitive());

    Supplier<ImmutableMap<String, MethodDescriptor>> declaredMethods =
        () -> {
          ImmutableMap.Builder<String, MethodDescriptor> mapBuilder = ImmutableMap.builder();
          for (IMethodBinding methodBinding : typeBinding.getDeclaredMethods()) {
            MethodDescriptor methodDescriptor = JdtUtils.createMethodDescriptor(methodBinding);
            mapBuilder.put(
                // TODO(b/33595109): Using the method declaration signature here is kind of iffy;
                // but needs to be done because parameterized types might make multiple
                // superinterface methods collide which are represented by JDT as different method
                // bindings but with the same signature, e.g.
                //   interface I<U, V extends Serializable> {
                //     void foo(U u);
                //     void foo(V v);
                //   }
                // When considering the type I<A,A>, there are two different method bindings
                // that describe the single method 'void foo(A a)' each with the respective
                // method declaration.
                methodDescriptor.getDeclarationDescriptor().getMethodSignature(), methodDescriptor);
          }
          return mapBuilder.build();
        };

    Supplier<ImmutableList<FieldDescriptor>> declaredFields =
        () ->
            Arrays.stream(typeBinding.getDeclaredFields())
                .map(JdtUtils::createFieldDescriptor)
                .collect(toImmutableList());

    TypeDeclaration typeDeclaration =
        JdtUtils.createDeclarationForType(typeBinding.getTypeDeclaration());

    // Compute these even later
    DeclaredTypeDescriptor typeDescriptor =
        DeclaredTypeDescriptor.newBuilder()
            .setTypeDeclaration(typeDeclaration)
            .setEnclosingTypeDescriptor(
                createDeclaredTypeDescriptor(typeBinding.getDeclaringClass()))
            .setInterfaceTypeDescriptorsFactory(
                () ->
                    createTypeDescriptors(
                        typeBinding.getInterfaces(), DeclaredTypeDescriptor.class))
            .setSingleAbstractMethodDescriptorFactory(
                () -> createMethodDescriptor(typeBinding.getFunctionalInterfaceMethod()))
            .setJsFunctionMethodDescriptorFactory(() -> getJsFunctionMethodDescriptor(typeBinding))
            .setSuperTypeDescriptorFactory(
                () ->
                    (typeDeclaration.isJsEnum()
                        ? TypeDescriptors.get().javaLangObject
                        : createDeclaredTypeDescriptor(typeBinding.getSuperclass())))
            .setTypeArgumentDescriptors(getTypeArgumentTypeDescriptors(typeBinding))
            .setDeclaredFieldDescriptorsFactory(declaredFields)
            .setDeclaredMethodDescriptorsFactory(declaredMethods)
            .build();
    cachedDeclaredTypeDescriptorByTypeBinding.put(typeBinding, typeDescriptor);
    return typeDescriptor;
  }

  private static Kind getKindFromTypeBinding(ITypeBinding typeBinding) {
    if (typeBinding.isEnum() && !typeBinding.isAnonymous()) {
      // Do not consider the anonymous classes that constitute enum values as Enums, only the
      // enum "class" itself is considered Kind.ENUM.
      return Kind.ENUM;
    } else if (typeBinding.isClass() || (typeBinding.isEnum() && typeBinding.isAnonymous())) {
      return Kind.CLASS;
    } else if (typeBinding.isInterface()) {
      return Kind.INTERFACE;
    }
    throw new RuntimeException("Type binding " + typeBinding + " not handled");
  }

  private static String getJsName(final ITypeBinding typeBinding) {
    checkArgument(!typeBinding.isPrimitive());
    return JsInteropAnnotationUtils.getJsName(typeBinding);
  }

  private static String getJsNamespace(
      ITypeBinding typeBinding, PackageInfoCache packageInfoCache) {
    checkArgument(!typeBinding.isPrimitive());
    String jsNamespace = JsInteropAnnotationUtils.getJsNamespace(typeBinding);
    if (jsNamespace != null) {
      return jsNamespace;
    }

    // Maybe namespace is set via package-info file?
    boolean isTopLevelType = typeBinding.getDeclaringClass() == null;
    if (isTopLevelType) {
      return packageInfoCache.getJsNamespace(
          getBinaryNameFromTypeBinding(toTopLevelTypeBinding(typeBinding)));
    }
    return null;
  }

  /**
   * Returns true for the cases where the qualifier an expression that has always the same value and
   * will not trigger class initializers.
   */
  public static boolean isEffectivelyConstant(org.eclipse.jdt.core.dom.Expression expression) {
    switch (expression.getNodeType()) {
      case ASTNode.PARENTHESIZED_EXPRESSION:
        return isEffectivelyConstant(((ParenthesizedExpression) expression).getExpression());
      case ASTNode.SIMPLE_NAME:
      case ASTNode.QUALIFIED_NAME:
        IBinding binding = ((Name) expression).resolveBinding();
        if (binding instanceof IVariableBinding) {
          IVariableBinding variableBinding = (IVariableBinding) binding;
          return !variableBinding.isField() && variableBinding.isEffectivelyFinal();
        }
        // Type expressions are always effectively constant.
        return binding instanceof ITypeBinding;
      case ASTNode.THIS_EXPRESSION:
      case ASTNode.BOOLEAN_LITERAL:
      case ASTNode.CHARACTER_LITERAL:
      case ASTNode.NULL_LITERAL:
      case ASTNode.NUMBER_LITERAL:
      case ASTNode.STRING_LITERAL:
      case ASTNode.TYPE_LITERAL:
        return true;
      default:
        return false;
    }
  }

  public static TypeDeclaration createDeclarationForType(final ITypeBinding typeBinding) {
    if (typeBinding == null) {
      return null;
    }

    checkArgument(typeBinding.getTypeDeclaration() == typeBinding);
    checkArgument(!typeBinding.isArray());
    checkArgument(!typeBinding.isParameterizedType());
    checkArgument(!typeBinding.isTypeVariable());
    checkArgument(!typeBinding.isWildcardType());
    checkArgument(!typeBinding.isCapture());

    PackageInfoCache packageInfoCache = PackageInfoCache.get();

    ITypeBinding topLevelTypeBinding = toTopLevelTypeBinding(typeBinding);
    if (topLevelTypeBinding.isFromSource()) {
      // Let the PackageInfoCache know that this class is Source, otherwise it would have to rummage
      // around in the class path to figure it out and it might even come up with the wrong answer
      // for example if this class has also been globbed into some other library that is a
      // dependency of this one.
      PackageInfoCache.get().markAsSource(getBinaryNameFromTypeBinding(topLevelTypeBinding));
    }

    // Compute these first since they're reused in other calculations.
    String packageName =
        typeBinding.getPackage() == null ? null : typeBinding.getPackage().getName();
    boolean isAbstract = isAbstract(typeBinding);
    boolean isFinal = isFinal(typeBinding);

    Supplier<ImmutableMap<String, MethodDescriptor>> declaredMethods =
        () -> {
          ImmutableMap.Builder<String, MethodDescriptor> mapBuilder = ImmutableMap.builder();
          for (IMethodBinding methodBinding : typeBinding.getDeclaredMethods()) {
            MethodDescriptor methodDescriptor = createMethodDescriptor(methodBinding);
            mapBuilder.put(
                // TODO(b/33595109): Using the method declaration signature here is kind of iffy;
                // but needs to be done because parameterized types might make multiple
                // superinterface methods collide which are represented by JDT as different method
                // bindings but with the same signature, e.g.
                //   interface I<U, V extends Serializable> {
                //     void foo(U u);
                //     void foo(V v);
                //   }
                // When considering the type I<A,A>, there are two different method bindings
                // that describe the single method 'void foo(A a)' each with the respective
                // method declaration.
                methodDescriptor.getDeclarationDescriptor().getMethodSignature(), methodDescriptor);
          }
          return mapBuilder.build();
        };

    Supplier<ImmutableList<FieldDescriptor>> declaredFields =
        () ->
            Arrays.stream(typeBinding.getDeclaredFields())
                .map(JdtUtils::createFieldDescriptor)
                .collect(toImmutableList());

    JsEnumInfo jsEnumInfo = JsInteropUtils.getJsEnumInfo(typeBinding);

    return TypeDeclaration.newBuilder()
        .setClassComponents(getClassComponents(typeBinding))
        .setEnclosingTypeDeclaration(createDeclarationForType(typeBinding.getDeclaringClass()))
        .setInterfaceTypeDescriptorsFactory(
            () -> createTypeDescriptors(typeBinding.getInterfaces(), DeclaredTypeDescriptor.class))
        .setUnparameterizedTypeDescriptorFactory(() -> createDeclaredTypeDescriptor(typeBinding))
        .setHasAbstractModifier(isAbstract)
        .setKind(getKindFromTypeBinding(typeBinding))
        .setCapturingEnclosingInstance(capturesEnclosingInstance(typeBinding))
        .setFinal(isFinal)
        .setFunctionalInterface(typeBinding.getFunctionalInterfaceMethod() != null)
        .setJsFunctionInterface(JsInteropUtils.isJsFunction(typeBinding))
        .setJsType(JsInteropUtils.isJsType(typeBinding))
        .setJsEnumInfo(jsEnumInfo)
        .setNative(JsInteropUtils.isJsNativeType(typeBinding))
        .setAnonymous(typeBinding.isAnonymous())
        .setLocal(isLocal(typeBinding))
        .setSimpleJsName(getJsName(typeBinding))
        .setCustomizedJsNamespace(getJsNamespace(typeBinding, packageInfoCache))
        .setPackageName(packageName)
        .setSuperTypeDescriptorFactory(
            () ->
                (jsEnumInfo != null
                    ? TypeDescriptors.get().javaLangObject
                    : createDeclaredTypeDescriptor(typeBinding.getSuperclass())))
        .setTypeParameterDescriptors(
            getTypeArgumentTypeDescriptors(typeBinding, TypeVariable.class))
        .setVisibility(getVisibility(typeBinding))
        .setDeclaredMethodDescriptorsFactory(declaredMethods)
        .setDeclaredFieldDescriptorsFactory(declaredFields)
        .setUnusableByJsSuppressed(JsInteropAnnotationUtils.isUnusableByJsSuppressed(typeBinding))
        .setDeprecated(isDeprecated(typeBinding))
        .build();
  }

  private JdtUtils() {}
}
