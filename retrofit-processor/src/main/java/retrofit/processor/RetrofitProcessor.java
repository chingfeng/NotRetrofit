/*
 * Copyright (C) 2015 8tory, Inc.
 * Copyright (C) 2012 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit.processor;

import retrofit.http.Retrofit;
import retrofit.RetrofitError;
import retrofit.Callback;
import com.google.auto.service.AutoService;
import com.google.common.base.Functions;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.beans.Introspector;
import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.annotation.Generated;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.lang.model.type.MirroredTypeException;

/**
 * Javac annotation processor (compiler plugin) for value types; user code never references this
 * class.
 *
 * @author Éamonn McManus
 * @see retrofit.http.Retrofit
 */
@AutoService(Processor.class)
public class RetrofitProcessor extends AbstractProcessor {
  public RetrofitProcessor() {
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return ImmutableSet.of(Retrofit.class.getName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  private ErrorReporter errorReporter;

  /**
   * Qualified names of {@code @Retrofit} classes that we attempted to process but had to abandon
   * because we needed other types that they referenced and those other types were missing.
   */
  private final List<String> deferredTypeNames = new ArrayList<String>();

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    errorReporter = new ErrorReporter(processingEnv);
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    List<TypeElement> deferredTypes = new ArrayList<TypeElement>();
    for (String deferred : deferredTypeNames) {
      deferredTypes.add(processingEnv.getElementUtils().getTypeElement(deferred));
    }
    if (roundEnv.processingOver()) {
      // This means that the previous round didn't generate any new sources, so we can't have found
      // any new instances of @Retrofit; and we can't have any new types that are the reason a type
      // was in deferredTypes.
      for (TypeElement type : deferredTypes) {
        errorReporter.reportError("Did not generate @Retrofit class for " + type.getQualifiedName()
            + " because it references undefined types", type);
      }
      return false;
    }
    Collection<? extends Element> annotatedElements =
        roundEnv.getElementsAnnotatedWith(Retrofit.class);
    List<TypeElement> types = new ImmutableList.Builder<TypeElement>()
        .addAll(deferredTypes)
        .addAll(ElementFilter.typesIn(annotatedElements))
        .build();
    deferredTypeNames.clear();
    for (TypeElement type : types) {
      try {
        processType(type);
      } catch (AbortProcessingException e) {
        // We abandoned this type; continue with the next.
      } catch (MissingTypeException e) {
        // We abandoned this type, but only because we needed another type that it references and
        // that other type was missing. It is possible that the missing type will be generated by
        // further annotation processing, so we will try again on the next round (perhaps failing
        // again and adding it back to the list). We save the name of the @Retrofit type rather
        // than its TypeElement because it is not guaranteed that it will be represented by
        // the same TypeElement on the next round.
        deferredTypeNames.add(type.getQualifiedName().toString());
      } catch (RuntimeException e) {
        // Don't propagate this exception, which will confusingly crash the compiler.
        // Instead, report a compiler error with the stack trace.
        String trace = Throwables.getStackTraceAsString(e);
        errorReporter.reportError("@Retrofit processor threw an exception: " + trace, type);
      }
    }
    return false;  // never claim annotation, because who knows what other processors want?
  }

  private String generatedClassName(TypeElement type, String prefix) {
    String name = type.getSimpleName().toString();
    while (type.getEnclosingElement() instanceof TypeElement) {
      type = (TypeElement) type.getEnclosingElement();
      name = type.getSimpleName() + "_" + name;
    }
    String pkg = TypeSimplifier.packageNameOf(type);
    String dot = pkg.isEmpty() ? "" : ".";
    return pkg + dot + prefix + name;
  }

  private String generatedSubclassName(TypeElement type) {
    return generatedClassName(type, "Retrofit_");
  }

  public interface Action1<T> {
      void call(T t);
  }

  private void onAnnotationForProperty(AnnotationMirror annotation) {
      onAnnotationForProperty.call(annotation);
  }

  private Action1<? super AnnotationMirror> onAnnotationForProperty;

  private void annotationForProperty(Action1<? super AnnotationMirror> onAnnotationForProperty) {
      this.onAnnotationForProperty = onAnnotationForProperty;
  }

  public static class Part {
    private final String name;
    private final String mimeType;
    private final boolean isFile;
    private final boolean isTypedFile;
    private final boolean isTypedString;
    private final boolean isTypedByteArray;

    public Part(String name, String mimeType, boolean isFile, boolean isTypedFile, boolean isTypedString, boolean isTypedByteArray) {
      this.name = name;
      this.mimeType = mimeType;
      this.isFile = isFile;
      this.isTypedFile = isTypedFile;
      this.isTypedString = isTypedString;
      this.isTypedByteArray = isTypedByteArray;
    }

    public String getName() {
      return name;
    }

    public String getMimeType() {
      return mimeType;
    }
    public boolean isFile() {
      return isFile;
    }
    public boolean isTypedFile() {
      return isTypedFile;
    }
    public boolean isTypedString() {
      return isTypedString;
    }
    public boolean isTypedByteArray() {
      return isTypedByteArray;
    }
  }

  /**
   * A property of an {@code @Retrofit} class, defined by one of its abstract methods.
   * An instance of this class is made available to the Velocity template engine for
   * each property. The public methods of this class define JavaBeans-style properties
   * that are accessible from templates. For example {@link #getType()} means we can
   * write {@code $p.type} for a Velocity variable {@code $p} that is a {@code Property}.
   */
  public static class Property {
    private final String name;
    private final String identifier;
    private final ExecutableElement method;
    private final String type;
    private String typeArgs;
    private String typeArgs2;
    private final ImmutableList<String> annotations;
    private final String args;
    private final String path;
    private final Map<String, String> queries;
    private final List<String> queryMaps;
    private final List<String> queryBundles;
    private final boolean isGet;
    private final boolean isPut;
    private final boolean isPost;
    private final boolean isDelete;
    private final boolean isHead;
    private final boolean isObservable; // returnType Observable
    private final boolean isResponseType; // returnType == Response || returnType<Response>
    private final boolean isVoid;
    private final boolean isBlocking;
    private final String body;
    private final String callbackType;
    private final TypeMirror callbackTypeMirror;
    private final String callbackArg;
    private final ProcessingEnvironment processingEnv;
    private final TypeSimplifier typeSimplifier;
    private final List<String> permissions;
    private final boolean isAuthenticated;
    private final boolean isSingletonRequestInterceptor;
    private final Map<String, String> headers;
    private final Map<String, String> fields;
    private final Map<String, Part> parts;
    private String callbackName;
    public final String converter;
    public String gsonConverter = "";
    public final String errorHandler;
    public final String logLevel;
    public final String requestInterceptor;

    Property(
        String name,
        String identifier,
        ExecutableElement method,
        String type,
        TypeSimplifier typeSimplifier,
        ProcessingEnvironment processingEnv
        ) {
      this.name = name;
      this.identifier = identifier;
      this.method = method;
      this.type = type;
      this.typeSimplifier = typeSimplifier;
      this.processingEnv = processingEnv;
      this.annotations = buildAnnotations(typeSimplifier);
      this.args = formalTypeArgsString(method);
      this.path = buildPath(method);
      this.queries = buildQueries(method);
      this.queryMaps = buildQueryMaps(method);
      this.queryBundles = buildQueryBundles(method);
      this.isGet = buildIsGet(method);
      this.isPut = buildIsPut(method);
      this.isPost = buildIsPost(method);
      this.isDelete = buildIsDelete(method);
      this.isHead = buildIsHead(method);
      this.isAuthenticated = buildIsAuthenticated(method);
      this.isObservable = buildIsObservable(method);
      this.body = buildBody(method);
      this.callbackTypeMirror = buildCallbackTypeMirror(method);
      //this.callbackType = buildTypeArgument(callbackTypeMirror);
      this.callbackType = buildCallbackTypeArgument(method);
      this.callbackArg = buildTypeArguments(callbackType);
      this.isBlocking = !isCallback() && !isObservable();
      this.isResponseType = buildIsResponseType(method);
      if (isObservable()) {
        this.typeArgs = buildTypeArguments(type); // Observable<List<String>> -> List<String>
        this.typeArgs2 = buildTypeArguments(typeArgs); // Observable<List<String>> -> String
      } else if (isCallback()) {
        this.typeArgs = callbackType;  // Callback<List<String>> -> List<String>
        this.typeArgs2 = buildTypeArguments(typeArgs); // Callback<List<String>> -> String
      } else { // isBlocking
        this.typeArgs = type;
      }
      if ("".equals(typeArgs)) typeArgs = callbackType;
      this.isVoid = buildIsVoid(method);
      this.permissions = buildPermissions(method);
      this.headers = buildHeaders(method);
      this.fields = buildFields(method);
      this.parts = buildParts(method);
      this.converter = buildConverter(method);
      this.errorHandler = buildErrorHandler(method);
      this.logLevel = buildLogLevel(method);
      this.requestInterceptor = buildRequestInterceptor(method);
      this.isSingletonRequestInterceptor = buildIsSingletonRequestInterceptor(method);
    }

    private String buildRequestInterceptor(ExecutableElement method) {
      String name = "";
      Retrofit.RequestInterceptor requestInterceptorAnnotation = method.getAnnotation(Retrofit.RequestInterceptor.class);
      if (requestInterceptorAnnotation != null) {
        TypeMirror requestInterceptor = null;
        try {
          requestInterceptor = getTypeMirror(processingEnv, requestInterceptorAnnotation.value());
        } catch (MirroredTypeException mte) {
          // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
          requestInterceptor = mte.getTypeMirror();
        }
        name = typeSimplifier.simplify(requestInterceptor);
      }
      return name;
    }

    private String buildConverter(ExecutableElement method) {
      String converterName = "";
      Retrofit.Converter converterAnnotation = method.getAnnotation(Retrofit.Converter.class);
      if (converterAnnotation != null) {
        TypeMirror converter = null;
        try {
          converter = getTypeMirror(processingEnv, converterAnnotation.value());
        } catch (MirroredTypeException mte) {
          // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
          converter = mte.getTypeMirror();
        }
        converterName = typeSimplifier.simplify(converter);
        TypeMirror gsonConverterType = getTypeMirror(processingEnv, retrofit.converter.GsonConverter.class);
        Types typeUtils = processingEnv.getTypeUtils();
        if (typeUtils.isSubtype(gsonConverterType, converter)) {
          this.gsonConverter = converterName;
        }
      }
      return converterName;
    }

    private String buildErrorHandler(ExecutableElement method) {
      String name = "";
      Retrofit.ErrorHandler errorHandlerAnnotation = method.getAnnotation(Retrofit.ErrorHandler.class);
      if (errorHandlerAnnotation != null) {
        TypeMirror errorHandler = null;
        try {
          errorHandler = getTypeMirror(processingEnv, errorHandlerAnnotation.value());
        } catch (MirroredTypeException mte) {
          // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
          errorHandler = mte.getTypeMirror();
        }
        name = typeSimplifier.simplify(errorHandler);
      }
      return name;
    }

    private String buildLogLevel(ExecutableElement method) {
      Retrofit.LogLevel logLevelAnnotation = method.getAnnotation(Retrofit.LogLevel.class);
      if (logLevelAnnotation != null) {
        return ""; // TODO
      }
      return "";
    }

    private boolean buildIsObservable(ExecutableElement method) {
      Types typeUtils = processingEnv.getTypeUtils();
      TypeMirror obsType = getTypeMirror(processingEnv, rx.Observable.class);
      TypeMirror returnType = method.getReturnType();

      if (returnType instanceof DeclaredType) {
        List<? extends TypeMirror> params = ((DeclaredType) returnType).getTypeArguments();
        if (params.size() == 1) {
          obsType = typeUtils.getDeclaredType((TypeElement) typeUtils.asElement(obsType), new TypeMirror[] {params.get(0)});

          return typeUtils.isSubtype(returnType, obsType);
        }
      }

      return false;
    }

    private boolean buildIsVoid(ExecutableElement method) {
      return method.getReturnType().getKind() == TypeKind.VOID;
    }

    private boolean buildIsResponseType(ExecutableElement method) {
      Types typeUtils = processingEnv.getTypeUtils();
      TypeMirror responseType = getTypeMirror(processingEnv, retrofit.client.Response.class);
      TypeMirror returnType = method.getReturnType();

      if (isObservable()) {
        List<? extends TypeMirror> params = ((DeclaredType) returnType).getTypeArguments();
        if (params.size() == 1) { // Observable<Response>
          returnType = params.get(0); // Response
          return typeUtils.isSubtype(returnType, responseType);
        }
      } else if (isCallback()) {
        List<? extends TypeMirror> params = ((DeclaredType) callbackTypeMirror).getTypeArguments();
        if (params.size() == 1) { //  Callback<Response>
          returnType = params.get(0); // Response
          return typeUtils.isSubtype(returnType, responseType);
        }
      }

      return typeUtils.isSubtype(returnType, responseType); // isBlocking()
    }

    private String buildTypeArguments(String type) {
      Pattern pattern = Pattern.compile( "<(.*)>" );
      Matcher m = pattern.matcher(type);
      if (m.find()) return m.group(1);
      return "";
    }

    private TypeMirror buildCallbackTypeMirror(ExecutableElement method) {
      Types typeUtils = processingEnv.getTypeUtils();
      TypeMirror callback = getTypeMirror(processingEnv, Callback.class);

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        TypeMirror type = parameter.asType();
        if (type instanceof DeclaredType) {
          List<? extends TypeMirror> params = ((DeclaredType) type).getTypeArguments();
          if (params.size() == 1) {
            callback = typeUtils.getDeclaredType((TypeElement) typeUtils.asElement(callback), new TypeMirror[] {params.get(0)});

            if (typeUtils.isSubtype(type, callback)) {
              this.callbackName = parameter.getSimpleName().toString();
              return callback;
            }
          }
        }
      }
      return null;
    }

    private String buildCallbackTypeArgument(ExecutableElement method) {
      Types typeUtils = processingEnv.getTypeUtils();
      TypeMirror callback = getTypeMirror(processingEnv, Callback.class);

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        TypeMirror type = parameter.asType();
        if (type instanceof DeclaredType) {
          List<? extends TypeMirror> params = ((DeclaredType) type).getTypeArguments();
          if (params.size() == 1) {
            callback = typeUtils.getDeclaredType((TypeElement) typeUtils.asElement(callback), new TypeMirror[] {params.get(0)});

            if (typeUtils.isSubtype(type, callback)) {
              return typeSimplifier.simplify(params.get(0));
            }
          }
        }
      }
      return "";
    }

    private String buildTypeArgument(TypeMirror type) {
      if (type != null) {
        List<? extends TypeMirror> params = ((DeclaredType) type).getTypeArguments();
        return typeSimplifier.simplify(params.get(0));
      }
      return "";
    }

    public boolean buildIsGet(ExecutableElement method) {
      // TODO duplicated routine
      return method.getAnnotation(Retrofit.GET.class) != null || method.getAnnotation(retrofit.http.HEAD.class) != null;
    }

    public boolean buildIsPost(ExecutableElement method) {
      // TODO duplicated routine
      return method.getAnnotation(Retrofit.POST.class) != null || method.getAnnotation(retrofit.http.HEAD.class) != null;
    }

    public boolean buildIsPut(ExecutableElement method) {
      // TODO duplicated routine
      return method.getAnnotation(Retrofit.PUT.class) != null || method.getAnnotation(retrofit.http.HEAD.class) != null;
    }

    public boolean buildIsDelete(ExecutableElement method) {
      // TODO duplicated routine
      return method.getAnnotation(Retrofit.DELETE.class) != null || method.getAnnotation(retrofit.http.HEAD.class) != null;
    }

    public boolean buildIsHead(ExecutableElement method) {
      // TODO duplicated routine
      return method.getAnnotation(Retrofit.HEAD.class) != null || method.getAnnotation(retrofit.http.HEAD.class) != null;
    }

    public boolean buildIsAuthenticated(ExecutableElement method) {
      return method.getAnnotation(Retrofit.Authenticated.class) != null;
    }

    public boolean buildIsSingletonRequestInterceptor(ExecutableElement method) {
      javax.inject.Singleton singleton = null;
      Retrofit.RequestInterceptor requestInterceptorAnnotation = method.getAnnotation(Retrofit.RequestInterceptor.class);
      if (requestInterceptorAnnotation != null) {
        TypeMirror requestInterceptor = null;
        try {
          requestInterceptor = getTypeMirror(processingEnv, requestInterceptorAnnotation.value());
        } catch (MirroredTypeException mte) {
          // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
          requestInterceptor = mte.getTypeMirror();
        }

        Types typeUtils = processingEnv.getTypeUtils();
        TypeElement requestInterceptorType = (TypeElement) typeUtils.asElement(requestInterceptor);
        singleton = requestInterceptorType.getAnnotation(javax.inject.Singleton.class);
      }
      return singleton != null;
    }

    public String buildBody(ExecutableElement method) {
      String body = "";

      if (method.getAnnotation(Retrofit.POST.class) == null && method.getAnnotation(retrofit.http.POST.class) == null) return body;

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        if (parameter.getAnnotation(Retrofit.Body.class) != null || method.getAnnotation(retrofit.http.Body.class) != null) {
          body = parameter.getSimpleName().toString();
        }
      }
      return body;
    }

    public List<String> buildPermissions(ExecutableElement method) {
      Retrofit.GET get = method.getAnnotation(Retrofit.GET.class);
      Retrofit.PUT put = method.getAnnotation(Retrofit.PUT.class);
      Retrofit.POST post = method.getAnnotation(Retrofit.POST.class);
      Retrofit.DELETE delete = method.getAnnotation(Retrofit.DELETE.class);
      Retrofit.HEAD head = method.getAnnotation(Retrofit.HEAD.class);
      if (get != null) return Arrays.asList(get.permissions());
      if (put != null) return Arrays.asList(put.permissions());
      if (post != null) return Arrays.asList(post.permissions());
      if (delete != null) return Arrays.asList(delete.permissions());
      if (head != null) return Arrays.asList(head.permissions());
      return Collections.emptyList();
    }

    public Map<String, String> buildHeaders(ExecutableElement method) {
      Map<String, String> map = new HashMap<String, String>();
      String[] headers;

      Retrofit.Headers headersAnnotation = method.getAnnotation(Retrofit.Headers.class);
      retrofit.http.Headers headers1Annotation = method.getAnnotation(retrofit.http.Headers.class);
      if (headersAnnotation != null) {
        headers = headersAnnotation.value();
      } else if (headers1Annotation != null) {
        headers = headers1Annotation.value();
      } else {
        return Collections.emptyMap();
      }

      for (String header : headers) {
        String[] tokens = header.split(":");
        map.put(tokens[0].trim(), "\"" + tokens[1].trim() + "\"");
      }

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        Retrofit.Header header = parameter.getAnnotation(Retrofit.Header.class);
        retrofit.http.Header header1 = parameter.getAnnotation(retrofit.http.Header.class);
        String key = null;
        if (header != null) {
          key = header.value().equals("") ? parameter.getSimpleName().toString() : header.value();
        } else if (header1 != null) {
          key = header1.value().equals("") ? parameter.getSimpleName().toString() : header1.value();
        } else {
          continue;
        }
        map.put(key, parameter.getSimpleName().toString());
      }

      return map;
    }

    public Map<String, String> buildFields(ExecutableElement method) {
      Map<String, String> map = new HashMap<String, String>();
      // TODO FieldMap

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        Retrofit.Field field = parameter.getAnnotation(Retrofit.Field.class);
        retrofit.http.Field field1 = parameter.getAnnotation(retrofit.http.Field.class);
        String key = null;
        if (field != null) {
          key = field.value().equals("") ? parameter.getSimpleName().toString() : field.value();
        } else if (field1 != null) {
          key = field1.value().equals("") ? parameter.getSimpleName().toString() : field1.value();
        } else {
          continue;
        }
        map.put(key, parameter.getSimpleName().toString());
      }

      return map;
    }

    public Map<String, Part> buildParts(ExecutableElement method) {
      Map<String, Part> map = new HashMap<String, Part>();
      Types typeUtils = processingEnv.getTypeUtils();
      TypeMirror fileType = getTypeMirror(processingEnv, java.io.File.class);
      TypeMirror typedFileType = getTypeMirror(processingEnv, retrofit.mime.TypedFile.class);
      TypeMirror typedStringType = getTypeMirror(processingEnv, retrofit.mime.TypedString.class);
      TypeMirror typedByteArrayType = getTypeMirror(processingEnv, retrofit.mime.TypedByteArray.class);

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        String mimeType = "";
        String value = "";
        Retrofit.Part partAnnotation = parameter.getAnnotation(Retrofit.Part.class);
        retrofit.http.Part part1Annotation = parameter.getAnnotation(retrofit.http.Part.class);
        if (partAnnotation != null) {
          value = partAnnotation.value();
          mimeType = partAnnotation.mimeType();
        } else if (part1Annotation != null) {
          value = part1Annotation.value();
        } else {
          continue;
        }

        TypeMirror type = parameter.asType();
        boolean isFile = typeUtils.isSubtype(type, fileType);
        boolean isTypedFile = typeUtils.isSubtype(type, typedFileType);
        boolean isTypedString = typeUtils.isSubtype(type, typedStringType);
        boolean isTypedByteArray = typeUtils.isSubtype(type, typedByteArrayType);

        String key = value.equals("") ? parameter.getSimpleName().toString() : value;
        map.put(key, new Part(parameter.getSimpleName().toString(), mimeType, isFile, isTypedFile, isTypedString, isTypedByteArray));
      }
      return map;
    }

    // /{postId}
    // /{userIdA}/friends/{userIdB}
    // "/" + userIdA + "/friends/" + userIdB
    // "/" + userIdA + "/friends/" + userIdB + ""
    public String buildPath(ExecutableElement method) {
      String fullPath = buildRawPath(method);
      if (fullPath == null) return null;

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        Retrofit.Path path = parameter.getAnnotation(Retrofit.Path.class);
        retrofit.http.Path path1 = parameter.getAnnotation(retrofit.http.Path.class);
        if ((path != null) && (!path.value().equals(""))) {
          fullPath = fullPath.replace("{" + path.value() + "}", "\" + " +
              parameter.getSimpleName().toString() + " + \"");
        } else if ((path1 != null) && (!path1.value().equals(""))) {
          fullPath = fullPath.replace("{" + path1.value() + "}", "\" + " +
              parameter.getSimpleName().toString() + " + \"");
        } else {
          fullPath = fullPath.replace("{" + parameter.getSimpleName().toString() + "}", "\" + " +
              parameter.getSimpleName().toString() + " + \"");
        }
      }

      return fullPath.replaceAll("\\?.+", "");
    }

    public String buildRawPath(ExecutableElement method) {
      // TODO duplicated routine
      String rawPath = null;
      Retrofit.GET get = method.getAnnotation(Retrofit.GET.class);
      Retrofit.PUT put = method.getAnnotation(Retrofit.PUT.class);
      Retrofit.POST post = method.getAnnotation(Retrofit.POST.class);
      Retrofit.DELETE delete = method.getAnnotation(Retrofit.DELETE.class);
      Retrofit.HEAD head = method.getAnnotation(Retrofit.HEAD.class);
      if (get != null) rawPath = get.value();
      if (put != null) rawPath = put.value();
      if (post != null) rawPath = post.value();
      if (delete != null) rawPath = delete.value();
      if (head != null) rawPath = head.value();
      retrofit.http.GET get1 = method.getAnnotation(retrofit.http.GET.class);
      retrofit.http.PUT put1 = method.getAnnotation(retrofit.http.PUT.class);
      retrofit.http.POST post1 = method.getAnnotation(retrofit.http.POST.class);
      retrofit.http.DELETE delete1 = method.getAnnotation(retrofit.http.DELETE.class);
      retrofit.http.HEAD head1 = method.getAnnotation(retrofit.http.HEAD.class);
      if (get1 != null) rawPath = get1.value();
      if (put1 != null) rawPath = put1.value();
      if (post1 != null) rawPath = post1.value();
      if (delete1 != null) rawPath = delete1.value();
      if (head1 != null) rawPath = head1.value();
      return rawPath;
    }

    public Map<String, String> buildQueries(ExecutableElement method) {
      Map<String, String> map = new HashMap<String, String>();

      String fullPath = buildRawPath(method);
      if (fullPath == null) return map;

      if (fullPath.indexOf("?") != -1) {
        fullPath = fullPath.replaceAll("^.*\\?", "");
        String[] queries = fullPath.split("&");
        for (String query : queries) {
          String[] keyValue = query.split("=");
          map.put(keyValue[0], "\"" + keyValue[1] + "\"");
        }
      }

      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        String value = "";
        Retrofit.Query query = parameter.getAnnotation(Retrofit.Query.class);
        retrofit.http.Query query1 = parameter.getAnnotation(retrofit.http.Query.class);
        if (query != null) {
          value = query.value();
        } else if (query1 != null) {
          value = query1.value();
        } else {
          continue;
        }

        String key = value.equals("") ? parameter.getSimpleName().toString() : value;
        map.put(key, parameter.getSimpleName().toString());
      }

      return map;
    }

    public List<String> buildQueryMaps(ExecutableElement method) {
      List<String> queryMaps = new ArrayList<String>();
      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        Retrofit.QueryMap queryMap = parameter.getAnnotation(Retrofit.QueryMap.class);
        retrofit.http.QueryMap queryMap1 = parameter.getAnnotation(retrofit.http.QueryMap.class);
        if (queryMap != null) {
        } else if (queryMap1 != null) {
        } else {
          continue;
        }

        queryMaps.add(parameter.getSimpleName().toString());
      }
      return queryMaps;
    }

    public List<String> buildQueryBundles(ExecutableElement method) {
      List<String> queryBundles = new ArrayList<String>();
      List<? extends VariableElement> parameters = method.getParameters();
      for (VariableElement parameter : parameters) {
        Retrofit.QueryBundle queryBundle = parameter.getAnnotation(Retrofit.QueryBundle.class);
        if (queryBundle == null) {
          continue;
        }

        queryBundles.add(parameter.getSimpleName().toString());
      }
      return queryBundles;
    }

    private ImmutableList<String> buildAnnotations(TypeSimplifier typeSimplifier) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();

      for (AnnotationMirror annotationMirror : method.getAnnotationMirrors()) {
        TypeElement annotationElement =
            (TypeElement) annotationMirror.getAnnotationType().asElement();
        if (annotationElement.getQualifiedName().toString().equals(Override.class.getName())) {
          // Don't copy @Override if present, since we will be adding our own @Override in the
          // implementation.
          continue;
        }
        // TODO(user): we should import this type if it is not already imported
        AnnotationOutput annotationOutput = new AnnotationOutput(typeSimplifier);
        builder.add(annotationOutput.sourceFormForAnnotation(annotationMirror));
      }

      return builder.build();
    }

    /**
     * Returns the name of the property as it should be used when declaring identifiers (fields and
     * parameters). If the original getter method was {@code foo()} then this will be {@code foo}.
     * If it was {@code getFoo()} then it will be {@code foo}. If it was {@code getPackage()} then
     * it will be something like {@code package0}, since {@code package} is a reserved word.
     */
    @Override
    public String toString() {
      return identifier;
    }

    /**
     * Returns the name of the property as it should be used in strings visible to users. This is
     * usually the same as {@code toString()}, except that if we had to use an identifier like
     * "package0" because "package" is a reserved word, the name here will be the original
     * "package".
     */
    public String getName() {
      return name;
    }

    /**
     * Returns the name of the getter method for this property as defined by the {@code @Retrofit}
     * class. For property {@code foo}, this will be {@code foo} or {@code getFoo} or {@code isFoo}.
     */
    public String getGetter() {
      return method.getSimpleName().toString();
    }

    TypeElement getOwner() {
      return (TypeElement) method.getEnclosingElement();
    }

    TypeMirror getReturnType() {
      return method.getReturnType();
    }

    public String getType() {
      return type;
    }

    public String getTypeArgs() {
      return typeArgs;
    }

    public String getTypeArgs2() {
      return typeArgs2;
    }

    public TypeKind getKind() {
      return method.getReturnType().getKind();
    }

    public String getCastType() {
      return primitive() ? box(method.getReturnType().getKind()) : getType();
    }

    private String box(TypeKind kind) {
      switch (kind) {
        case BOOLEAN:
          return "Boolean";
        case BYTE:
          return "Byte";
        case SHORT:
          return "Short";
        case INT:
          return "Integer";
        case LONG:
          return "Long";
        case CHAR:
          return "Character";
        case FLOAT:
          return "Float";
        case DOUBLE:
          return "Double";
        default:
          throw new RuntimeException("Unknown primitive of kind " + kind);
        }
    }

    public boolean primitive() {
      return method.getReturnType().getKind().isPrimitive();
    }

    public boolean isCallback() {
      return (callbackType != null && !"".equals(callbackType));
    }

    public String getCallbackType() {
      return callbackType;
    }

    public String getCallbackArg() {
      return callbackArg;
    }

    public String getCallbackName() {
      return callbackName;
    }

    public boolean isObservable() {
      return isObservable;
    }

    public boolean isVoid() {
      return isVoid;
    }

    public boolean isBlocking() {
      return isBlocking;
    }

    public boolean isResponseType() {
      return isResponseType;
    }

    public String getBody() {
      return body;
    }

    public String getConverter() {
      return converter;
    }

    public String getGsonConverter() {
      return gsonConverter;
    }

    public String getErrorHandler() {
      return errorHandler;
    }

    public String getRequestInterceptor() {
      return requestInterceptor;
    }

    public String getLogLevel() {
      return logLevel;
    }

    public List<String> getPermissions() {
      return permissions;
    }

    public boolean isGet() {
      return isGet;
    }

    public boolean isPut() {
      return isPut;
    }

    public boolean isPost() {
      return isPost;
    }

    public boolean isDelete() {
      return isDelete;
    }

    public boolean isHead() {
      return isHead;
    }

    public boolean isAuthenticated() {
      return isAuthenticated;
    }

    public boolean isSingletonRequestInterceptor() {
      return isSingletonRequestInterceptor;
    }

    public List<String> getAnnotations() {
      return annotations;
    }

    public String getArgs() {
      return args;
    }

    public String getPath() {
      return path;
    }

    public Map<String, String> getQueries() {
      return queries;
    }

    public List<String> getQueryMaps() {
      return queryMaps;
    }

    public List<String> getQueryBundles() {
      return queryBundles;
    }

    public Map<String, String> getHeaders() {
      return headers;
    }

    public Map<String, String> getFields() {
      return fields;
    }

    public Map<String, Part> getParts() {
      return parts;
    }

    public boolean isNullable() {
      for (AnnotationMirror annotationMirror : method.getAnnotationMirrors()) {
        String name = annotationMirror.getAnnotationType().asElement().getSimpleName().toString();
        if (name.equals("Nullable")) {
          return true;
        }
      }
      return false;
    }

    public String getAccess() {
      Set<Modifier> mods = method.getModifiers();
      if (mods.contains(Modifier.PUBLIC)) {
        return "public ";
      } else if (mods.contains(Modifier.PROTECTED)) {
        return "protected ";
      } else {
        return "";
      }
    }
  }

  private static boolean isJavaLangObject(TypeElement type) {
    return type.getSuperclass().getKind() == TypeKind.NONE && type.getKind() == ElementKind.CLASS;
  }

  private enum ObjectMethodToOverride {
    NONE, TO_STRING, EQUALS, HASH_CODE, DESCRIBE_CONTENTS, WRITE_TO_PARCEL
  }

  private static ObjectMethodToOverride objectMethodToOverride(ExecutableElement method) {
    String name = method.getSimpleName().toString();
    switch (method.getParameters().size()) {
      case 0:
        if (name.equals("toString")) {
          return ObjectMethodToOverride.TO_STRING;
        } else if (name.equals("hashCode")) {
          return ObjectMethodToOverride.HASH_CODE;
        } else if (name.equals("describeContents")) {
          return ObjectMethodToOverride.DESCRIBE_CONTENTS;
        }
        break;
      case 1:
        if (name.equals("equals")
            && method.getParameters().get(0).asType().toString().equals("java.lang.Object")) {
          return ObjectMethodToOverride.EQUALS;
        }
        break;
      case 2:
        if (name.equals("writeToParcel")
            && method.getParameters().get(0).asType().toString().equals("android.os.Parcel")
            && method.getParameters().get(1).asType().toString().equals("int")) {
          return ObjectMethodToOverride.WRITE_TO_PARCEL;
        }
        break;
    }
    return ObjectMethodToOverride.NONE;
  }

  private void findLocalAndInheritedMethods(TypeElement type, List<ExecutableElement> methods) {
    Types typeUtils = processingEnv.getTypeUtils();
    Elements elementUtils = processingEnv.getElementUtils();
    for (TypeMirror superInterface : type.getInterfaces()) {
      findLocalAndInheritedMethods((TypeElement) typeUtils.asElement(superInterface), methods);
    }
    if (type.getSuperclass().getKind() != TypeKind.NONE) {
      // Visit the superclass after superinterfaces so we will always see the implementation of a
      // method after any interfaces that declared it.
      findLocalAndInheritedMethods(
          (TypeElement) typeUtils.asElement(type.getSuperclass()), methods);
    }
    // Add each method of this class, and in so doing remove any inherited method it overrides.
    // This algorithm is quadratic in the number of methods but it's hard to see how to improve
    // that while still using Elements.overrides.
    List<ExecutableElement> theseMethods = ElementFilter.methodsIn(type.getEnclosedElements());
    for (ExecutableElement method : theseMethods) {
      if (!method.getModifiers().contains(Modifier.PRIVATE)) {
        boolean alreadySeen = false;
        for (Iterator<ExecutableElement> methodIter = methods.iterator(); methodIter.hasNext(); ) {
          ExecutableElement otherMethod = methodIter.next();
          if (elementUtils.overrides(method, otherMethod, type)) {
            methodIter.remove();
          } else if (method.getSimpleName().equals(otherMethod.getSimpleName())
              && method.getParameters().equals(otherMethod.getParameters())) {
            // If we inherit this method on more than one path, we don't want to add it twice.
            alreadySeen = true;
          }
        }
        if (!alreadySeen) {
          /*
          Retrofit.GET action = method.getAnnotation(Retrofit.GET.class);
          System.out.printf(
              "%s Action value = %s\n",
              method.getSimpleName(),
              action == null ? null : action.value() );
          */
          methods.add(method);
        }
      }
    }
  }

  private void processType(TypeElement type) {
    Retrofit autoValue = type.getAnnotation(Retrofit.class);
    if (autoValue == null) {
      // This shouldn't happen unless the compilation environment is buggy,
      // but it has happened in the past and can crash the compiler.
      errorReporter.abortWithError("annotation processor for @Retrofit was invoked with a type"
          + " that does not have that annotation; this is probably a compiler bug", type);
    }
    if (type.getKind() != ElementKind.CLASS) {
      errorReporter.abortWithError(
          "@" + Retrofit.class.getName() + " only applies to classes", type);
    }
    if (false && ancestorIsRetrofit(type)) {
      errorReporter.abortWithError("One @Retrofit class may not extend another", type);
    }
    if (implementsAnnotation(type)) {
      errorReporter.abortWithError("@Retrofit may not be used to implement an annotation"
          + " interface; try using @AutoAnnotation instead", type);
    }
    RetrofitTemplateVars vars = new RetrofitTemplateVars();
    vars.pkg = TypeSimplifier.packageNameOf(type);
    vars.origClass = TypeSimplifier.classNameOf(type);
    vars.simpleClassName = TypeSimplifier.simpleNameOf(vars.origClass);
    vars.subclass = TypeSimplifier.simpleNameOf(generatedSubclassName(type));
    defineVarsForType(type, vars);
    GwtCompatibility gwtCompatibility = new GwtCompatibility(type);
    vars.gwtCompatibleAnnotation = gwtCompatibility.gwtCompatibleAnnotationString();
    String text = vars.toText();
    text = Reformatter.fixup(text);
    writeSourceFile(generatedSubclassName(type), text, type);
    GwtSerialization gwtSerialization = new GwtSerialization(gwtCompatibility, processingEnv, type);
    gwtSerialization.maybeWriteGwtSerializer(vars);
  }

  private void defineVarsForType(TypeElement type, RetrofitTemplateVars vars) {
    Types typeUtils = processingEnv.getTypeUtils();
    List<ExecutableElement> methods = new ArrayList<ExecutableElement>();
    findLocalAndInheritedMethods(type, methods);
    determineObjectMethodsToGenerate(methods, vars);
    ImmutableSet<ExecutableElement> methodsToImplement = methodsToImplement(methods);
    Set<TypeMirror> types = new TypeMirrorSet();
    types.addAll(returnTypesOf(methodsToImplement));
    //    TypeMirror javaxAnnotationGenerated = getTypeMirror(Generated.class);
    //    types.add(javaxAnnotationGenerated);
    TypeMirror javaUtilArrays = getTypeMirror(Arrays.class);
    if (containsArrayType(types)) {
      // If there are array properties then we will be referencing java.util.Arrays.
      // Arrange to import it unless that would introduce ambiguity.
      types.add(javaUtilArrays);
    }
    BuilderSpec builderSpec = new BuilderSpec(type, processingEnv, errorReporter);
    Optional<BuilderSpec.Builder> builder = builderSpec.getBuilder();
    ImmutableSet<ExecutableElement> toBuilderMethods;
    ImmutableList<ExecutableElement> builderSetters;
    if (builder.isPresent()) {
      types.add(getTypeMirror(BitSet.class));
      toBuilderMethods = builder.get().toBuilderMethods(typeUtils, methodsToImplement);
      builderSetters = builder.get().getSetters();
    } else {
      toBuilderMethods = ImmutableSet.of();
      builderSetters = ImmutableList.of();
    }
    vars.toBuilderMethods =
        FluentIterable.from(toBuilderMethods).transform(SimpleNameFunction.INSTANCE).toList();

    Set<ExecutableElement> propertyMethods = Sets.difference(methodsToImplement, toBuilderMethods);
    String pkg = TypeSimplifier.packageNameOf(type);
    TypeSimplifier typeSimplifier = new TypeSimplifier(typeUtils, pkg, types, type.asType());
    vars.imports = typeSimplifier.typesToImport();
    //    vars.generated = typeSimplifier.simplify(javaxAnnotationGenerated);
    vars.arrays = typeSimplifier.simplify(javaUtilArrays);
    vars.bitSet = typeSimplifier.simplifyRaw(getTypeMirror(BitSet.class));
    ImmutableMap<ExecutableElement, String> methodToPropertyName =
        methodToPropertyNameMap(propertyMethods);
    Map<ExecutableElement, String> methodToIdentifier =
        Maps.newLinkedHashMap(methodToPropertyName);
    fixReservedIdentifiers(methodToIdentifier);
    List<Property> props = new ArrayList<Property>();
    for (ExecutableElement method : propertyMethods) {
      String propertyType = typeSimplifier.simplify(method.getReturnType());
      String propertyName = methodToPropertyName.get(method);
      String identifier = methodToIdentifier.get(method);
      List<String> args = new ArrayList<String>();
      props.add(new Property(propertyName, identifier, method, propertyType, typeSimplifier, processingEnv));
    }
    // If we are running from Eclipse, undo the work of its compiler which sorts methods.
    eclipseHack().reorderProperties(props);
    vars.props = props;
    vars.serialVersionUID = getSerialVersionUID(type);
    vars.formalTypes = typeSimplifier.formalTypeParametersString(type);
    vars.actualTypes = TypeSimplifier.actualTypeParametersString(type);
    vars.wildcardTypes = wildcardTypeParametersString(type);
    Retrofit typeAnnoation = type.getAnnotation(Retrofit.class);
    vars.baseUrl = typeAnnoation.value();

    Map<String, String> headerMap = new HashMap<String, String>();
    Retrofit.Headers headersAnnotation = type.getAnnotation(Retrofit.Headers.class);
    if (headersAnnotation != null) {
      for (String header : headersAnnotation.value()) {
        String[] tokens = header.split(":");
        headerMap.put(tokens[0].trim(), "\"" + tokens[1].trim() + "\"");
      }
      vars.headers = headerMap;
    }

    Map<String, String> retryHeaderMap = new HashMap<String, String>();
    Retrofit.RetryHeaders retryHeadersAnnotation = type.getAnnotation(Retrofit.RetryHeaders.class);
    if (retryHeadersAnnotation != null) {
      for (String header : retryHeadersAnnotation.value()) {
        String[] tokens = header.split(":");
        retryHeaderMap.put(tokens[0].trim(), "\"" + tokens[1].trim() + "\"");
      }
      vars.retryHeaders = retryHeaderMap;
    }

    Retrofit.OkHttpClient okHttpClienterAnnotation = type.getAnnotation(Retrofit.OkHttpClient.class);
    if (okHttpClienterAnnotation != null) {
      TypeMirror okHttpClienter = null;
      try {
        okHttpClienter = getTypeMirror(okHttpClienterAnnotation.value());
      } catch (MirroredTypeException mte) {
        // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
        okHttpClienter = mte.getTypeMirror();
      }
      vars.okHttpClient = typeSimplifier.simplify(okHttpClienter);
    }
    Retrofit.Converter converterAnnotation = type.getAnnotation(Retrofit.Converter.class);
    if (converterAnnotation != null) {
      TypeMirror converter = null;
      try {
        converter = getTypeMirror(converterAnnotation.value());
      } catch (MirroredTypeException mte) {
        // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
        converter = mte.getTypeMirror();
      }
      vars.converter = typeSimplifier.simplify(converter);
      TypeMirror gsonConverterType = getTypeMirror(retrofit.converter.GsonConverter.class);
      if (typeUtils.isSubtype(gsonConverterType, converter)) {
        vars.gsonConverter = vars.converter;
      }
    }
    Retrofit.ErrorHandler errorHandlerAnnotation = type.getAnnotation(Retrofit.ErrorHandler.class);
    if (errorHandlerAnnotation != null) {
      TypeMirror errorHandler = null;
      try {
        errorHandler = getTypeMirror(errorHandlerAnnotation.value());
      } catch (MirroredTypeException mte) {
        // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
        errorHandler = mte.getTypeMirror();
      }
      vars.errorHandler = typeSimplifier.simplify(errorHandler);
    }
    Retrofit.LogLevel logLevelAnnotation = type.getAnnotation(Retrofit.LogLevel.class);
    if (logLevelAnnotation != null) {
      vars.logLevel = logLevelAnnotation.value();
    }
    Retrofit.RequestInterceptor requestInterceptorAnnotation = type.getAnnotation(Retrofit.RequestInterceptor.class);
    if (requestInterceptorAnnotation != null) {
      TypeMirror requestInterceptor = null;
      try {
        requestInterceptor = getTypeMirror(requestInterceptorAnnotation.value());
      } catch (MirroredTypeException mte) {
        // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
        requestInterceptor = mte.getTypeMirror();
      }
      vars.requestInterceptor = typeSimplifier.simplify(requestInterceptor);
    }
    Retrofit.Authenticator authenticatorAnnotation = type.getAnnotation(Retrofit.Authenticator.class);
    if (authenticatorAnnotation != null) {
      TypeMirror authenticator = null;
      try {
        authenticator = getTypeMirror(authenticatorAnnotation.value());
      } catch (MirroredTypeException mte) {
        // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
        authenticator = mte.getTypeMirror();
      }
      vars.authenticator = typeSimplifier.simplify(authenticator);
    }
    Retrofit.Authenticated authenticatedAnnotation = type.getAnnotation(Retrofit.Authenticated.class);
    if (authenticatedAnnotation != null) {
      TypeMirror authenticatedType = null;
      try {
        authenticatedType = getTypeMirror(authenticatedAnnotation.value());
      } catch (MirroredTypeException mte) {
        // http://blog.retep.org/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
        authenticatedType = mte.getTypeMirror();
      }
      String authenticated = typeSimplifier.simplify(authenticatedType);
      vars.authenticated = authenticated != null && !"".equals(authenticated);
    }

    List<Property> builderProps = new ArrayList<Property>();
    for (ExecutableElement method : builderSetters) {
      List<? extends VariableElement> parameters = method.getParameters();
      String propertyType = typeSimplifier.simplify(parameters.get(0).asType());
      String propertyName = method.getSimpleName().toString();
      String identifier = method.getSimpleName().toString();
      builderProps.add(new Property(propertyName, identifier, method, propertyType, typeSimplifier, processingEnv));
    }
    vars.builderProps = builderProps;

    TypeElement parcelable = processingEnv.getElementUtils().getTypeElement("android.os.Parcelable");
    vars.parcelable = parcelable != null
      && processingEnv.getTypeUtils().isAssignable(type.asType(), parcelable.asType());
    // Check for @Retrofit.Builder and add appropriate variables if it is present.
    if (builder.isPresent()) {
      builder.get().defineVars(vars, typeSimplifier, methodToPropertyName);
    }
  }

  private ImmutableMap<ExecutableElement, String> methodToPropertyNameMap(
      Iterable<ExecutableElement> propertyMethods) {
    ImmutableMap.Builder<ExecutableElement, String> builder = ImmutableMap.builder();
    boolean allGetters = allGetters(propertyMethods);
    for (ExecutableElement method : propertyMethods) {
      String methodName = method.getSimpleName().toString();
      String name = allGetters ? nameWithoutPrefix(methodName) : methodName;
      builder.put(method, name);
    }
    ImmutableMap<ExecutableElement, String> map = builder.build();
    if (allGetters) {
      checkDuplicateGetters(map);
    }
    return map;
  }

  private static boolean allGetters(Iterable<ExecutableElement> methods) {
    for (ExecutableElement method : methods) {
      String name = method.getSimpleName().toString();
      // TODO(user): decide whether getfoo() (without a capital) is a getter. Currently it is.
      boolean get = name.startsWith("get") && !name.equals("get");
      boolean is = name.startsWith("is") && !name.equals("is")
          && method.getReturnType().getKind() == TypeKind.BOOLEAN;
      if (!get && !is) {
        return false;
      }
    }
    return true;
  }

  private String nameWithoutPrefix(String name) {
    if (name.startsWith("get")) {
      name = name.substring(3);
    } else {
      assert name.startsWith("is");
      name = name.substring(2);
    }
    return Introspector.decapitalize(name);
  }

  private void checkDuplicateGetters(Map<ExecutableElement, String> methodToIdentifier) {
    if (true) return;
    Set<String> seen = Sets.newHashSet();
    for (Map.Entry<ExecutableElement, String> entry : methodToIdentifier.entrySet()) {
      if (!seen.add(entry.getValue())) {
        errorReporter.reportError(
            "More than one @Retrofit property called " + entry.getValue(), entry.getKey());
      }
    }
  }

  // If we have a getter called getPackage() then we can't use the identifier "package" to represent
  // its value since that's a reserved word.
  private void fixReservedIdentifiers(Map<ExecutableElement, String> methodToIdentifier) {
    for (Map.Entry<ExecutableElement, String> entry : methodToIdentifier.entrySet()) {
      if (SourceVersion.isKeyword(entry.getValue())) {
        entry.setValue(disambiguate(entry.getValue(), methodToIdentifier.values()));
      }
    }
  }

  private String disambiguate(String name, Collection<String> existingNames) {
    for (int i = 0; ; i++) {
      String candidate = name + i;
      if (!existingNames.contains(candidate)) {
        return candidate;
      }
    }
  }

  private Set<TypeMirror> returnTypesOf(Iterable<ExecutableElement> methods) {
    Set<TypeMirror> returnTypes = new TypeMirrorSet();
    for (ExecutableElement method : methods) {
      returnTypes.add(method.getReturnType());
    }
    return returnTypes;
  }

  private static boolean containsArrayType(Set<TypeMirror> types) {
    for (TypeMirror type : types) {
      if (type.getKind() == TypeKind.ARRAY) {
        return true;
      }
    }
    return false;
  }

  /**
   * Given a list of all methods defined in or inherited by a class, sets the equals, hashCode, and
   * toString fields of vars according as the corresponding methods should be generated.
   */
  private static void determineObjectMethodsToGenerate(
      List<ExecutableElement> methods, RetrofitTemplateVars vars) {
    // The defaults here only come into play when an ancestor class doesn't exist.
    // Compilation will fail in that case, but we don't want it to crash the compiler with
    // an exception before it does. If all ancestors do exist then we will definitely find
    // definitions of these three methods (perhaps the ones in Object) so we will overwrite these:
    vars.equals = false;
    vars.hashCode = false;
    vars.toString = false;
    for (ExecutableElement method : methods) {
      ObjectMethodToOverride override = objectMethodToOverride(method);
      boolean canGenerate = method.getModifiers().contains(Modifier.ABSTRACT)
          || isJavaLangObject((TypeElement) method.getEnclosingElement());
      switch (override) {
        case EQUALS:
          vars.equals = canGenerate;
          break;
        case HASH_CODE:
          vars.hashCode = canGenerate;
          break;
        case TO_STRING:
          vars.toString = canGenerate;
          break;
      }
    }
  }

  private ImmutableSet<ExecutableElement> methodsToImplement(List<ExecutableElement> methods) {
    ImmutableSet.Builder<ExecutableElement> toImplement = ImmutableSet.builder();
    boolean errors = false;
    for (ExecutableElement method : methods) {
      if (method.getModifiers().contains(Modifier.ABSTRACT)
          && objectMethodToOverride(method) == ObjectMethodToOverride.NONE) {
        if (method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID) {
          if (isReferenceArrayType(method.getReturnType())) {
            errorReporter.reportError("An @Retrofit class cannot define an array-valued property"
                + " unless it is a primitive array", method);
            errors = true;
          }
          toImplement.add(method);
        } else {
          toImplement.add(method);
        }
      }
    }
    if (errors) {
      throw new AbortProcessingException();
    }
    return toImplement.build();
  }

  private static boolean isReferenceArrayType(TypeMirror type) {
    return type.getKind() == TypeKind.ARRAY
        && !((ArrayType) type).getComponentType().getKind().isPrimitive();
  }

  private void writeSourceFile(String className, String text, TypeElement originatingType) {
    try {
      JavaFileObject sourceFile =
          processingEnv.getFiler().createSourceFile(className, originatingType);
      Writer writer = sourceFile.openWriter();
      try {
        writer.write(text);
      } finally {
        writer.close();
      }
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Could not write generated class " + className + ": " + e);
    }
  }

  private boolean ancestorIsRetrofit(TypeElement type) {
    while (true) {
      TypeMirror parentMirror = type.getSuperclass();
      if (parentMirror.getKind() == TypeKind.NONE) {
        return false;
      }
      Types typeUtils = processingEnv.getTypeUtils();
      TypeElement parentElement = (TypeElement) typeUtils.asElement(parentMirror);
      if (parentElement.getAnnotation(Retrofit.class) != null) {
        return true;
      }
      type = parentElement;
    }
  }

  private boolean implementsAnnotation(TypeElement type) {
    Types typeUtils = processingEnv.getTypeUtils();
    return typeUtils.isAssignable(type.asType(), getTypeMirror(Annotation.class));
  }

  // Return a string like "1234L" if type instanceof Serializable and defines
  // serialVersionUID = 1234L, otherwise "".
  private String getSerialVersionUID(TypeElement type) {
    Types typeUtils = processingEnv.getTypeUtils();
    TypeMirror serializable = getTypeMirror(Serializable.class);
    if (typeUtils.isAssignable(type.asType(), serializable)) {
      List<VariableElement> fields = ElementFilter.fieldsIn(type.getEnclosedElements());
      for (VariableElement field : fields) {
        if (field.getSimpleName().toString().equals("serialVersionUID")) {
          Object value = field.getConstantValue();
          if (field.getModifiers().containsAll(Arrays.asList(Modifier.STATIC, Modifier.FINAL))
              && field.asType().getKind() == TypeKind.LONG
              && value != null) {
            return value + "L";
          } else {
            errorReporter.reportError(
                "serialVersionUID must be a static final long compile-time constant", field);
            break;
          }
        }
      }
    }
    return "";
  }

  private TypeMirror getTypeMirror(Class<?> c) {
    return getTypeMirror(processingEnv, c);
  }

  private TypeMirror getTypeMirror(String canonicalName) {
    return getTypeMirror(processingEnv, canonicalName);
  }

  private static TypeMirror getTypeMirror(ProcessingEnvironment processingEnv, Class<?> c) {
    return getTypeMirror(processingEnv, c.getCanonicalName());
  }

  private static TypeMirror getTypeMirror(ProcessingEnvironment processingEnv, String canonicalName) {
    return processingEnv.getElementUtils().getTypeElement(canonicalName).asType();
  }

  // The @Retrofit type, with a ? for every type.
  // If we have @Retrofit abstract class Foo<T extends Something> then this method will return
  // just <?>.
  private static String wildcardTypeParametersString(TypeElement type) {
    List<? extends TypeParameterElement> typeParameters = type.getTypeParameters();
    if (typeParameters.isEmpty()) {
      return "";
    } else {
      return "<"
          + Joiner.on(", ").join(
          FluentIterable.from(typeParameters).transform(Functions.constant("?")))
          + ">";
    }
  }

  private static String catArgsString(ExecutableElement method) {
    List<? extends VariableElement> parameters = method.getParameters();
    if (parameters.isEmpty()) {
      return "";
    } else {
      return ""
        + Joiner.on(" + ").join(
        FluentIterable.from(parameters).transform(new Function<VariableElement, String>() {
          @Override
          public String apply(VariableElement element) {
            return "" + element.getSimpleName();
          }
        }))
        + "";
    }
  }

  private static String formalArgsString(ExecutableElement method) {
    List<? extends VariableElement> parameters = method.getParameters();
    if (parameters.isEmpty()) {
      return "";
    } else {
      return ""
        + Joiner.on(", ").join(
        FluentIterable.from(parameters).transform(new Function<VariableElement, String>() {
          @Override
          public String apply(VariableElement element) {
            return "" + element.getSimpleName();
          }
        }))
        + "";
    }
  }

  private static String formalTypeArgsString(ExecutableElement method) {
    List<? extends VariableElement> parameters = method.getParameters();
    if (parameters.isEmpty()) {
      return "";
    } else {
      return ""
        + Joiner.on(", ").join(
        FluentIterable.from(parameters).transform(new Function<VariableElement, String>() {
          @Override
          public String apply(VariableElement element) {
            return "final " + element.asType() + " " + element.getSimpleName();
          }
        }))
        + "";
    }
  }

  private EclipseHack eclipseHack() {
    return new EclipseHack(processingEnv);
  }
}
