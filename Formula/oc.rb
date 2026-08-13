#
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
#

class Oc < Formula
  desc "OpenCrawling CLI (oc) for DevSecOps & Terminal Ingestion Management"
  homepage "https://opencrawling.github.io"
  url "https://github.com/opencrawling/opencrawling/releases/download/v1.0.0/oc-cli-1.0.0.jar"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  license "Apache-2.0"

  depends_on "openjdk@25" => :recommended

  def install
    libexec.install "oc-cli-#{version}.jar" => "oc-cli.jar"
    (bin/"oc").write <<~EOS
      #!/usr/bin/env bash
      exec java --enable-preview -jar "#{libexec}/oc-cli.jar" "$@"
    EOS
  end

  test do
    assert_match "OpenCrawling CLI", shell_output("#{bin}/oc --version")
  end
end
