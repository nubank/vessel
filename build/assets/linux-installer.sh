#!/usr/bin/env sh
# Copyright {{year}} Nubank
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -o errexit
set -o nounset

################################################################################
### This script installs Vessel {{version}} on Linux operating systems.
################################################################################

VESSEL_INSTALL="${VESSEL_INSTALL:-/usr/local/lib/vessel}"

version="{{version}}"

vessel="/tmp/vessel-${version}.tar.gz"

function detect_tool() {
    local tool=$1
    which $tool > /dev/null 2>&1 \
        && echo "$tool is installed" \
            || die "This installer requires $tool to work properly"
}

function die() {
    local message=$1
    >&2 echo -e "Error: $message"
    exit 1
}

function try() {
    echo "$ $@" 1>&2; "$@" || die "cannot $*";
}

if [ ! $(whoami) == "root" ]
then
    die "this installer must be run as root\nPlease, re-run the script as root using sudo"
fi

echo "Evaluating preconditions..."

detect_tool curl

detect_tool tar

echo "Installing Vessel $version"

try mkdir -p $VESSEL_INSTALL

try curl --fail --location --output $vessel https://github.com/nubank/vessel/releases/download/${version}/vessel-${version}.tar.gz

try tar -xvf $vessel -C $VESSEL_INSTALL

try mv $VESSEL_INSTALL/vessel /usr/local/bin/

try chmod a+x /usr/local/bin/vessel

echo "Done! Run vessel --help to see the available commands"
