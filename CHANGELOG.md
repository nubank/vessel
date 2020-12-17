# Change Log

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Accept tarballs as base images: [#23](https://github.com/nubank/vessel/pull/23). This feature will be less obscure and easier to be used in the version 1 of Vessel.

## [0.2.137] - 2020-12-07

### Fixed
- Avoid blowing up the build process when a non-mapped class file is found [#22](https://github.com/nubank/vessel/pull/22). Now, the non-mapped file is assigned to the first known source directory in order to force the file to be copied to the corresponding image layer.

## [0.2.135] - 2020-08-10

### Fixed
- Preserve timestamps when copying files:
  [#21](https://github.com/nubank/vessel/pull/21). The corresponding issue was
  slowing down the startup of containerized applications and causing runtime
  problems due to conflicts between AOT and JIT compiled Clojure namespaces.

## [0.2.134] - 2020-07-03

### Added
- [#15](https://github.com/nubank/vessel/pull/15): added the user option
  to set the default user image

## [0.2.128] - 2020-05-28

### Added
- [#10](https://github.com/nubank/vessel/pull/10): added out of the box
  integration with Amazon Elastic Container Registry. Vessel looks up
  credentials to access AWS API the same way the `awscli` or `Java SDK`
  do. Thus, Vessel is capable of obtaining credentials to access `ECR`
  repositories through instance profiles without additional configurations, what
  might be useful to eliminate extra steps on CI pipelines.

## [0.2.126] - 2020-05-21

### Added
- Integration tests.
- Workflow file to run tests automatically on Github Actions.
- Normalize keys of the manifest.json file in order to avoid incongruities. Thus, Vessel can be employed  as a generic alternative to push tarballs to remote registries.

## [0.2.107] - 2020-03-09

### Added
- Added the `--preserve-file-permissions` flag to the `containerize` command. By
  default, files are copied to the container with the permissions 644. When this
  flag is enabled, Vessel copies files with their original permissions. This
  feature is useful, for instance, to keep executable scripts (e.g. wrappers for
  calling the java command) with their original permissions within the
  container.

## [0.2.99] - 2020-03-02

### Added
* Push command to upload a tarball to a registry.

### Changed
* Internal: rename vessel.jib namespace to vessel.jib.containerizer and move
functions that deal with common aspects of Jib API to the new
  vessel.jib.helpers namespace.
* Upgrade Jib to version 0.13.0.

## [0.1.90] - 2020-02-20

### Added
* Set the directory for caching base image and application layers to
  ~/.vessel-cache. Eventually, Vessel can take this directory as a parameter to
  allow a more fine-grained customization.

## [0.1.84] - 2020-02-18

### Added
* Containerize, image and manifest commands (Vessel is in an alpha stage; the
  API is subject to changes).
