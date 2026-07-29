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
  -f, --force     Force update even if versions match.
  -h, --help      Show this help message and exit.
  -p, --path=<customPath>
                  Custom installation directory path.
  -v, --version=<versionToInstall>
                  Specific version to install (e.g., 1.9.0). If omitted, latest
                    is fetched.
  -V, --version   Print version information and exit.
```

### Adding to your own catalog

If you'd like to include `tfup` in your own `jbang-catalog.json`:

```json
{
  "aliases": {
    "tfup": {
      "script-ref": "https://raw.githubusercontent.com/<your-github-username>/jbang-catalog/main/tfup.java",
      "description": "Fetches and installs Terraform."
    }
  }
}
```

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

### Adding to your own catalog

If you'd like to include `serve` in your own `jbang-catalog.json`:

```json
{
  "aliases": {
    "serve": {
      "script-ref": "https://raw.githubusercontent.com/<your-github-username>/jbang-catalog/main/serve.java",
      "description": "Serves the given directory on the specified port."
    }
  }
}
```

## keep-presence

`keep-presence` is a tool inspired by `carrot69/keep-presence` that simulates user activity (mouse movement, key press, or scrolling) when idle to keep your Microsoft Teams presence status active.

### Usage

To run it via jbang from this catalog repository:

```bash
jbang keep-presence@alaurie/jbang-catalog
```

Or with custom parameters (e.g. keyboard mode every 180 seconds):

```bash
jbang keep-presence@alaurie/jbang-catalog -m keyboard -s 180
```

Or, if you clone the repository locally:

```bash
jbang keep-presence.java
```

### Options

```
Usage: keep-presence [-chV] [-m=<mode>] [-p=<pixels>] [-s=<seconds>]
                     [-r=<START> <STOP> <START> <STOP>]...
Simulates user activity (mouse movement, key press, scrolling) when idle to
keep your presence status active.
  -c, --circular            Move mouse in a circle. Default move diagonally.
  -h, --help                Show this help message and exit.
  -m, --mode=<mode>         Action mode: mouse, keyboard, both, scroll.
                              Default: mouse.
  -p, --pixels=<pixels>     Set how many pixels the mouse should move. Default
                              1.
  -r, --random=<START> <STOP> <START> <STOP>
                            Execute actions using a random interval between
                              START and STOP seconds. Overrides --seconds.
  -s, --seconds=<seconds>   Define in seconds how long to wait between idle
                              checks. Default 300.
  -V, --version             Print version information and exit.
```

### Adding to your own catalog

If you'd like to include `keep-presence` in your own `jbang-catalog.json`:

```json
{
  "aliases": {
    "keep-presence": {
      "script-ref": "https://raw.githubusercontent.com/<your-github-username>/jbang-catalog/main/keep-presence.java",
      "description": "Simulates user activity (mouse movement, key press, scrolling) to keep your presence status active."
    }
  }
}
```
