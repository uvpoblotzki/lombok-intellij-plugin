package de.plushnikov.intellij.plugin.processor.field;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.util.StringBuilderSpinAllocator;
import de.plushnikov.intellij.plugin.problem.ProblemBuilder;
import de.plushnikov.intellij.plugin.psi.LombokLightMethodBuilder;
import de.plushnikov.intellij.plugin.quickfix.PsiQuickFixFactory;
import de.plushnikov.intellij.plugin.thirdparty.LombokUtils;
import de.plushnikov.intellij.plugin.util.LombokProcessorUtil;
import de.plushnikov.intellij.plugin.util.PsiAnnotationUtil;
import de.plushnikov.intellij.plugin.util.PsiClassUtil;
import de.plushnikov.intellij.plugin.util.PsiMethodUtil;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Wither;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

import static de.plushnikov.intellij.plugin.util.PsiElementUtil.typesAreEquivalent;
import static java.lang.String.format;

public class WitherFieldProcessor extends AbstractFieldProcessor {

  public WitherFieldProcessor() {
    super(Wither.class, PsiMethod.class);
  }

  @Override
  protected boolean validate(@NotNull PsiAnnotation psiAnnotation, @NotNull PsiField psiField, @NotNull ProblemBuilder builder) {

    boolean valid = validateVisibility(psiAnnotation);
    valid &= validNonStatic(psiField, psiAnnotation, builder);
    valid &= validHasConstructor(psiField, builder);
    valid &= validIsWitherUnique(psiField, psiAnnotation, builder);

    return valid;
  }

  protected boolean validateVisibility(@NotNull PsiAnnotation psiAnnotation) {
    final String methodVisibility = LombokProcessorUtil.getMethodModifier(psiAnnotation);
    return null != methodVisibility;
  }

  @Override
  protected void generatePsiElements(@NotNull PsiField psiField, @NotNull PsiAnnotation psiAnnotation, @NotNull List<? super PsiElement> target) {
    String methodModifier = LombokProcessorUtil.getMethodModifier(psiAnnotation);
    if (methodModifier != null) {
      AccessorsInfo accessorsInfo = AccessorsInfo.build(psiField);
      PsiMethod method = createWitherMethod(psiField, methodModifier, accessorsInfo);
      if (method != null) {
        target.add(method);
      }
    }
  }

  private String witherName(String fieldName) {
    final String suffix = fieldName.startsWith("is") && Character.isUpperCase(fieldName.charAt(2)) ?
        fieldName.substring(2) :
        fieldName;
    return "with" + StringUtil.capitalize(suffix);
  }

  private String secondWitherName(String fieldName) {
    return "with" + StringUtil.capitalize(fieldName);
  }

  private boolean validNonStatic(PsiField field, PsiAnnotation annotation, @NotNull final ProblemBuilder builder) {
    if (field.hasModifierProperty(PsiModifier.STATIC)) {
      builder.addError(format("'@%s' on static field is not allowed", annotation.getQualifiedName()),
          PsiQuickFixFactory.createModifierListFix(field, PsiModifier.STATIC, false, false));
      return false;
    }
    return true;
  }

  private boolean hasRightConstructor(PsiField field) {
    return null != getRightConstructor(field);
  }

  private PsiMethod getRightConstructor(PsiField field) {
    PsiClass psiClass = field.getContainingClass();
    if (psiClass != null) {
      for (PsiMethod psiMethod : PsiClassUtil.collectClassConstructorIntern(psiClass)) {
        if (hasParam(field, psiMethod)) {
          return psiMethod;
        }
      }
    }
    return null;
  }

  private boolean hasParam(PsiField field, PsiMethod psiMethod) {
    for (PsiParameter param : psiMethod.getParameterList().getParameters()) {
      if (typesAreEquivalent(param.getType(), field.getType())) {
        return true;
      }
    }
    return false;
  }

  private boolean validHasConstructor(@NotNull final PsiField field, @NotNull final ProblemBuilder builder) {
    final PsiClass fieldContainingClass = field.getContainingClass();
    if (null == fieldContainingClass) {
      return false;
    }
    if (PsiAnnotationUtil.isAnnotatedWith(fieldContainingClass, AllArgsConstructor.class)) {
      return true;
    }
    if (hasRightConstructor(field)) {
      return true;
    }
    final boolean hasRequiredArgsConstAnnotation = PsiAnnotationUtil.isAnnotatedWith(fieldContainingClass, RequiredArgsConstructor.class);
    final boolean isFinal = field.hasModifierProperty(PsiModifier.FINAL);
    final boolean hasNonNullAnnotation = PsiAnnotationUtil.isAnnotatedWith(field, NonNull.class);

    if (hasRequiredArgsConstAnnotation && (isFinal || hasNonNullAnnotation)) {
      return true;
    } else {
      builder.addWarning(format("Compilation will fail : no constructor with a parameter of type '%s' was found",
          field.getType().getCanonicalText()));
      return false;
    }
  }

  private boolean validIsWitherUnique(@NotNull PsiField field, @NotNull PsiAnnotation annotation, @NotNull final ProblemBuilder builder) {
    final PsiClass fieldContainingClass = field.getContainingClass();
    if (field.getName() != null && fieldContainingClass != null) {
      if (PsiMethodUtil.hasSimilarMethod(PsiClassUtil.collectClassMethodsIntern(fieldContainingClass), witherName(field.getName()), 1)
          || PsiMethodUtil.hasSimilarMethod(PsiClassUtil.collectClassMethodsIntern(fieldContainingClass), secondWitherName(field.getName()), 1)) {
        builder.addWarning(
            format("No '@%s' generated : a method named '%s' taking one parameter already exists",
                annotation.getQualifiedName(),
                witherName(field.getName())));
        return false;
      }
    }
    return true;
  }

  public PsiMethod createWitherMethod(@NotNull PsiField psiField, @NotNull String methodModifier, @NotNull AccessorsInfo accessorsInfo) {
    LombokLightMethodBuilder result = null;
    final PsiClass psiFieldContainingClass = psiField.getContainingClass();
    if (psiFieldContainingClass != null) {
      final PsiType returnType = PsiClassUtil.getTypeWithGenerics(psiFieldContainingClass);
      final String psiFieldName = psiField.getName();
      result = new LombokLightMethodBuilder(psiField.getManager(), witherName(accessorsInfo.removePrefix(psiFieldName)))
          .withMethodReturnType(returnType)
          .withContainingClass(psiFieldContainingClass)
          .withParameter(psiFieldName, psiField.getType())
          .withNavigationElement(psiField)
          .withModifier(methodModifier);

      final String paramString = getConstructorCall(psiField, psiFieldContainingClass);
      final String blockText = String.format("return this.%s == %s ? this : new %s(%s);", psiFieldName, psiFieldName, returnType.getCanonicalText(), paramString);
      result.withBody(PsiMethodUtil.createCodeBlockFromText(blockText, psiFieldContainingClass));

      copyAnnotations(psiField, result.getModifierList(),
          LombokUtils.NON_NULL_PATTERN, LombokUtils.NULLABLE_PATTERN, LombokUtils.DEPRECATED_PATTERN);
    }
    return result;
  }

  private String getConstructorCall(@NotNull PsiField psiField, @NotNull PsiClass psiClass) {
    final StringBuilder paramString = StringBuilderSpinAllocator.alloc();
    try {
      final Collection<PsiField> psiFields = PsiClassUtil.collectClassFieldsIntern(psiClass);
      for (PsiField classField : psiFields) {
        final String classFieldName = classField.getName();
        if (classFieldName.startsWith(LombokUtils.LOMBOK_INTERN_FIELD_MARKER)) {
          continue;
        }
        if (classField.hasModifierProperty(PsiModifier.STATIC)) {
          continue;
        }
        if (classField.hasModifierProperty(PsiModifier.FINAL) && null != classField.getInitializer()) {
          continue;
        }

        if (classField.equals(psiField)) {
          paramString.append(classFieldName);
        } else {
          paramString.append("this.").append(classFieldName);
        }
        paramString.append(',');
      }
      if (paramString.length() > 1) {
        paramString.deleteCharAt(paramString.length() - 1);
      }
      return paramString.toString();
    } finally {
      StringBuilderSpinAllocator.dispose(paramString);
    }
  }
}
