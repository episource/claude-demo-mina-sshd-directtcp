# Project Context

## Stack
- Java 21 (LTS). Do not use preview features or APIs introduced after Java 21.
- Mina SSHD library (sshd-core)
- Maven 3
- Junit 5

## Claude Conventions
- Read docs/requirements.md first
- Start with making a plan first and then ask for confirmation before continuing.
- The plan should include a summary of changes that would be needed to update docs/requirements.md
- Make sure docs/requirements.md is aligned with the project after every modification

## Code Conventions
- No Lombok - write explicit constructors and accessors
- Package executable application into fat jar
- Prefer maven-assembly-plugin over maven-shade-plugin
- For maven-assembly-plugin: Use `appendAssemblyId=true`

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
- Do not create git commits
- Do not perform any git commands writing to the git database. Only read!
- Do not write, delete or edit any files without a user confirmed plan!
