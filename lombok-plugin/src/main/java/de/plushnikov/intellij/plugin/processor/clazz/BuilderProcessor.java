package de.plushnikov.intellij.plugin.processor.clazz;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import de.plushnikov.intellij.plugin.problem.ProblemBuilder;
import de.plushnikov.intellij.plugin.processor.clazz.constructor.AllArgsConstructorProcessor;
import de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder;
import de.plushnikov.intellij.plugin.util.BuilderUtil;
import de.plushnikov.intellij.plugin.util.PsiClassUtil;
import de.plushnikov.intellij.plugin.util.PsiMethodUtil;
import lombok.experimental.Builder;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * Inspect and validate @Builder lombok annotation on a class
 * Creates methods for a builder pattern for initializing a class
 *
 * @author Tomasz Kalkosiński
 */
public class BuilderProcessor extends BuilderInnerClassProcessor {

  public BuilderProcessor() {
    super(Builder.class, PsiMethod.class);
  }

  @Override
  protected boolean validate(@NotNull PsiAnnotation psiAnnotation, @NotNull PsiClass psiClass, @NotNull ProblemBuilder builder) {
    return validateInternal(psiAnnotation, psiClass, builder, false);
  }

  protected void generatePsiElements(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation, @NotNull List<? super PsiElement> target) {
    final Collection<PsiMethod> definedConstructors = PsiClassUtil.collectClassConstructorIntern(psiClass);
    // Create all args constructor only if there is no declared constructor
    if (definedConstructors.isEmpty()) {
      final AllArgsConstructorProcessor allArgsConstructorProcessor = new AllArgsConstructorProcessor();
      target.addAll(allArgsConstructorProcessor.createAllArgsConstructor(psiClass, PsiModifier.DEFAULT, psiAnnotation));
    }

    String innerClassName = BuilderUtil.createBuilderClassName(psiAnnotation, psiClass);
    PsiClass innerClassByName = PsiClassUtil.getInnerClassByName(psiClass, innerClassName);
    assert innerClassByName != null; // BuilderInnerClassProcessor should run first
    if (null != innerClassByName) {
      final String builderMethodName = BuilderUtil.createBuilderMethodName(psiAnnotation);
      if (!PsiMethodUtil.hasMethodByName(PsiClassUtil.collectClassMethodsIntern(psiClass), builderMethodName)) {
        LombokLightMethodBuilder method = new LombokLightMethodBuilder(psiClass.getManager(), builderMethodName)
            .withMethodReturnType(PsiClassUtil.getTypeWithGenerics(innerClassByName))
            .withContainingClass(psiClass)
            .withNavigationElement(psiAnnotation);
        method.withModifier(PsiModifier.STATIC);
        method.withModifier(PsiModifier.PUBLIC);
        target.add(method);
      }
    }
  }
}
