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
### Executes Vessel.
################################################################################

VESSEL_INSTALL="${VESSEL_INSTALL:-/usr/local/lib/vessel}"

VESSEL_JVM_OPTS="${VESSEL_JVM_OPTS:--Xmx256m}"

version="{{version}}"

vessel="$VESSEL_INSTALL/vessel-$version.jar"

if [ ! -f $vessel ]
then
    >&2 echo "Error: $vessel could not be found."
    >&2 echo "Install Vessel by executing the installer script available at https://github.com/nubank/vessel/archive/vessel-${version}.tar.gz"
    exit 1
fi

exec java "$VESSEL_JVM_OPTS" -jar $vessel "$@"
