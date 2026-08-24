<p align="center">
  <img src="logo.png" alt="jbang-catalog logo" width="300" />
</p>

# jbang-catalog

This repository contains a [jbang](https://jbang.dev/) catalog of useful scripts.

## tfup

`tfup` is a tool to fetch and install [Terraform](https://www.terraform.io/).

### Usage

To run it via jbang from this catalog repository:

```bash
jbang tfup@alaurie
```

Or, if you clone the repository locally:

```bash
jbang tfup
```

### Options

```
Usage: tfup [-fhV] [-p=<customPath>] [-v=<versionToInstall>]
Fetches and installs Terraform.
  -f, --force               Force update even if versions match.
  -h, --help                Show this help message and exit.
  -p, --path=<customPath>   Custom installation directory path.
  -v, --version=<versionToInstall>
                            Specific version to install (e.g., 1.9.0). If
                              omitted, latest is fetched.
  -V                        Print version information and exit.
```

---

## serve

`serve` is a simple HTTP file server inspired by `python -m http.server`.

### Usage
To run it via jbang from this catalog repository:

```bash
jbang serve@alaurie
```

Or with optional port and/or directory positional arguments:

```bash
jbang serve@alaurie 8000
jbang serve@alaurie /path/to/dir 8000
```

Or, if you clone the repository locally:

```bash
jbang serve
```

### Options

```
Usage: serve [-ahvV] [-b=<bind>] [-d=<directory>] [-p=<port>] [[dirOrPort]...]
Simple HTTP file server inspired by python -m http.server
      [[dirOrPort]...]   Optional directory path and/or port number
  -a, --download         Force browser to download files instead of displaying
                           inline
  -b, --bind=<bind>      Address to bind to (default: 0.0.0.0)
  -d, --directory=<directory>
                         Directory to serve (default: current directory)
  -h, --help             Show this help message and exit.
  -p, --port=<port>      Port to listen on (default: 8080)
  -v, --verbose          Enable verbose request logging
  -V, --version          Print version information and exit.
```

---

## nudge

`nudge` is a tool inspired by `carrot69/keep-presence` that simulates user activity (mouse movement, key press, or scrolling) when idle to keep your Microsoft Teams presence status active.

### Usage
To run it via jbang from this catalog repository:

```bash
jbang nudge@alaurie
```

Or with custom parameters (e.g. keyboard mode every 180 seconds):

```bash
jbang nudge@alaurie -m keyboard -s 180
```

Or, if you clone the repository locally:

```bash
jbang nudge
```

### Options

```
Usage: nudge [-chV] [-b=<buffer>] [-m=<mode>] [-p=<pixels>]
                     [-s=<seconds>] [-r=<START> <STOP> <START> <STOP>]...
Simulates user activity (mouse movement, key press, scrolling) when idle to
keep your presence status active.
  -b, --buffer=<buffer>     Initial buffer delay in seconds before the first
                              check. Default: same as check interval.
  -c, --circular            Move mouse in a circle pattern. Default move
                              out-and-back.
  -h, --help                Show this help message and exit.
  -m, --mode=<mode>         Action mode: mouse, keyboard, both, scroll.
                              Default: mouse.
  -p, --pixels=<pixels>     Set how many pixels the mouse should move. Default
                              5.
  -r, --random=<START> <STOP> <START> <STOP>
                            Execute actions using a random interval between
                              START and STOP seconds. Overrides --seconds.
  -s, --seconds=<seconds>   Define in seconds how long to wait between idle
                              checks. Default 300.
  -V, --version             Print version information and exit.
```

---

## typeit

`typeit` reads your system clipboard (or a custom string) and simulates keystroke-by-keystroke typing into your active window after a countdown delay. Useful for remote consoles, VDIs, or VMs where copy-paste is blocked but keystrokes work.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang typeit@alaurie
```

Or with custom countdown delay and typing speed:

```bash
jbang typeit@alaurie -d 3 -s 20
```

Or with custom text instead of clipboard:

```bash
jbang typeit@alaurie -t "my-secret-password"
```

### Options

```
Usage: typeit [-hvV] [-d=<delay>] [-s=<speed>] [-t=<customText>]
Simulates typing clipboard text (or specified string) into the active window
after a countdown delay.
  -d, --delay=<delay>       Countdown delay in seconds before typing starts
                              (default: 5).
  -h, --help                Show this help message and exit.
  -s, --speed=<speed>       Typing speed delay in milliseconds between
                              keystrokes (default: 10).
  -t, --text=<customText>   Custom text to type instead of reading from the
                              system clipboard.
  -v, --verbose             Print characters as they are typed.
  -V, --version             Print version information and exit.
```

---

## jwt

`jwt` is a CLI utility to inspect and decode JSON Web Tokens (JWT) safely off-line without sending sensitive tokens to external web services.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang jwt@alaurie [tokenOrFile]
```

Or check token validity:

```bash
jbang jwt@alaurie --check-exp <token>
```

Or read token from stdin:

```bash
cat token.txt | jbang jwt@alaurie -
```

Or, if you clone the repository locally:

```bash
jbang jwt
```

### Options

```
Usage: jwt [-chHpV] [<tokenOrFile>]
Inspect and decode JSON Web Tokens (JWT) without sending tokens to third
parties.
      [<tokenOrFile>]   JWT token string, file path containing token, or '-'
                          for stdin.
  -c, --check-exp       Validate token expiry state and exit 0 (valid) or 1
                          (expired).
  -h, --help            Show this help message and exit.
  -H, --header-only     Print header JSON only.
  -p, --payload-only    Print payload JSON only.
  -V, --version         Print version information and exit.
```

---

## killport

`killport` is a cross-platform CLI utility to find and terminate processes listening on specified network ports.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang killport@alaurie 8080
```

Or inspect matching processes without terminating them (dry-run):

```bash
jbang killport@alaurie -d 8080 3000
```

Or forcefully kill processes listening on multiple ports:

```bash
jbang killport@alaurie -f 8080 9000
```

Or, if you clone the repository locally:

```bash
jbang killport
```

### Options

```
Usage: killport [-dfhV] <port>...
Find and terminate processes listening on specified network ports.
      <port>...   One or more port numbers to inspect or kill.
  -d, --dry-run   Show matching processes without killing them.
  -f, --force     Forcefully terminate process (SIGKILL / taskkill /F).
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
```

---

## hash

`hash` is a CLI utility to compute and verify cryptographic checksums (MD5, SHA-1, SHA-256, SHA-512, SHA3-256, SHA3-512) for files, string text, or stdin.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang hash@alaurie myfile.tar.gz
```

Or compute an SHA-512 checksum of string text:

```bash
jbang hash@alaurie -a SHA-512 -t "my-password-string"
```

Or verify files against a checksum file:

```bash
jbang hash@alaurie -c checksums.sha256
```

Or, if you clone the repository locally:

```bash
jbang hash
```

### Options

```
Usage: hash [-hV] [-a=<algorithm>] [-c=<checkFile>] [-t=<textInput>] [<file>...]
Compute and verify cryptographic checksums for files or text input.
      [<file>...]           One or more file paths to hash, or '-' for stdin.
  -a, --algorithm=<algorithm>
                            Hash algorithm: MD5, SHA-1, SHA-256, SHA-512,
                              SHA3-256, SHA3-512. Default: SHA-256.
  -c, --check=<checkFile>   Verify checksums from specified checksum file.
  -h, --help                Show this help message and exit.
  -t, --text=<textInput>    Compute hash for string text input instead of file.
  -V, --version             Print version information and exit.
```

---

## fetch

`fetch` is a high-performance multithreaded CLI file downloader with live progress and automatic checksum detection and verification.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang fetch@alaurie [https://example.com/file.iso](https://example.com/file.iso)
```

Or specify concurrent connection chunks and custom output path:

```bash
jbang fetch@alaurie -c 8 -o debian.iso [https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-13.0.0-amd64-netinst.iso](https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-13.0.0-amd64-netinst.iso)
```

Or skip automatic checksum probing:

```bash
jbang fetch@alaurie --no-checksum [https://example.com/file.zip](https://example.com/file.zip)
```

Or, if you clone the repository locally:

```bash
jbang fetch [https://example.com/file.iso](https://example.com/file.iso)
```

### Options

```
Usage: fetch [-hV] [--no-checksum] [-c=<connections>] [-o=<outputPath>] <uri>
High-performance multi-threaded CLI file downloader with auto-checksum verification.
      <uri>                  Target URL to download.
  -c, --connections=<connections>
                             Concurrent chunk download connections (default: 4).
  -h, --help                 Show this help message and exit.
      --no-checksum          Skip automatic checksum probing and verification.
  -o, --output=<outputPath> Custom target file output path.
  -V, --version              Print version information and exit.
```

---

## Development

To automatically run `jbang-fmt` on staged `.java` files before committing, enable the repository's pre-commit hook:

```bash
git config core.hooksPath .githooks
```

---

## License

Distributed under the [MIT License](LICENSE).