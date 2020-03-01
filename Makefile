# Copyright 2020 Nubank
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

VERSION = $(shell cat version-prefix.txt).$(shell git rev-list --count master)

.PHONY: build

build:
	@./build/uberjar.sh $(VERSION)

clean:
	@rm -rf target

unit-test:
	@./build/test.sh -d test/unit

integration-test:
	@./build/test.sh -d test/integration

release: clean
	@./build/release.sh $(VERSION)
