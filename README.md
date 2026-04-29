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
