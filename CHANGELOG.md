# Change Log

All notable changes to this project will be documented in this file.

## [Unreleased]

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
