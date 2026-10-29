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

# CSGQL mock: an IBM FileNet Content Services GraphQL API emulator for testing the FileNet repository connector
FROM python:3.13.9-slim

RUN useradd --system --uid 10001 csgql
WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY schema.graphql csgql_mock.py /app/

ENV CSGQL_PORT=8080 PYTHONUNBUFFERED=1
EXPOSE 8080
USER csgql

HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/content-services-graphql/ping', timeout=2)"

ENTRYPOINT ["python", "/app/csgql_mock.py"]
