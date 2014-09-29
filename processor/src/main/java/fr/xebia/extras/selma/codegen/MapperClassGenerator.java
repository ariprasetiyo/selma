/*
 * Copyright 2013 Xebia and Séven Le Mesle
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package fr.xebia.extras.selma.codegen;

import com.squareup.javawriter.JavaWriter;
import fr.xebia.extras.selma.Mapper;
import fr.xebia.extras.selma.SelmaConstants;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.util.*;

import static javax.lang.model.element.Modifier.*;

/**
 *
 */
public class MapperClassGenerator {


    public static final String GENERATED_BY_SELMA = "GENERATED BY S3LM4";
    public static final Set<String> registry = new HashSet<String>();
    private final Collection<ExecutableElement> mapperMethods;
    private final String origClasse;
    private final ProcessingEnvironment processingEnv;
    private final MapperGeneratorContext context;
    private final MappingRegistry mappingRegistry;
    private final TypeElement element;
    private final SourceConfiguration configuration;
    private final IgnoreFieldsWrapper ignoreFieldsWrapper;
    private final FieldsWrapper fields;
    private EnumMappersWrapper enumMappers;
    private CustomMapperWrapper customMappers;

    public MapperClassGenerator(String classe, Collection<ExecutableElement> executableElements, ProcessingEnvironment processingEnvironment) {
        this.origClasse = classe;
        this.mapperMethods = executableElements;
        this.processingEnv = processingEnvironment;
        context = new MapperGeneratorContext(processingEnv, null);
        mappingRegistry = new MappingRegistry(context);

        element = context.elements.getTypeElement(classe);

        AnnotationWrapper mapper = AnnotationWrapper.buildFor(context, element, Mapper.class);
        ignoreFieldsWrapper = new IgnoreFieldsWrapper(context, element);
        configuration = SourceConfiguration.buildFrom(mapper, ignoreFieldsWrapper);
        fields = new FieldsWrapper(context, element);
        mappingRegistry.fields(fields);

        if (registry.contains(origClasse)) {
            return;
        }

        // Here we collect custom mappers
        customMappers = new CustomMapperWrapper(element, context);
        mappingRegistry.customMappers(customMappers);

        enumMappers = new EnumMappersWrapper(mapper, context);
        mappingRegistry.enumMappers(enumMappers);
        validateTypes();
    }



    private void validateTypes() {

        for (ExecutableElement mapperMethod : mapperMethods) {

            MethodWrapper methodWrapper = new MethodWrapper(mapperMethod, context);

            enumMappers.buildForMethod(methodWrapper);

            InOutType inOutType = methodWrapper.inOutType();
            if (inOutType.differs()) {
                MappingBuilder builder = MappingBuilder.getBuilderFor(context, inOutType);

                if ((inOutType.in().getKind() != TypeKind.DECLARED || inOutType.out().getKind() != TypeKind.DECLARED) && builder == null) {
                    context.error(mapperMethod, "In type : %s and Out type : %s differs and this kind of conversion is not supported here", inOutType.in(), inOutType.out());
                } else {
                    context.mappingMethod(methodWrapper.inOutType(), methodWrapper.getSimpleName());
                }
            }

        }
    }

    public void build() throws IOException {

        if (registry.contains(origClasse))
            return;

        registry.add(origClasse);

        boolean firstMethod = true;
        JavaWriter writer = null;
        JavaFileObject sourceFile = null;

        for (ExecutableElement mapperMethod : mapperMethods) {

            if (firstMethod) {
                String packageName = getPackage(mapperMethod).getQualifiedName().toString();
                TypeElement type = processingEnv.getElementUtils().getTypeElement(origClasse);
                String strippedTypeName = strippedTypeName(type.getQualifiedName().toString(), packageName);
                String adapterName = new StringBuilder(type.toString()).append(SelmaConstants.MAPPER_CLASS_SUFFIX).toString();

                sourceFile = processingEnv.getFiler().createSourceFile(adapterName, type);
                writer = new JavaWriter(sourceFile.openWriter());

                writer.emitSingleLineComment(GENERATED_BY_SELMA);
                writer.emitPackage(packageName);
                writer.emitEmptyLine();
                if (configuration.isFinalMappers()) {
                    writer.beginType(adapterName, "class", EnumSet.of(PUBLIC, FINAL), null, strippedTypeName);
                } else {
                    writer.beginType(adapterName, "class", EnumSet.of(PUBLIC), null, strippedTypeName);
                }
                writer.emitEmptyLine();
                firstMethod = false;

                buildConstructor(writer, adapterName);
            }
            // Write mapping method
            MapperMethodGenerator.create(writer, mapperMethod, context, mappingRegistry, configuration).build();

            writer.emitEmptyLine();
        }
        writer.endType();
        writer.close();

        // Report unused customMappers
        customMappers.reportUnused();

        // Report unused ignore fields
        ignoreFieldsWrapper.reportUnusedFields();

        // Report unused custom fields mapping
        fields.reportUnused();

        // Report unused enumMapper
        enumMappers.reportUnused();
    }

    private void buildConstructor(JavaWriter writer, String adapterName) throws IOException {


        int i = 0, iArg = 0;
        String[] args = new String[configuration.getSourceClass().size() * 2];
        List<String> assigns = new ArrayList<String>();
        StringBuilder builder = new StringBuilder();
        for (String classe : configuration.getSourceClass()) {

            builder.append(',');
            writer.emitEmptyLine();
            writer.emitJavadoc("This field is used as source akka given as parameter to the Pojos constructors");
            writer.emitField(classe.replace(".class", ""), "source" + i, EnumSet.of(PRIVATE, FINAL));
            args[iArg] = classe.replace(".class", "");
            iArg++;
            args[iArg] = "_source" + i;
            iArg++;
            assigns.add(String.format("this.source%s = _source%s", i, i));
            builder.append("this.source").append(i);
        }

        customMappers.emitCustomMappersFields(writer, false);

        if (configuration.getSourceClass().size() > 0) {
            builder.deleteCharAt(0);
        }

        // newParams hold the parameters we pass to Pojo constructor
        context.setNewParams(builder.toString());

        // First build default constructor
        writer.emitEmptyLine();
        writer.emitJavadoc("Single constructor");
        writer.beginMethod(null, adapterName, EnumSet.of(PUBLIC), args);

        // assign source in parameters to instance fields
        for (String assign : assigns) {
            writer.emitStatement(assign);
        }
        // Add customMapper instantiation
        customMappers.emitCustomMappersFields(writer, true);

        writer.endMethod();
        writer.emitEmptyLine();
    }


    public PackageElement getPackage(Element type) {
        while (type.getKind() != ElementKind.PACKAGE) {
            type = type.getEnclosingElement();
        }
        return (PackageElement) type;
    }

    public String strippedTypeName(String type, String packageName) {
        return type.substring(packageName.isEmpty() ? 0 : packageName.length() + 1);
    }

}