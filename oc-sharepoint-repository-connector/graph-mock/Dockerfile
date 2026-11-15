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

# Graph mock: a Microsoft Entra ID + Microsoft Graph (SharePoint) emulator for testing the SharePoint repository connector
FROM python:3.13.9-slim

RUN useradd --system --uid 10001 graph
WORKDIR /app
COPY graph_mock.py /app/graph_mock.py

ENV GRAPH_PORT=8080 PYTHONUNBUFFERED=1
EXPOSE 8080
USER graph

HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/mock/stats', timeout=2)"

ENTRYPOINT ["python", "/app/graph_mock.py"]
