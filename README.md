# jbang-catalog

This repository contains a [jbang](https://jbang.dev/) catalog of useful scripts.

## tfup

`tfup` is a tool to fetch and install [Terraform](https://www.terraform.io/).

### Usage

To run it via jbang from this catalog repository:

```bash
jbang tfup@alaurie/jbang-catalog
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
jbang serve@alaurie/jbang-catalog
```

Or with optional port and/or directory positional arguments:

```bash
jbang serve@alaurie/jbang-catalog 8000
jbang serve@alaurie/jbang-catalog /path/to/dir 8000
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
