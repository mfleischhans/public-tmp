# Copilot Instructions

## Project Setup

This is a Maven-based Spring Boot project.

Before generating code, inspect `pom.xml` to determine the effective project setup.

- Read the Java version from the `maven.compiler.release` property.
- Read the Spring Boot version from the `spring-boot-starter-parent` version.

Do not assume Java or Spring Boot versions from general defaults.  
Generate code compatible with the versions defined in the project.

## Logging

Use **SLF4J** for logging.

Do not introduce alternative logging frameworks.  
Assume that SLF4J is already configured by the project.

## Comments

Include explanatory comments when generating code.

AI-generated comments must start with a **capital letter**.  
Human-written comments in this project start with a **lowercase letter**.

Prefer comments that explain intent, assumptions, or non-trivial logic.  
Avoid comments that merely restate obvious code.

## Data Classes

Prefer Java **records** for simple immutable data carriers.

If records are not sufficient (e.g. mutable objects, builders, framework requirements, or more complex behavior), use **Lombok** to reduce boilerplate.

Do not manually generate trivial boilerplate such as getters, setters, constructors, or builders when Lombok can be used.

## Lombok Usage

Lombok is widely used in this project.

### Immutable Classes

For immutable classes prefer:

- `@Getter`
- `@AllArgsConstructor`

Avoid using `@Setter` unless mutation is explicitly required.

### Builder Pattern

When dealing with inheritance or complex immutable objects, prefer Lombok's `@Builder`.

### JSON POJOs

For simple Jackson JSON objects, `@Data` is commonly used.
