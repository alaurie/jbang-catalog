# AGENTS.md

Instructions and conventions for AI coding agents operating in `jbang-catalog`.

---

## 1. Project Overview

`jbang-catalog` is a curated repository of single-file Java CLI utilities distributed via [JBang](https://jbang.dev/). All scripts are designed for high performance, zero-installer execution, and seamless cross-platform compatibility.

---

## 2. Technical Requirements & Conventions

### Java Version & Environment
- **Target JDK**: **Java 25+** (`//JAVA 25+` directive at the top of every JBang script).
- **Dependencies**: Declare external libraries via `//DEPS` directives (e.g., `//DEPS info.picocli:picocli:4.7.7`).
- **CLI Framework**: Use **Picocli** (`picocli.CommandLine`) for option parsing and standard help (`-h`, `--help`).

### Code Style & Formatting
- **Formatter**: **Google Java Style** (`jbang-fmt --style=google`).
- **Indentation**: 2 spaces (no raw tab `\t` characters).
- **Line Length**: 100 characters max.
- **Git Hooks**: Pre-commit hook (`.githooks/pre-commit`) automatically formats staged `.java` files using `jbang-fmt --style=google`.

### Modern Java Idioms
- **Type Inference**: Use `var` for local variables where types are obvious from context.
- **Modern APIs**:
  - Prefer `java.net.http.HttpClient` over legacy `HttpURLConnection`.
  - Use `java.nio.file.Path` and `java.nio.file.Files` for file operations.
  - Leverage **Streams**, **Records**, **Pattern Matching**, and **Text Blocks**.

### Cross-Platform Support (Windows, macOS, Linux)
- All tools **MUST** run cross-platform by default.
- Handle OS-specific paths using `Path.of()` or `Paths.get()`.
- Handle OS-specific executables (e.g., `.exe` extension on Windows).
- Handle headless/GUI desktop environments gracefully (e.g., `java.awt.GraphicsEnvironment.isHeadless()` checks, macOS Accessibility permissions notes, Wayland vs X11 warnings).

---

## 3. Catalog Structure & Workflow

- **Script Names**: Lowercase or kebab-case `.java` script files (e.g. `keep-presence.java`, `serve.java`, `tfup.java`).
- **Catalog Manifest (`jbang-catalog.json`)**: Every new script must be registered under `"aliases"` in `jbang-catalog.json`:
  ```json
  "alias-name": {
    "script-ref": "alias-name.java",
    "description": "Short description of the utility."
  }
  ```
- **Catalog Execution Syntax**: Documentation and README commands must use `@alaurie` format:
  ```bash
  jbang <alias>@alaurie
  ```
- **Documentation**: Update `README.md` with:
  - Utility description & usage examples.
  - Output of `jbang <script>.java --help`.
  - Horizontal rule separators (`---`) between program sections.

---

## 4. Verification Checklist

Before submitting changes:
1. Verify compilation and `--help` output: `jbang <script>.java --help`.
2. Format Java files: `jbang-fmt --style=google *.java`.
3. Verify git status and ensure working tree is clean.
