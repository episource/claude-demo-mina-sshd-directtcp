# Project Context

## Stack
- Java 21 (LTS). Do not use preview features or APIs introduced after Java 21.
- Mina SSHD library (sshd-core)
- Maven 3
- Junit 5

## Conventions
- No Lombok - write explicit constructors and accessors
- Package executable application into fat jar

### Pom.xml
- Format each `<dependency>...</dependency>` on a single line, do not split into multiple lines
- For `<plugin>...</plugin>` sections: place `<groupId>`, `<artifactId>` and `<version>` nodes on a single line

## Build & Run
- `mvn clean` to prepare clean build
- `mvn package` to compile, test and package jars
- `mvn test` to run tests only

## Do Not
- Do not suggest Kotlin alternatives
- Do not suggest other programming languages
- Do not generate code that targets Java 22+
