# Contributing to gomod123

Thank you for your interest in contributing to **gomod123**!

## Prerequisites

- **JDK 8** (for Forge 1.8.9 builds)
- **JDK 17+** (for Lunar Weave 1.3.4 Gradle builds; targets Java 8 automatically)
- Git

## Building Locally

### Forge 1.8.9
```bash
./gradlew build -PpublicRelease=true
```

### Lunar Client (Weave 1.3.4)
```bash
./gradlew buildWeavePublic
```

### Running Tests
```bash
# Forge tests
./gradlew test -PpublicRelease=true

# Weave tests
cd weave && ./gradlew test -PpublicRelease=true
```

## Pull Requests

1. Fork the repository and create a branch from `main`.
2. Keep changes focused and verify that both Forge and Weave builds pass with `-PpublicRelease=true`.
3. Open a pull request with a clear description of what changed and how you tested it.

## Reporting Bugs

Please open an issue using the **Bug Report** template and include:
- Your client & loader (**Forge 1.8.9** or **Lunar Client + Weave 1.3.4**)
- Your `gomod123` version
- Steps to reproduce and any relevant crash log / `latest.log` snippet
