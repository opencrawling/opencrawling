# Copyright © 2026 the original author or authors (piergiorgio@apache.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

class Oc < Formula
  desc "OpenCrawling CLI (oc) for DevSecOps & Terminal Ingestion Management"
  homepage "https://opencrawling.org"
  url "https://github.com/opencrawling/opencrawling/releases/download/v1.0.4/oc-cli-1.0.4.jar"
  sha256 "8ec4debd5eef324a4d7f09a5c28cdb0855899d7f03a4f57a8244135397a93757"
  license "Apache-2.0"

  depends_on "openjdk@25"

  def install
    libexec.install "oc-cli-#{version}.jar" => "oc-cli.jar"
    (bin/"oc").write <<~EOS
      #!/usr/bin/env bash
      if [ -n "${JAVA_HOME}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        JAVA_CMD="${JAVA_HOME}/bin/java"
      elif [ -x "#{formula_opt_bin("openjdk@25")}/java" ]; then
        JAVA_CMD="#{formula_opt_bin("openjdk@25")}/java"
      else
        JAVA_CMD="java"
      fi
      exec "${JAVA_CMD}" --enable-preview -jar "#{libexec}/oc-cli.jar" "$@"
    EOS
  end

  test do
    assert_match "OpenCrawling CLI", shell_output("#{bin}/oc --version")
  end
end
