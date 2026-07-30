# AGENTS.md

Instructions, conventions, and engineering standards for AI coding agents operating in `jbang-catalog`.

---

## 1. Project Mission & Architecture

`jbang-catalog` is a curated repository of single-file Java CLI utilities distributed via [JBang](https://jbang.dev/).

### Architectural Principles
- **Single-File Executables**: Every tool lives in a single `.java` source file that can be executed directly by JBang without requiring a Gradle/Maven project wrapper or build steps.
- **Zero-Installer Philosophy**: All dependencies are declared directly inside the file header via `//DEPS` directives, allowing scripts to run instantly on any machine with JBang installed.
- **Standard Library First**: Prefer Java Standard Library APIs (`java.net.http`, `java.nio.file`, `java.awt`, `java.time`, `java.util.concurrent`, `java.util.stream`) over external libraries. Third-party dependencies should be minimal, lightweight, and focused (e.g., `picocli` for CLI parsing, `jackson` when JSON parsing is required).

---

## 2. Technical Requirements & Standards

### Java Version & Directives
- **Target JDK**: **Java 25+** (`//JAVA 25+` directive on line 2 of every script).
- **Shebang Header**:
  ```java
  ///usr/bin/env jbang "$0" "$@" ; exit $?
  //JAVA 25+
  //DEPS info.picocli:picocli:4.7.7
  ```
- **Native Image Options (Optional)**: If native compilation directives are needed, specify via `//NATIVE_OPTIONS`.

### Code Style & Formatting
- **Style Guide**: **Google Java Style Guide**.
- **Formatter**: `jbang-fmt --style=google`.
- **Indentation**: Exactly **2 spaces** (never use tab `\t` characters).
- **Line Length**: **100 characters** maximum.
- **Imports**: Group static imports first, followed by alphabetical standard Java packages and third-party packages. No wildcard star imports (`import java.util.*`).
- **Automated Formatting Hook**: Pre-commit hook at `.githooks/pre-commit` enforces `jbang-fmt --style=google` on all staged Java files. Enable via:
  ```bash
  git config core.hooksPath .githooks
  ```

### Modern Java Idioms
- **Type Inference (`var`)**: Use `var` for local variables whenever the right-hand type assignment or initialization is clear.
- **HTTP Communications**: Use `java.net.http.HttpClient`, `HttpRequest`, and `HttpResponse` (async or sync) instead of legacy `HttpURLConnection`.
- **File System Operations**: Use `java.nio.file.Path`, `Files`, `Paths`, and `BufferedReader`/`BufferedWriter` with UTF-8 encoding.
- **Language Constructs**: Utilize Java 25 features where appropriate:
  - **Records** for immutable data carriers and DTOs.
  - **Pattern Matching** for `instanceof` and `switch` statements.
  - **Text Blocks** (`""" ... """`) for multiline strings, templates, or help text.
  - **Streams & Functional Pipelines**: Use `.stream()`, `.map()`, `.filter()`, `.toList()`, and `.collect()` for collection processing.

---

## 3. CLI Design & Picocli Conventions

- **Callable Contract**: All main CLI classes must implement `java.util.concurrent.Callable<Integer>` and return `0` on successful execution or a non-zero exit code (`1` or `2`) on failure.
- **Picocli `@Command` Annotation**:
  ```java
  @Command(
      name = "script-name",
      mixinStandardHelpOptions = true,
      version = "script-name 1.0",
      description = "Concise description of what the utility does."
  )
  ```
- **Standard Options**:
  - `-h`, `--help`: Display usage options and exit.
  - `-v` / `-V`, `--version`: Display version information and exit.
  - Handle option collisions gracefully (e.g., if custom `-v` is used for version-to-install or verbose logging, declare explicit `@Option(names = {"-h", "--help"}, usageHelp = true)` fields).
- **Main Method Entry Point (Java 25+)**:
  In Java 25+, `public` access modifiers and `static` declarations are **no longer required** for main entry points (JEP 495). You can use flexible instance or package-private main methods:
  
  *Option A — Instance Main (Recommended for Picocli)*:
  ```java
  void main(String... args) {
    int exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }
  ```
  
  *Option B — Package-Private Static Main*:
  ```java
  static void main(String... args) {
    int exitCode = new CommandLine(new MyScriptClass()).execute(args);
    System.exit(exitCode);
  }
  ```

---

## 4. Cross-Platform Guidelines (Windows, macOS, Linux)

All utilities **MUST** be fully functional across Windows, macOS, and Linux by default.

### Pathing & Environment
- Never hardcode `/` or `\` path separators in strings. Always use `Path.of()`, `Paths.get()`, or `File.separator`.
- Resolve home directories portably via `System.getProperty("user.home")`.
- Account for OS binary extensions (e.g., appending `.exe` on Windows vs extensionless binaries on Linux/macOS).

### GUI, AWT & Desktop Environments
- For utilities interacting with screen/keyboard/mouse (`java.awt.Robot`, `java.awt.MouseInfo`):
  - Check `GraphicsEnvironment.isHeadless()` early to display clear error messages if executed in headless environments.
  - Add explicit delays (`robot.delay(50)`, `robot.setAutoDelay(40)`) to account for OS window manager event loop processing.
  - Provide helpful diagnostic logs for OS security policies:
    - **macOS**: Note Accessibility permissions requirements (`System Settings -> Privacy & Security -> Accessibility -> Terminal / Java`).
    - **Linux**: Detect Wayland display servers (`WAYLAND_DISPLAY` or `XDG_SESSION_TYPE=wayland`) and warn about compositor restrictions, suggesting X11 or fallback modes.

### Error Handling & Graceful Shutdown
- Catch exceptions at the boundary, printing user-friendly error messages to `System.err` without dumping raw stack traces unless debug mode is enabled.
- For long-running processes or background loops (e.g. `keep-presence`, `serve`), register a shutdown hook (`Runtime.getRuntime().addShutdownHook(...)`) to clean up sockets, threads, or resources on `SIGINT` / `Ctrl+C`.

---

## 5. Catalog Registration & Documentation Standards

### 1. Repository Directory Structure & Catalog Manifest (`jbang-catalog.json`)
Applications live in dedicated subdirectories under `apps/`: `apps/<app-name>/<app-name>.java`.
Every new utility must be registered under `"aliases"` in `jbang-catalog.json`:
```json
{
  "aliases": {
    "my-script": {
      "script-ref": "apps/my-script/my-script.java",
      "description": "Short description of the utility."
    }
  }
}
```

### 2. Documentation (`README.md`)
- **Header**: Maintain the centered logo image at the top of `README.md`.
- **Execution Syntax**: Use `@alaurie` shorthand notation in all usage examples:
  ```bash
  jbang <alias>@alaurie
  ```
- **Section Structure**: Each tool must have a dedicated section formatted as:
  ```markdown
  ## <alias>

  `<alias>` is a description of the utility.

  ### Usage

  To run it via jbang from this catalog repository:

  ```bash
  jbang <alias>@alaurie [args]
  ```

  ### Options

  ```
  <Exact verbatim output of `jbang <script>.java --help`>
  ```
  ```
- **Visual Dividers**: Use horizontal rule bar separators (`---`) between every top-level program section in `README.md`.

---

## 6. Development & Verification Workflow

Before committing any change:

1. **Verify Execution & Help Output**:
   ```bash
   jbang <script>.java --help
   ```
2. **Format Source Code**:
   ```bash
   jbang-fmt --style=google *.java
   ```
3. **Verify Git Pre-Commit Hook**:
   ```bash
   git config core.hooksPath .githooks
   ```
4. **Check Git Status**: Ensure working tree is clean and all changes are formatted properly.
