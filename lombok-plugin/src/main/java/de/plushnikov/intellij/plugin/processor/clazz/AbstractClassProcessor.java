package de.plushnikov.intellij.plugin.processor.clazz;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import de.plushnikov.intellij.plugin.problem.LombokProblem;
import de.plushnikov.intellij.plugin.problem.ProblemBuilder;
import de.plushnikov.intellij.plugin.problem.ProblemEmptyBuilder;
import de.plushnikov.intellij.plugin.problem.ProblemNewBuilder;
import de.plushnikov.intellij.plugin.processor.AbstractProcessor;
import de.plushnikov.intellij.plugin.quickfix.PsiQuickFixFactory;
import de.plushnikov.intellij.plugin.thirdparty.LombokUtils;
import de.plushnikov.intellij.plugin.util.PsiAnnotationUtil;
import de.plushnikov.intellij.plugin.util.PsiClassUtil;
import de.plushnikov.intellij.plugin.util.PsiMethodUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Base lombok processor class for class annotations
 *
 * @author Plushnikov Michail
 */
public abstract class AbstractClassProcessor extends AbstractProcessor implements ClassProcessor {

  protected AbstractClassProcessor(@NotNull Class<? extends Annotation> supportedAnnotationClass, @NotNull Class<? extends PsiElement> supportedClass) {
    super(supportedAnnotationClass, supportedClass);
  }

  @NotNull
  @Override
  public List<? super PsiElement> process(@NotNull PsiClass psiClass) {
    List<? super PsiElement> result = Collections.emptyList();

    PsiAnnotation psiAnnotation = PsiAnnotationUtil.findAnnotation(psiClass, getSupportedAnnotation());
    if (null != psiAnnotation) {
      if (validate(psiAnnotation, psiClass, ProblemEmptyBuilder.getInstance())) {
        result = new ArrayList<PsiElement>();
        generatePsiElements(psiClass, psiAnnotation, result);
      }
    }
    return result;
  }

  @NotNull
  public Collection<PsiAnnotation> collectProcessedAnnotations(@NotNull PsiClass psiClass) {
    Collection<PsiAnnotation> result = new ArrayList<PsiAnnotation>();
    PsiAnnotation psiAnnotation = PsiAnnotationUtil.findAnnotation(psiClass, getSupportedAnnotation());
    if (null != psiAnnotation) {
      result.add(psiAnnotation);
    }
    return result;
  }

  @NotNull
  @Override
  public Collection<LombokProblem> verifyAnnotation(@NotNull PsiAnnotation psiAnnotation) {
    Collection<LombokProblem> result = Collections.emptyList();
    // check first for fields, methods and filter it out, because PsiClass is parent of all annotations and will match other parents too
    PsiElement psiElement = PsiTreeUtil.getParentOfType(psiAnnotation, PsiField.class, PsiMethod.class, PsiClass.class);
    if (psiElement instanceof PsiClass) {
      result = new ArrayList<LombokProblem>(1);
      validate(psiAnnotation, (PsiClass) psiElement, new ProblemNewBuilder(result));
    }

    return result;
  }

  protected abstract boolean validate(@NotNull PsiAnnotation psiAnnotation, @NotNull PsiClass psiClass, @NotNull ProblemBuilder builder);

  protected abstract void generatePsiElements(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation, @NotNull List<? super PsiElement> target);

  protected void validateCallSuperParam(PsiAnnotation psiAnnotation, PsiClass psiClass, ProblemBuilder builder, String generatedMethodName) {
    Boolean callSuperProperty = PsiAnnotationUtil.getDeclaredAnnotationValue(psiAnnotation, "callSuper", Boolean.class);
    if (null == callSuperProperty && PsiClassUtil.hasSuperClass(psiClass)) {
      builder.addWarning("Generating " + generatedMethodName + " implementation but without a call to superclass, " +
          "even though this class does not extend java.lang.Object." +
          "If this is intentional, add '(callSuper=false)' to your type.",
          PsiQuickFixFactory.createChangeAnnotationParameterFix(psiAnnotation, "callSuper", "true"),
          PsiQuickFixFactory.createChangeAnnotationParameterFix(psiAnnotation, "callSuper", "false"));
    }
  }

  protected void validateOfParam(PsiClass psiClass, ProblemBuilder builder, PsiAnnotation psiAnnotation, Collection<String> ofProperty) {
    for (String fieldName : ofProperty) {
      if (!StringUtil.isEmptyOrSpaces(fieldName)) {
        PsiField fieldByName = psiClass.findFieldByName(fieldName, false);
        if (null == fieldByName) {
          final String newPropertyValue = calcNewPropertyValue(ofProperty, fieldName);
          builder.addWarning(String.format("The field '%s' does not exist", fieldName),
              PsiQuickFixFactory.createChangeAnnotationParameterFix(psiAnnotation, "of", newPropertyValue));
        }
      }
    }
  }

  protected void validateExcludeParam(PsiClass psiClass, ProblemBuilder builder, PsiAnnotation psiAnnotation, Collection<String> excludeProperty) {
    for (String fieldName : excludeProperty) {
      if (!StringUtil.isEmptyOrSpaces(fieldName)) {
        PsiField fieldByName = psiClass.findFieldByName(fieldName, false);
        if (null == fieldByName) {
          final String newPropertyValue = calcNewPropertyValue(excludeProperty, fieldName);
          builder.addWarning(String.format("The field '%s' does not exist", fieldName),
              PsiQuickFixFactory.createChangeAnnotationParameterFix(psiAnnotation, "exclude", newPropertyValue));
        } else {
          if (fieldName.startsWith(LombokUtils.LOMBOK_INTERN_FIELD_MARKER) || fieldByName.hasModifierProperty(PsiModifier.STATIC)) {
            final String newPropertyValue = calcNewPropertyValue(excludeProperty, fieldName);
            builder.addWarning(String.format("The field '%s' would have been excluded anyway", fieldName),
                PsiQuickFixFactory.createChangeAnnotationParameterFix(psiAnnotation, "exclude", newPropertyValue));
          }
        }
      }
    }
  }

  private String calcNewPropertyValue(Collection<String> allProperties, String fieldName) {
    String result = null;
    final Collection<String> restProperties = new ArrayList<String>(allProperties);
    restProperties.remove(fieldName);

    if (!restProperties.isEmpty()) {
      final StringBuilder builder = new StringBuilder();
      builder.append('{');
      for (final String property : restProperties) {
        builder.append('"').append(property).append('"').append(',');
      }
      builder.deleteCharAt(builder.length() - 1);
      builder.append('}');

      result = builder.toString();
    }
    return result;
  }

  protected Collection<PsiField> filterFields(@NotNull PsiClass psiClass, @NotNull PsiAnnotation psiAnnotation, boolean filterTransient) {
    final Collection<String> excludeProperty = makeSet(PsiAnnotationUtil.getAnnotationValues(psiAnnotation, "exclude", String.class));
    final Collection<String> ofProperty = makeSet(PsiAnnotationUtil.getAnnotationValues(psiAnnotation, "of", String.class));

    final Collection<PsiField> psiFields = PsiClassUtil.collectClassFieldsIntern(psiClass);

    final Collection<PsiField> result = new ArrayList<PsiField>(psiFields.size());

    for (PsiField classField : psiFields) {
      final String fieldName = classField.getName();
      if (classField.hasModifierProperty(PsiModifier.STATIC) || (filterTransient && classField.hasModifierProperty(PsiModifier.TRANSIENT))) {
        continue;
      }
      if (excludeProperty.contains(fieldName)) {
        continue;
      }
      if (!ofProperty.isEmpty() && !ofProperty.contains(fieldName)) {
        continue;
      }

      if (fieldName.startsWith(LombokUtils.LOMBOK_INTERN_FIELD_MARKER) && !ofProperty.contains(fieldName)) {
        continue;
      }

      result.add(classField);
    }
    return result;
  }

  protected String buildAttributeNameString(boolean doNotUseGetters, @NotNull PsiField classField, @NotNull PsiClass psiClass) {
    final String fieldName = classField.getName();
    if (doNotUseGetters) {
      return fieldName;
    } else {
      final String getterName = getGetterName(classField);

      boolean hasGetter = PsiMethodUtil.hasMethodByName(PsiClassUtil.collectClassMethodsIntern(psiClass), getterName);

      return hasGetter ? getterName + "()" : fieldName;
    }
  }

  protected Collection<String> makeSet(@Nullable Collection<String> exclude) {
    if (null == exclude || exclude.isEmpty()) {
      return Collections.emptySet();
    }
    return new HashSet<String>(exclude);
  }
}
