# Vessel

![Workflow](https://github.com/nubank/vessel/workflows/Vessel%20Tests/badge.svg?branch=master)

A containerization tool for Clojure applications. It uses [Google Jib](https://github.com/GoogleContainerTools/jib) to perform this task.

## Usage

> `build`:

Build a Clojure application

| Option | Default | Description |
| -- | -- | -- |
| `-c` `--classpath PATHS` | `--` | Directories and zip/jar files on the classpath in the same format expected by the java command |
| `-s` `--source-path PATH` | `--` | Directories containing source files. This option can be repeated many times |
| `-m` `--main-class NAMESPACE` | `--` | Namespace that contains the application's entrypoint, with a :gen-class directive and a -main function |
| `-r` `--resource-path PATH` | `--` | Directories containing resource files. This option can be repeated many times |
| `-o` `--output PATH` | `--` | Directory where the application's files will be written to |
| `-C` `--compiler-options OPTIONS` | No | Options provided to the Clojure compiler, see `clojure.core/*compiler-options*` |

> `containerize`:

Containerize a Clojure application

| Option | Default | Description |
| -- | -- | -- |
| `-a` `--app-root PATH` | `"/app"` | app root of the container image. Classes and resource files will be copied to relative paths to the app root |
| `-c` `--classpath PATHS` | `--` | Directories and zip/jar files on the classpath in the same format expected by the java command |
| `-e` `--extra-path PATH` | `--` | extra files to be copied to the container image. The value must be passed in the form source:target or source:target@churn and this option can be repeated many times |
| `-i` `--internal-deps REGEX` | `--` | java regex to determine internal dependencies. Can be repeated many times for a logical or effect |
| `-m` `--main-class NAMESPACE` | `--` | Namespace that contains the application's entrypoint, with a :gen-class directive and a -main function |
| `-M` `--manifest PATH` | `--` | manifest file describing the image to be built |
| `-o` `--output PATH` | `"image.tar"` | path to save the tarball containing the built image |
| `-p` `--project-root PATH` | `--` | root dir of the Clojure project to be built |
| `-P` `--preserve-file-permissions` | `--` | Preserve original file permissions when copying files to the container. If not enabled, the default permissions for files are 644 |
| `-s` `--source-path PATH` | `--` | Directories containing source files. This option can be repeated many times |
| `-r` `--resource-path PATH` | `--` | Directories containing resource files. This option can be repeated many times |
| `-u` `--user USER` | `root` | Define the default user for the image |
| `-C` `--compiler-options OPTIONS` | `nil` | Options provided to the Clojure compiler, see `clojure.core/*compiler-options*` |

> `image`:

Generate an image manifest, optionally by extending a base image and/or merging other manifests

| Option | Default | Description |
| -- | -- | -- |
| `-a` `--attribute KEY-VALUE` | `--` | Add the attribute in the form key:value to the manifest. This option can be repeated multiple times |
| `-b` `--base-image PATH` | `--` | Manifest file describing the base image |
| `-m` `--merge-into PATH` | -- | Manifest file to be merged into the manifest being created. This option can be repeated multiple times |
| `-o` `--output PATH` | `stdout` | Write the manifest to path instead of stdout |
| `-r` `--registry REGISTRY` | `"docker.io"` | Image registry |
| `-R` `--repository REPOSITORY` | -- | Image repository |
| `-t` `--tag TAG` | -- | Image tag. When omitted uses a SHA-256 digest of the resulting manifest |

> `manifest`:

Generate arbitrary manifests

| Option | Default | Description |
| -- | -- | -- |
| `-a` `--attribute KEY-VALUE` | `[]` | Add the attribute in the form key:value to the manifest. This option can be repeated multiple times |
| `-o` `--output PATH` | `stdout` | "Write the manifest to path instead of stdout" |
| `-O` `--object OBJECT` | `--` | Object under which attributes will be added |

> `push`:

Push a tarball to a registry

| Option | Default | Description |
| -- | -- | -- |
| `-t` `--tarball PATH` | `--` | Tar archive containing image layers and metadata files |
| `-a` `--allow-insecure-registries` | `--` | Allow pushing images to insecure registries |
| `-A` `--anonymous` | `--` | Do not authenticate on the registry; push anonymously |
