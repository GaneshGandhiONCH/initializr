/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.initializr.generator.language.kotlin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.spring.initializr.generator.io.IndentingWriter;
import io.spring.initializr.generator.io.IndentingWriterFactory;
import io.spring.initializr.generator.language.Annotatable;
import io.spring.initializr.generator.language.Annotation;
import io.spring.initializr.generator.language.ClassName;
import io.spring.initializr.generator.language.CodeBlock;
import io.spring.initializr.generator.language.CodeBlock.FormattingOptions;
import io.spring.initializr.generator.language.CompilationUnit;
import io.spring.initializr.generator.language.Parameter;
import io.spring.initializr.generator.language.SourceCode;
import io.spring.initializr.generator.language.SourceCodeWriter;
import io.spring.initializr.generator.language.SourceStructure;

/**
 * A {@link SourceCodeWriter} that writes {@link SourceCode} in Kotlin.
 *
 * @author Stephane Nicoll
 * @author Matt Berteaux
 */
public class KotlinSourceCodeWriter implements SourceCodeWriter<KotlinSourceCode> {

	private static final FormattingOptions FORMATTING_OPTIONS = new KotlinFormattingOptions();

	private final IndentingWriterFactory indentingWriterFactory;

	public KotlinSourceCodeWriter(IndentingWriterFactory indentingWriterFactory) {
		this.indentingWriterFactory = indentingWriterFactory;
	}

	@Override
	public void writeTo(SourceStructure structure, KotlinSourceCode sourceCode) throws IOException {
		for (KotlinCompilationUnit compilationUnit : sourceCode.getCompilationUnits()) {
			writeTo(structure, compilationUnit);
		}
	}

	private void writeTo(SourceStructure structure, KotlinCompilationUnit compilationUnit) throws IOException {
		Path output = structure.createSourceFile(compilationUnit.getPackageName(), compilationUnit.getName());
		Files.createDirectories(output.getParent());
		try (IndentingWriter writer = this.indentingWriterFactory.createIndentingWriter("kotlin",
				Files.newBufferedWriter(output))) {
			writer.println("package " + compilationUnit.getPackageName());
			writer.println();
			Set<String> imports = determineImports(compilationUnit);
			if (!imports.isEmpty()) {
				for (String importedType : imports) {
					writer.println("import " + importedType);
				}
				writer.println();
			}
			for (KotlinTypeDeclaration type : compilationUnit.getTypeDeclarations()) {
				writeAnnotations(writer, type);
				writeModifiers(writer, type.getModifiers());
				writer.print("class " + type.getName());
				if (type.getExtends() != null) {
					writer.print(" : " + getUnqualifiedName(type.getExtends()) + "()");
				}
				List<KotlinPropertyDeclaration> propertyDeclarations = type.getPropertyDeclarations();
				List<KotlinFunctionDeclaration> functionDeclarations = type.getFunctionDeclarations();
				boolean hasDeclarations = !propertyDeclarations.isEmpty() || !functionDeclarations.isEmpty();
				if (hasDeclarations) {
					writer.println(" {");
				}
				if (!propertyDeclarations.isEmpty()) {
					writer.indented(() -> {
						for (KotlinPropertyDeclaration propertyDeclaration : propertyDeclarations) {
							writeProperty(writer, propertyDeclaration);
						}
					});
				}
				if (!functionDeclarations.isEmpty()) {
					writer.indented(() -> {
						for (KotlinFunctionDeclaration functionDeclaration : functionDeclarations) {
							writeFunction(writer, functionDeclaration);
						}
					});
					writer.println();
				}
				else {
					writer.println("");
				}
				if (hasDeclarations) {
					writer.println("}");
				}
			}
			List<KotlinFunctionDeclaration> topLevelFunctions = compilationUnit.getTopLevelFunctions();
			if (!topLevelFunctions.isEmpty()) {
				for (KotlinFunctionDeclaration topLevelFunction : topLevelFunctions) {
					writeFunction(writer, topLevelFunction);
				}
			}

		}
	}

	private void writeProperty(IndentingWriter writer, KotlinPropertyDeclaration propertyDeclaration) {
		writer.println();
		writeModifiers(writer, propertyDeclaration.getModifiers());
		if (propertyDeclaration.isVal()) {
			writer.print("val ");
		}
		else {
			writer.print("var ");
		}
		writer.print(propertyDeclaration.getName());
		if (propertyDeclaration.getReturnType() != null) {
			writer.print(": " + getUnqualifiedName(propertyDeclaration.getReturnType()));
		}
		if (propertyDeclaration.getValueExpression() != null) {
			writer.print(" = ");
			writeExpression(writer, propertyDeclaration.getValueExpression().getExpression());
		}
		if (propertyDeclaration.getGetter() != null) {
			writer.println();
			writer.indented(() -> writeAccessor(writer, "get", propertyDeclaration.getGetter()));
		}
		if (propertyDeclaration.getSetter() != null) {
			writer.println();
			writer.indented(() -> writeAccessor(writer, "set", propertyDeclaration.getSetter()));
		}
		writer.println();
	}

	private void writeAccessor(IndentingWriter writer, String accessorName,
			KotlinPropertyDeclaration.Accessor accessor) {
		writeAnnotations(writer, accessor, () -> writer.print(" "));
		writer.print(accessorName);
		if (!accessor.isEmptyBody()) {
			writer.print("() = ");
			if (accessor.getCode() != null) {
				accessor.getCode().write(writer, FORMATTING_OPTIONS);
			}
			else if (accessor.getBody() != null) {
				writeExpression(writer, accessor.getBody().getExpression());
			}
		}
	}

	private void writeFunction(IndentingWriter writer, KotlinFunctionDeclaration functionDeclaration) {
		writer.println();
		writeAnnotations(writer, functionDeclaration);
		writeModifiers(writer, functionDeclaration.getModifiers());
		writer.print("fun ");
		writer.print(functionDeclaration.getName() + "(");
		writeParameters(writer, functionDeclaration.getParameters());
		writer.print(")");
		if (functionDeclaration.getReturnType() != null) {
			writer.print(": " + getUnqualifiedName(functionDeclaration.getReturnType()));
		}
		writer.println(" {");
		writer.indented(() -> {
			functionDeclaration.getCode().write(writer, FORMATTING_OPTIONS);
			writeStatements(writer, functionDeclaration);
		});
		writer.println("}");
	}

	private void writeParameters(IndentingWriter writer, List<Parameter> parameters) {
		if (parameters.isEmpty()) {
			return;
		}
		Iterator<Parameter> it = parameters.iterator();
		while (it.hasNext()) {
			Parameter parameter = it.next();
			writeAnnotations(writer, parameter, () -> writer.print(" "));
			writer.print(parameter.getName() + ": " + getUnqualifiedName(parameter.getType()));
			if (it.hasNext()) {
				writer.print(", ");
			}
		}
	}

	@SuppressWarnings("removal")
	private void writeStatements(IndentingWriter writer, KotlinFunctionDeclaration functionDeclaration) {
		List<KotlinStatement> statements = functionDeclaration.getStatements();
		for (KotlinStatement statement : statements) {
			if (statement instanceof KotlinExpressionStatement) {
				writeExpression(writer, ((KotlinExpressionStatement) statement).getExpression());
			}
			else if (statement instanceof KotlinReturnStatement) {
				writer.print("return ");
				writeExpression(writer, ((KotlinReturnStatement) statement).getExpression());
			}
			writer.println("");
		}
	}

	private void writeAnnotations(IndentingWriter writer, Annotatable annotatable, Runnable separator) {
		annotatable.annotations().values().forEach((annotation) -> {
			annotation.write(writer, FORMATTING_OPTIONS);
			separator.run();
		});
	}

	private void writeAnnotations(IndentingWriter writer, Annotatable annotatable) {
		writeAnnotations(writer, annotatable, writer::println);
	}

	private void writeModifiers(IndentingWriter writer, List<KotlinModifier> declaredModifiers) {
		String modifiers = declaredModifiers.stream()
			.filter((entry) -> !entry.equals(KotlinModifier.PUBLIC))
			.sorted()
			.map((entry) -> entry.toString().toLowerCase(Locale.ENGLISH))
			.collect(Collectors.joining(" "));
		if (!modifiers.isEmpty()) {
			writer.print(modifiers);
			writer.print(" ");
		}
	}

	private void writeExpression(IndentingWriter writer, KotlinExpression expression) {
		if (expression instanceof KotlinFunctionInvocation) {
			writeFunctionInvocation(writer, (KotlinFunctionInvocation) expression);
		}
		else if (expression instanceof KotlinReifiedFunctionInvocation) {
			writeReifiedFunctionInvocation(writer, (KotlinReifiedFunctionInvocation) expression);
		}
		else if (expression != null) {
			writer.print(expression.toString());
		}
	}

	private void writeFunctionInvocation(IndentingWriter writer, KotlinFunctionInvocation functionInvocation) {
		writer.print(getUnqualifiedName(functionInvocation.getTarget()) + "." + functionInvocation.getName() + "("
				+ String.join(", ", functionInvocation.getArguments()) + ")");
	}

	private void writeReifiedFunctionInvocation(IndentingWriter writer,
			KotlinReifiedFunctionInvocation functionInvocation) {
		writer.print(getUnqualifiedName(functionInvocation.getName()) + "<"
				+ getUnqualifiedName(functionInvocation.getTargetClass()) + ">("
				+ String.join(", ", functionInvocation.getArguments()) + ")");
	}

	private Set<String> determineImports(KotlinCompilationUnit compilationUnit) {
		List<String> imports = new ArrayList<>();
		for (KotlinTypeDeclaration typeDeclaration : compilationUnit.getTypeDeclarations()) {
			imports.add(typeDeclaration.getExtends());
			imports.addAll(appendImports(typeDeclaration.annotations().values(), Annotation::getImports));
			typeDeclaration.getPropertyDeclarations()
				.forEach(((propertyDeclaration) -> imports.addAll(determinePropertyImports(propertyDeclaration))));
			typeDeclaration.getFunctionDeclarations()
				.forEach((functionDeclaration) -> imports.addAll(determineFunctionImports(functionDeclaration)));
		}
		compilationUnit.getTopLevelFunctions()
			.forEach((functionDeclaration) -> imports.addAll(determineFunctionImports(functionDeclaration)));
		return imports.stream()
			.filter((candidate) -> isImportCandidate(compilationUnit, candidate))
			.sorted()
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Set<String> determinePropertyImports(KotlinPropertyDeclaration propertyDeclaration) {
		return (propertyDeclaration.getReturnType() != null) ? Set.of(propertyDeclaration.getReturnType())
				: Collections.emptySet();
	}

	private Set<String> determineFunctionImports(KotlinFunctionDeclaration functionDeclaration) {
		Set<String> imports = new LinkedHashSet<>();
		imports.add(functionDeclaration.getReturnType());
		imports.addAll(appendImports(functionDeclaration.annotations().values(), Annotation::getImports));
		for (Parameter parameter : functionDeclaration.getParameters()) {
			imports.add(parameter.getType());
			imports.addAll(appendImports(parameter.annotations().values(), Annotation::getImports));
		}
		imports.addAll(functionDeclaration.getCode().getImports());
		imports.addAll(appendImports(
				getKotlinExpressions(functionDeclaration).filter(KotlinFunctionInvocation.class::isInstance)
					.map(KotlinFunctionInvocation.class::cast),
				(invocation) -> Collections.singleton(invocation.getTarget())));
		imports.addAll(appendImports(
				getKotlinExpressions(functionDeclaration).filter(KotlinReifiedFunctionInvocation.class::isInstance)
					.map(KotlinReifiedFunctionInvocation.class::cast),
				(invocation) -> Collections.singleton(invocation.getName())));
		return imports;
	}

	@SuppressWarnings("removal")
	private Stream<KotlinExpression> getKotlinExpressions(KotlinFunctionDeclaration functionDeclaration) {
		return functionDeclaration.getStatements()
			.stream()
			.filter(KotlinExpressionStatement.class::isInstance)
			.map(KotlinExpressionStatement.class::cast)
			.map(KotlinExpressionStatement::getExpression);
	}

	private <T> List<String> appendImports(List<T> candidates, Function<T, Collection<String>> mapping) {
		return appendImports(candidates.stream(), mapping);
	}

	private <T> List<String> appendImports(Stream<T> candidates, Function<T, Collection<String>> mapping) {
		return candidates.map(mapping).flatMap(Collection::stream).collect(Collectors.toList());
	}

	private String getUnqualifiedName(String name) {
		if (!name.contains(".")) {
			return name;
		}
		return name.substring(name.lastIndexOf(".") + 1);
	}

	private boolean isImportCandidate(CompilationUnit<?> compilationUnit, String name) {
		if (name == null || !name.contains(".")) {
			return false;
		}
		String packageName = name.substring(0, name.lastIndexOf('.'));
		return !"java.lang".equals(packageName) && !compilationUnit.getPackageName().equals(packageName);
	}

	static class KotlinFormattingOptions implements FormattingOptions {

		@Override
		public String statementSeparator() {
			return "";
		}

		@Override
		public CodeBlock arrayOf(CodeBlock... values) {
			return CodeBlock.of("[$L]", CodeBlock.join(Arrays.asList(values), ", "));
		}

		@Override
		public CodeBlock classReference(ClassName className) {
			return CodeBlock.of("$T::class", className);
		}

	}

}
