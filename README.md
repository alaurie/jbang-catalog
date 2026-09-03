<p align="center">
  <img src="logo.png" alt="jbang-catalog logo" width="300" />
</p>

# jbang-catalog

This repository contains a [jbang](https://jbang.dev/) catalog of useful scripts.

## serve

`serve` is a simple HTTP file server inspired by `python -m http.server` with built-in SPA routing and Basic Auth support.

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
Usage: serve [-ahvV] [--spa] [--auth=<authCredentials>] [-b=<bind>]
             [-d=<directory>] [-p=<port>] [[dirOrPort]...]
Simple HTTP file server inspired by python -m http.server
      [[dirOrPort]...]   Optional directory path and/or port number
  -a, --download         Force browser to download files instead of displaying
                           inline
      --auth=<authCredentials>
                         HTTP Basic Authentication credentials (format: user:
                           password)
  -b, --bind=<bind>      Address to bind to (default: 0.0.0.0)
  -d, --directory=<directory>
                         Directory to serve (default: current directory)
  -h, --help             Show this help message and exit.
  -p, --port=<port>      Port to listen on (default: 8080)
      --spa              Single Page Application mode: fallback 404 requests to
                           index.html
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
Usage: nudge [-chV] [-b=<buffer>] [-m=<mode>] [-p=<pixels>] [-s=<seconds>]
             [--between=<START> <STOP> <START> <STOP>]... [-r=<START> <STOP>
             <START> <STOP>]...
Simulates user activity (mouse movement, key press, scrolling) when idle to
keep your presence status active.
  -b, --buffer=<buffer>     Initial buffer delay in seconds before the first
                              check. Default: same as check interval.
      --between=<START> <STOP> <START> <STOP>
                            Only perform nudges between HH:mm and HH:mm working
                              hours window.
  -c, --circular            Move mouse in a circle pattern. Default: move
                              out-and-back.
  -h, --help                Show this help message and exit.
  -m, --mode=<mode>         Action mode: mouse, keyboard, both, scroll.
                              Default: mouse.
  -p, --pixels=<pixels>     Set how many pixels the mouse should move. Default:
                              5.
  -r, --random=<START> <STOP> <START> <STOP>
                            Execute actions using a random interval between
                              START and STOP seconds. Overrides --seconds.
  -s, --seconds=<seconds>   Define in seconds how long to wait between idle
                              checks. Default: 300.
  -V, --version             Print version information and exit.

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
Usage: typeit [-ehpvV] [-d=<delay>] [-s=<speed>] [-t=<customText>]
Simulates typing clipboard text (or specified string) into the active window
after a countdown delay.
  -d, --delay=<delay>       Countdown delay in seconds before typing starts
                              (default: 5).
  -e, --enter               Press Enter key after typing completes.
  -h, --help                Show this help message and exit.
  -p, --password            Prompt securely for password input without echoing
                              characters to terminal.
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
Usage: jwt [-cehHpV] [-s=<secret>] [<tokenOrFile>]
Inspect and decode JSON Web Tokens (JWT) without sending tokens to third
parties.
      [<tokenOrFile>]     JWT token string, file path containing token, or '-'
                            for stdin.
  -c, --check-exp         Validate token expiry state and exit 0 (valid) or 1
                            (expired).
  -e, --env, --export     Format payload claims as shell environment variables
                            (export KEY=VAL).
  -h, --help              Show this help message and exit.
  -H, --header-only       Print header JSON only.
  -p, --payload-only      Print payload JSON only.
  -s, --secret=<secret>   HMAC secret key to verify token signature (HS256,
                            HS384, HS512).
  -V, --version           Print version information and exit.
```


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
Usage: killport [-dfhiV] [-s=<signal>] <port>...
Find and terminate processes listening on specified network ports.
      <port>...           One or more port numbers to inspect or kill.
  -d, --dry-run           Show matching processes without killing them.
  -f, --force             Forcefully terminate process (SIGKILL / taskkill /F).
  -h, --help              Show this help message and exit.
  -i, --interactive       Prompt confirmation [y/N] before killing each process.
  -s, --signal=<signal>   Termination signal: TERM (graceful) or KILL
                            (forceful). Default: TERM.
  -V, --version           Print version information and exit.
```

## digest

`digest` is a CLI utility to compute and verify cryptographic checksums (MD5, SHA-1, SHA-256, SHA-512, SHA3-256, SHA3-512) for files, string text, or stdin.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang digest@alaurie myfile.tar.gz
```

Or compute an SHA-512 checksum of string text:

```bash
jbang digest@alaurie -a SHA-512 -t "my-password-string"
```

Or verify files against a checksum file:

```bash
jbang digest@alaurie -c checksums.sha256
```

Or, if you clone the repository locally:

```bash
jbang digest
```
### Options

```
Usage: digest [-chrV] [-a=<algorithm>] [-c=<checkFile>] [-t=<textInput>]
              [--benchmark] [<file>...]
Compute and verify cryptographic checksums for files or text input.
      [<file>...]           One or more file paths or directories to hash, or
                              '-' for stdin.
  -a, --algorithm=<algorithm>
                            Hash algorithm: MD5, SHA-1, SHA-256, SHA-512,
                              SHA3-256, SHA3-512. Default: SHA-256.
      --benchmark           Benchmark CPU hashing throughput across algorithms
                              (MB/s).
  -c, --check=<checkFile>   Verify checksums from specified checksum file.
  -h, --help                Show this help message and exit.
  -r, --recursive           Recursively compute checksum manifest for
                              directories.
  -t, --text=<textInput>    Compute hash for string text input instead of file.
  -V, --version             Print version information and exit.
```


### Usage

To run it via jbang from this catalog repository:

```bash
jbang fetch@alaurie https://example.com/file.iso
```

Or specify concurrent connection chunks and custom output path:

```bash
jbang fetch@alaurie -c 8 -o debian.iso https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-13.0.0-amd64-netinst.iso
```
Or explicitly verify against a specific hash (skips download if file already exists locally and matches):

```bash
jbang fetch@alaurie -o debian.iso --expected-hash 4ffa57f26c713cde084b728a64b1c79b74465e6b8e043175e3b6c364377613c8 https://example.com/file.zip
```

Or skip automatic checksum probing:

```bash
jbang fetch@alaurie --no-checksum https://example.com/file.zip
```


Or, if you clone the repository locally:

```bash
jbang fetch https://example.com/file.iso
```

### Options

```
Usage: fetch [-hV] [--no-checksum] [-c=<connections>]
             [--expected-hash=<explicitHash>] [-o=<outputPath>] <uri>
High-performance multi-threaded CLI file downloader with auto-checksum
verification
      <uri>           Target URL to download
  -c, --connections=<connections>
                      Concurrent chunk download connections
      --expected-hash=<explicitHash>
                      Explicitly verify against this hash (auto-detects
                        algorithm by length). Bypasses server probe.
  -h, --help          Show this help message and exit.
      --no-checksum   Skip automatic checksum probing and verification
  -o, --output=<outputPath>
                      Target file output path
  -V, --version       Print version information and exit.
```

---

## reach

`reach` is an advanced network diagnostic CLI utility to test TCP reachability, measure handshake latency, inspect TLS/SSL certificates (including SANs & key bits), query DNS & WHOIS records, probe Layer 7 HTTP/HTTPS status, and export structured JSON stats.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang reach@alaurie github.com:443
```

Or inspect Layer 7 HTTP status code and TTFB (Time-To-First-Byte):

```bash
jbang reach@alaurie -H example.com 443
```

Or probe multiple ports or ranges:

```bash
jbang reach@alaurie example.com 80,443,8080
jbang reach@alaurie 192.168.1.1 80-85
```

Or output machine-readable JSON for scripts & monitoring:

```bash
jbang reach@alaurie -j -H github.com 443
```
Or query comprehensive DNS records (A, AAAA, MX, NS, CNAME, TXT) and WHOIS domain info:

```bash
jbang reach@alaurie --dns --whois google.com 443
```



Or warn and exit code 2 if SSL cert expires in less than 30 days:

```bash
jbang reach@alaurie --warn-days 30 example.com 443
```


Or, if you clone the repository locally:

```bash
jbang reach github.com:443
```

### Options

```
Usage: reach [-46chHjsV] [--dns] [--whois] [-i=<interval>] [-n=<count>]
             [-t=<timeout>] [-w=<warnDaysThreshold>] <target> [<portSpec>]
Network diagnostic CLI utility to test TCP reachability and inspect TLS certs.
      <target>              Target host, host:port, or IP address.
      [<portSpec>]          Port, list (80,443), or range (80-85). Default: 80
                              or 443.
  -4, --ipv4                Force IPv4 address resolution.
  -6, --ipv6                Force IPv6 address resolution.
  -c, --continuous          Continuous probing until stopped via Ctrl+C.
      --dns                 Perform comprehensive DNS records lookup (A, AAAA,
                              MX, NS, CNAME, TXT).
  -h, --help                Show this help message and exit.
  -H, --http                Probe Layer 7 HTTP/HTTPS status code and
                              Time-To-First-Byte (TTFB).
  -i, --interval=<interval> Interval between probes in milliseconds (default:
                              1000).
  -j, --json                Output diagnostic results in JSON format.
  -n, --count=<count>       Number of probe attempts per port (default: 4).
  -s, --ssl, --tls          Force TLS/SSL certificate inspection.
  -t, --timeout=<timeout>   Connection timeout in milliseconds (default: 2000).
  -V, --version             Print version information and exit.
  -w, --warn-days=<warnDaysThreshold>
                            Exit code 2 if SSL certificate expires within
                              specified days threshold.
      --whois               Perform native WHOIS domain lookup (Registrar,
                              Creation & Expiration dates).
```

---

## fetch

`fetch` is a high-performance multi-threaded CLI file downloader with automatic checksum probing and verification.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang fetch@alaurie https://example.com/file.iso
```

Or download into a specific target directory or file path:

```bash
jbang fetch@alaurie https://mirror.aarnet.edu.au/pub/almalinux/10.2/isos/x86_64/AlmaLinux-10-latest-x86_64-boot.iso -o ~/Downloads/
```

Or download with custom concurrent connections or explicit checksum verification:

```bash
jbang fetch@alaurie https://example.com/file.tar.gz -c 8 --expected-hash sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

Or, if you clone the repository locally:

```bash
jbang fetch https://example.com/file.iso
```

### Options

```
Usage: fetch [-hV] [--no-checksum] [--no-resume] [-c=<connections>]
             [--expected-hash=<explicitHash>] [-o=<outputPath>] <uri>
High-performance multi-threaded CLI file downloader with auto-checksum
verification
      <uri>           Target URL to download
  -c, --connections=<connections>
                      Concurrent chunk download connections
      --expected-hash=<explicitHash>
                      Explicitly verify against this hash (auto-detects
                        algorithm by length). Bypasses server probe.
  -h, --help          Show this help message and exit.
      --no-checksum   Skip automatic checksum probing and verification
      --no-resume     Disable automatic download resumption and start fresh
  -o, --output=<outputPath>
                      Target file output path
  -V, --version       Print version information and exit.
```

---

## slowfetch

`slowfetch` is a thorough, beautiful system information tool written in modern Java, inspired by fastfetch and neofetch. Powered by OSHI (Operating System and Hardware Information), it displays system metrics, hardware specs, memory/swap, disk usage, IP info, and ASCII logos cross-platform across Linux, macOS, and Windows.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang slowfetch@alaurie
```

Or display all mounted physical disks:

```bash
jbang slowfetch@alaurie --disks
```
Or inspect top consuming processes:

```bash
jbang slowfetch@alaurie --top
```

Or force a specific OS logo (e.g. `java`, `debian`, `ubuntu`, `arch`, `fedora`, `macos`, `windows`, `linux`):

```bash
jbang slowfetch@alaurie --logo=java
```

Or, if you clone the repository locally:

```bash
jbang slowfetch
```

### Options

```
Usage: slowfetch [-hV] [--disks] [--no-bars] [--no-logo] [--top]
                 [--logo=<forceLogo>]
A thorough, beautiful system information tool written in modern Java.
      --disks              Show all mounted physical disks instead of just root.
  -h, --help               Show this help message and exit.
      --logo=<forceLogo>   Force a specific logo: debian, ubuntu, arch, fedora,
                             macos, windows, linux, java.
      --no-bars            Disable visual progress bar gauges for
                             memory/disk/battery.
      --no-logo            Hide OS ASCII art logo.
      --top                Show top 3 processes by CPU and Memory consumption.
  -V, --version            Print version information and exit.
```
---

## install-native

`install-native` compiles and exports catalog tools as standalone zero-overhead GraalVM native binaries directly into `~/.jbang/bin` or a custom directory, bypassing shell script wrapper overhead for instant sub-10ms CLI execution.

### Usage

To compile and export all native-supported tools into `~/.jbang/bin`:

```bash
jbang install-native@alaurie
```

Or export specific tools:

```bash
jbang install-native@alaurie fetch digest jwt
```

Or export to a custom directory:

```bash
jbang install-native@alaurie -d ~/.local/bin fetch digest
```

Or clean / remove exported native binaries from `~/.jbang/bin`:

```bash
jbang install-native@alaurie --clean
jbang install-native@alaurie --clean fetch digest
```

Or list catalog tools and native compatibility:

```bash
jbang install-native@alaurie --list
```

### Options

```
Usage: install-native [-cfhlvV] [-d=<targetDir>] [<apps>...]
Compile, export, and manage standalone zero-overhead native executables.
      [<apps>...]            Specific application aliases to export or clean (e.
                               g. fetch digest jwt). Defaults to all
                               native-supported apps.
  -c, --clean, --uninstall   Remove exported native binaries from the target
                               destination directory.
  -d, --dir=<targetDir>      Target destination directory for native binaries.
                               Default: ~/.jbang/bin
  -f, --force                Overwrite existing binaries in the target
                               directory.
  -h, --help                 Show this help message and exit.
  -l, --list                 List all available catalog applications and
                               dynamic native compatibility.
  -v, --verbose              Enable verbose output during native-image
                               compilation.
  -V, --version              Print version information and exit.
```

## Development

To automatically run `jbang-fmt` on staged `.java` files before committing, enable the repository's pre-commit hook:

```bash
git config core.hooksPath .githooks
```

---

## License

Distributed under the [MIT License](LICENSE).
