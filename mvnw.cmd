@echo off
@rem --------------------------------------------------------------------------
@rem NexoSync build bootstrap.
@rem
@rem Downloads a pinned Apache Maven distribution into the user's home
@rem directory the first time it runs, then delegates every argument to it.
@rem
@rem   mvnw.cmd package
@rem --------------------------------------------------------------------------
setlocal

set "MAVEN_VERSION=3.9.11"
set "DIST_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
set "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists"
set "MAVEN_DIR=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%"

if not exist "%MAVEN_DIR%\bin\mvn.cmd" (
    echo [NexoSync] Apache Maven %MAVEN_VERSION% not found locally, downloading...
    if not exist "%WRAPPER_HOME%" mkdir "%WRAPPER_HOME%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ProgressPreference='SilentlyContinue';" ^
        "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%WRAPPER_HOME%\maven.zip';" ^
        "Expand-Archive -Path '%WRAPPER_HOME%\maven.zip' -DestinationPath '%WRAPPER_HOME%' -Force;" ^
        "Remove-Item '%WRAPPER_HOME%\maven.zip' -Force"
    if errorlevel 1 (
        echo [NexoSync] Failed to download Apache Maven.
        exit /b 1
    )
)

call "%MAVEN_DIR%\bin\mvn.cmd" %*
