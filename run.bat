@REM
@REM Copyright © 2026 the original author or authors (piergiorgio@apache.org)
@REM
@REM Licensed under the Apache License, Version 2.0 (the "License");
@REM you may not use this file except in compliance with the License.
@REM You may obtain a copy of the License at
@REM
@REM     http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing, software
@REM distributed under the License is distributed on an "AS IS" BASIS,
@REM WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@REM See the License for the specific language governing permissions and
@REM limitations under the License.
@REM

@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM ============================================================================
REM OpenCrawling Developer CLI Runner (Windows CMD - run.bat)
REM ============================================================================
REM Unified root entrypoint for fast local Maven builds (via oc-maven-builder or
REM host Maven), BuildKit-accelerated Docker Compose stacks, testing, UI dev,
REM and day-to-day developer workflows on Windows.
REM ============================================================================

pushd "%~dp0"

if not defined DOCKER_BUILDKIT set "DOCKER_BUILDKIT=1"
if not defined COMPOSE_DOCKER_CLI_BUILD set "COMPOSE_DOCKER_CLI_BUILD=1"
if not defined HOME set "HOME=%USERPROFILE%"

set "COMMAND=%~1"
if "%COMMAND%"=="" set "COMMAND=help"

REM Collect remaining arguments (%2 .. %9+)
set "PASS_ARGS="
shift
:collect_args
if "%~1"=="" goto args_collected
set "PASS_ARGS=!PASS_ARGS! %1"
shift
goto collect_args
:args_collected

REM ----------------------------------------------------------------------------
REM Command Dispatcher
REM ----------------------------------------------------------------------------
if /I "%COMMAND%"=="help" goto cmd_help
if /I "%COMMAND%"=="-h" goto cmd_help
if /I "%COMMAND%"=="--help" goto cmd_help
if /I "%COMMAND%"=="/?" goto cmd_help

if /I "%COMMAND%"=="build" goto cmd_build
if /I "%COMMAND%"=="mvn:build" goto cmd_build
if /I "%COMMAND%"=="build:offline" goto cmd_build_offline
if /I "%COMMAND%"=="build:host" goto cmd_build_host
if /I "%COMMAND%"=="build:native" goto cmd_build_host
if /I "%COMMAND%"=="build:clean" goto cmd_build_clean
if /I "%COMMAND%"=="mvn" goto cmd_mvn

if /I "%COMMAND%"=="decoupled:up" goto cmd_decoupled_up
if /I "%COMMAND%"=="decoupled:local" goto cmd_decoupled_up
if /I "%COMMAND%"=="decoupled:override" goto cmd_decoupled_override
if /I "%COMMAND%"=="decoupled:down" goto cmd_decoupled_down
if /I "%COMMAND%"=="apps:up" goto cmd_apps_up
if /I "%COMMAND%"=="apps:local" goto cmd_apps_up
if /I "%COMMAND%"=="apps:override" goto cmd_apps_override
if /I "%COMMAND%"=="apps:down" goto cmd_apps_down
if /I "%COMMAND%"=="infra:up" goto cmd_infra_up
if /I "%COMMAND%"=="infra:down" goto cmd_infra_down
if /I "%COMMAND%"=="down" goto cmd_down
if /I "%COMMAND%"=="stop" goto cmd_down

if /I "%COMMAND%"=="status" goto cmd_status
if /I "%COMMAND%"=="ps" goto cmd_status
if /I "%COMMAND%"=="logs" goto cmd_logs
if /I "%COMMAND%"=="test:unit" goto cmd_test_unit
if /I "%COMMAND%"=="test:it" goto cmd_test_it
if /I "%COMMAND%"=="test:local-dev" goto cmd_test_local_dev
if /I "%COMMAND%"=="ui:dev" goto cmd_ui_dev
if /I "%COMMAND%"=="ui:build" goto cmd_ui_build
if /I "%COMMAND%"=="license:format" goto cmd_license_format
if /I "%COMMAND%"=="format" goto cmd_license_format
if /I "%COMMAND%"=="clean" goto cmd_clean

echo [ERROR] Unknown command: "%COMMAND%" 1>&2
echo. 1>&2
echo Run "run.bat help" for a list of available commands. 1>&2
popd
exit /b 1

REM ============================================================================
REM Help Banner
REM ============================================================================
:cmd_help
echo ================================================================================
echo OpenCrawling Developer CLI Runner (run.bat)
echo Fast local builds, Docker Compose stack orchestration, and developer workflows.
echo ================================================================================
echo.
echo USAGE:
echo   run.bat ^<command^> [args...]
echo.
echo 1. BUILD ^& MAVEN COMMANDS (Local %%USERPROFILE%%\.m2 Cache Acceleration):
echo   build, mvn:build          Build all modules via containerized oc-maven-builder (-DskipTests)
echo   build:offline             Fast offline build via oc-maven-builder (-o -DskipTests)
echo   build:host, build:native  Build using host Maven ^& JDK 25 (mvn clean package -DskipTests)
echo   build:clean               Full clean build with unit tests via oc-maven-builder
echo   mvn ^<args...^>             Run custom Maven goals inside oc-maven-builder
echo.
echo 2. DOCKER STACK LIFECYCLE COMMANDS (Local Compose Topologies):
echo   decoupled:up, decoupled:local  Start standalone decoupled stack with local BuildKit cache mounts
echo   decoupled:override        Start decoupled stack via compose override overlay
echo   decoupled:down            Stop decoupled local stack
echo   apps:up, apps:local       Start standalone apps stack (backend, embedding, UI) with local Dockerfiles
echo   apps:override             Start apps stack via compose override overlay
echo   apps:down                 Stop apps local stack
echo   infra:up                  Start core infrastructure only (PostgreSQL/pgvector, Redis, Ollama, Kafka)
echo   infra:down                Stop core infrastructure stack
echo   down, stop                Stop all active local OpenCrawling Compose stacks
echo.
echo 3. DEVELOPER CONVENIENCE ^& PRODUCTIVITY COMMANDS:
echo   status, ps                Show status and port bindings of running local containers
echo   logs [service...]         Follow container logs (defaults to decoupled local stack)
echo   test:unit [args...]       Run unit test suite via oc-maven-builder
echo   test:it [filter...]       Run integration tests with local BuildKit cache overlay (requires bash)
echo   test:local-dev            Run dedicated local Docker build acceleration test suite (requires bash)
echo   ui:dev                    Start React Admin UI Vite dev server on http://localhost:5173
echo   ui:build                  Compile and build React Admin UI production bundle
echo   license:format, format    Apply Apache 2.0 license headers across project files
echo   clean                     Clean Maven target directories (pass --prune to also prune Docker build cache)
echo   help, -h, --help          Display this help guide
echo.
echo EXAMPLES:
echo   run.bat build
echo   run.bat decoupled:up
echo   run.bat mvn test -pl oc-runtime
echo   run.bat ui:dev
popd
exit /b 0

REM ============================================================================
REM 1. Build & Maven Commands
REM ============================================================================
:cmd_build
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Building OpenCrawling via oc-maven-builder (mounting "%HOME%\.m2")...
docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package -DskipTests%PASS_ARGS%
if errorlevel 1 (
    echo [ERROR] Build failed. 1>&2
    popd
    exit /b 1
)
echo [OK] Build completed successfully.
popd
exit /b 0

:cmd_build_offline
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Running fast offline build via oc-maven-builder (-o -DskipTests)...
docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package -o -DskipTests%PASS_ARGS%
if errorlevel 1 (
    echo [ERROR] Offline build failed. 1>&2
    popd
    exit /b 1
)
echo [OK] Offline build completed successfully.
popd
exit /b 0

:cmd_build_host
call :check_host_mvn || (popd & exit /b 1)
echo.
echo ==^> Building OpenCrawling using host Maven...
call mvn clean package -DskipTests%PASS_ARGS%
if errorlevel 1 (
    echo [ERROR] Host Maven build failed. 1>&2
    popd
    exit /b 1
)
echo [OK] Host Maven build completed successfully.
popd
exit /b 0

:cmd_build_clean
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Running full clean build with tests via oc-maven-builder...
docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean package%PASS_ARGS%
if errorlevel 1 (
    echo [ERROR] Full clean build failed. 1>&2
    popd
    exit /b 1
)
echo [OK] Full clean build completed successfully.
popd
exit /b 0

:cmd_mvn
call :check_docker || (popd & exit /b 1)
if "%PASS_ARGS%"=="" (
    echo [ERROR] Please provide Maven arguments, e.g.: run.bat mvn test -pl oc-runtime 1>&2
    popd
    exit /b 1
)
echo.
echo ==^> Executing "mvn%PASS_ARGS%" inside oc-maven-builder...
docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn%PASS_ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%

REM ============================================================================
REM 2. Docker Stack Lifecycle Commands
REM ============================================================================
:cmd_decoupled_up
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Starting standalone decoupled stack with local BuildKit cache mounts...
docker compose -f docker-compose-decoupled-local.yml up -d --build%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Decoupled local stack is up.
popd
exit /b 0

:cmd_decoupled_override
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Starting decoupled stack via compose override overlay...
docker compose -f docker-compose-decoupled.yml -f docker-compose-decoupled.local.yml up -d --build%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Decoupled stack (override mode) is up.
popd
exit /b 0

:cmd_decoupled_down
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Stopping decoupled local stack...
docker compose -f docker-compose-decoupled-local.yml down%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Decoupled local stack stopped.
popd
exit /b 0

:cmd_apps_up
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Starting standalone apps stack (backend, embedding, UI) with local Dockerfiles...
docker compose -f docker-compose-apps-local.yml up -d --build%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Apps local stack is up.
popd
exit /b 0

:cmd_apps_override
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Starting apps stack via compose override overlay...
docker compose -f docker-compose.yml -f docker-compose-apps.yml -f docker-compose-apps.local.yml up -d --build%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Apps stack (override mode) is up.
popd
exit /b 0

:cmd_apps_down
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Stopping apps local stack...
docker compose -f docker-compose-apps-local.yml down%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Apps local stack stopped.
popd
exit /b 0

:cmd_infra_up
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Starting core infrastructure services (PostgreSQL/pgvector, Redis, Ollama, Kafka)...
docker compose up -d%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Core infrastructure is up.
popd
exit /b 0

:cmd_infra_down
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Stopping core infrastructure services...
docker compose down%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Core infrastructure stopped.
popd
exit /b 0

:cmd_down
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Stopping all active local OpenCrawling stacks...
docker compose -f docker-compose-decoupled-local.yml down --remove-orphans%PASS_ARGS% >nul 2>&1
docker compose -f docker-compose-apps-local.yml down --remove-orphans%PASS_ARGS% >nul 2>&1
docker compose down --remove-orphans%PASS_ARGS% >nul 2>&1
echo [OK] All local stacks stopped.
popd
exit /b 0

REM ============================================================================
REM 3. Developer Convenience & Productivity Commands
REM ============================================================================
:cmd_status
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Container Status (Decoupled Local Stack):
docker compose -f docker-compose-decoupled-local.yml ps%PASS_ARGS%
echo.
echo ==^> Container Status (Apps Local Stack):
docker compose -f docker-compose-apps-local.yml ps%PASS_ARGS%
popd
exit /b 0

:cmd_logs
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Following container logs...
docker compose -f docker-compose-decoupled-local.yml logs -f%PASS_ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%

:cmd_test_unit
call :check_docker || (popd & exit /b 1)
echo.
echo ==^> Running unit tests via oc-maven-builder...
docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn test%PASS_ARGS%
if errorlevel 1 (popd & exit /b 1)
echo [OK] Unit tests completed.
popd
exit /b 0

:cmd_test_it
call :check_docker || (popd & exit /b 1)
where bash >nul 2>&1
if errorlevel 1 (
    echo [ERROR] "bash" (Git Bash or WSL) is required on Windows to run ./run-integration-tests.sh. 1>&2
    popd
    exit /b 1
)
echo.
echo ==^> Running integration tests with local BuildKit cache overlay...
set "COMPOSE_FILE=docker-compose-decoupled.yml:docker-compose-decoupled.local.yml"
bash ./run-integration-tests.sh%PASS_ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%

:cmd_test_local_dev
call :check_docker || (popd & exit /b 1)
where bash >nul 2>&1
if errorlevel 1 (
    echo [ERROR] "bash" (Git Bash or WSL) is required on Windows to run ./scripts/test-docker-local-dev.sh. 1>&2
    popd
    exit /b 1
)
echo.
echo ==^> Running dedicated local Docker build acceleration test suite...
bash ./scripts/test-docker-local-dev.sh%PASS_ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%

:cmd_ui_dev
call :check_node || (popd & exit /b 1)
echo.
echo ==^> Starting React Admin UI Vite dev server on :5173...
pushd oc-admin-ui
if not exist "node_modules" (
    echo [INFO] Installing npm dependencies in oc-admin-ui...
    call npm install
    if errorlevel 1 (popd & popd & exit /b 1)
)
call npm run dev%PASS_ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
popd
popd
exit /b %EXIT_CODE%

:cmd_ui_build
call :check_node || (popd & exit /b 1)
echo.
echo ==^> Building React Admin UI production bundle...
pushd oc-admin-ui
if not exist "node_modules" (
    echo [INFO] Installing npm dependencies in oc-admin-ui...
    call npm install
    if errorlevel 1 (popd & popd & exit /b 1)
)
call npm run build%PASS_ARGS%
if errorlevel 1 (popd & popd & exit /b 1)
echo [OK] React Admin UI build completed.
popd
popd
exit /b 0

:cmd_license_format
echo.
echo ==^> Applying Apache 2.0 license headers...
where mvn >nul 2>&1
if %ERRORLEVEL% equ 0 (
    call mvn license:format%PASS_ARGS%
    if errorlevel 1 (popd & exit /b 1)
) else (
    call :check_docker || (popd & exit /b 1)
    docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn license:format%PASS_ARGS%
    if errorlevel 1 (popd & exit /b 1)
)
echo [OK] License headers formatted.
popd
exit /b 0

:cmd_clean
set "PRUNE_CACHE=false"
set "CLEAN_ARGS="
for %%A in (%PASS_ARGS%) do (
    if /I "%%~A"=="--prune" (
        set "PRUNE_CACHE=true"
    ) else (
        set "CLEAN_ARGS=!CLEAN_ARGS! %%A"
    )
)
echo.
echo ==^> Cleaning Maven target directories...
where mvn >nul 2>&1
if %ERRORLEVEL% equ 0 (
    call mvn clean!CLEAN_ARGS!
    if errorlevel 1 (popd & exit /b 1)
) else (
    call :check_docker || (popd & exit /b 1)
    docker compose -f docker-compose-maven-builder.yml run --rm oc-maven-builder mvn clean!CLEAN_ARGS!
    if errorlevel 1 (popd & exit /b 1)
)
if /I "%PRUNE_CACHE%"=="true" (
    call :check_docker || (popd & exit /b 1)
    echo.
    echo ==^> Pruning dangling Docker build cache...
    docker builder prune -f
)
echo [OK] Clean completed.
popd
exit /b 0

REM ============================================================================
REM Pre-flight Subroutines
REM ============================================================================
:check_docker
where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker CLI is not installed or not in PATH. 1>&2
    exit /b 1
)
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker daemon is not running. Please start Docker Desktop. 1>&2
    exit /b 1
)
docker compose version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] "docker compose" plugin is not available. Please install Docker Compose v2+. 1>&2
    exit /b 1
)
if not exist "%HOME%\.m2" mkdir "%HOME%\.m2"
exit /b 0

:check_host_mvn
where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Host "mvn" was not found in PATH. Use "run.bat build" to build via containerized oc-maven-builder instead. 1>&2
    exit /b 1
)
exit /b 0

:check_node
where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js and npm are required for UI commands but were not found in PATH. 1>&2
    exit /b 1
)
where npm >nul 2>&1
if errorlevel 1 (
    echo [ERROR] npm is required for UI commands but was not found in PATH. 1>&2
    exit /b 1
)
exit /b 0
