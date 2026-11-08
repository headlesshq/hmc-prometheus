#!/bin/bash

set -eo pipefail

sudo chown "$(id -u):$(id -g)" "$GRADLE_USER_HOME"

# The build includes the hmc-specifics submodule, see settings.gradle.
if [ ! -f hmc-specifics/hmc-specifics-api/build.gradle ]; then
  git submodule update --init || echo "Run 'git submodule update --init' before building" >&2
fi
