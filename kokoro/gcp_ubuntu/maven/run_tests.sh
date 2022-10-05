#!/bin/bash
# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

set -euo pipefail

if [[ -n "${KOKORO_ARTIFACTS_DIR:-}" ]] ; then
  TINK_BASE_DIR="$(echo "${KOKORO_ARTIFACTS_DIR}"/git*)"
  cd "${TINK_BASE_DIR}/tink_java"
  use_bazel.sh "$(cat .bazelversion)"
fi

./kokoro/testutils/update_android_sdk.sh
# Install the latest snapshot locally.
./maven/maven_deploy_library.sh install tink maven/tink-java.pom.xml HEAD
# Run examples/helloworld against the local artifact.
./kokoro/testutils/test_maven_snapshot.sh -l "examples/helloworld/pom.xml"