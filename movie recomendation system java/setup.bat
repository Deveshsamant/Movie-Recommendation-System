@echo off
REM ===========================================================================
REM  Movie Recommender System (Java) - one-time setup for a new Windows machine
REM
REM  Builds the app with the bundled Maven Wrapper, saves your TMDB key, downloads
REM  MovieLens, fetches film metadata and pre-trains every model.
REM  Run this once. Afterwards, use START-Windows.bat.
REM ===========================================================================
setlocal
title Movie Recommender System (Java) - Setup
cd /d "%~dp0"

echo.
echo   Movie Recommender System (Java) - Setup
echo   ========================================
echo.

REM ---------------------------------------------------------------- 1. Java --
echo [1/5] Looking for Java...
where javac >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo   ERROR: a JDK was not found on this machine.
    echo.
    echo   Install a JDK, version 17 or newer, from https://adoptium.net/
    echo   then close this window and run setup.bat again.
    echo.
    goto :failed
)
java -version

REM ---------------------------------------------------------------- 2. build --
echo.
echo [2/5] Building the app. The first build downloads Maven and the libraries...
echo.
call "%~dp0mvnw.cmd" -q -B -DskipTests package
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo   ERROR: the build failed. Check your internet connection and run
    echo   setup.bat again.
    goto :failed
)
echo       built target\movie-recommender.jar

REM ----------------------------------------------------------- 3. TMDB key --
echo.
echo [3/5] TMDB API key...
if exist "config.json" (
    echo       config.json already exists - leaving it untouched
    goto :key_done
)
copy /y "config.example.json" "config.json" >nul
echo.
echo   Posters, cast, directors and keywords come from TMDB.
echo   Get a free key at https://www.themoviedb.org/settings/api
echo   Press Enter to skip - the app still runs, just with placeholder artwork.
echo.
set "TMDB_KEY_INPUT="
set /p "TMDB_KEY_INPUT=  Paste your TMDB API key and press Enter: "
if not defined TMDB_KEY_INPUT goto :key_blank
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=(Resolve-Path 'config.json').Path; $d=Get-Content -Raw -LiteralPath $p | ConvertFrom-Json; $d.PSObject.Properties.Remove('_comment'); $d.tmdb_api_key=$env:TMDB_KEY_INPUT.Trim(); [IO.File]::WriteAllText($p, ($d | ConvertTo-Json -Depth 5))"
if %ERRORLEVEL% NEQ 0 (
    echo   ERROR: could not write the key into config.json
    goto :failed
)
echo       key saved to config.json
goto :key_done
:key_blank
echo       skipped - running without posters
:key_done

REM ------------------------------------------- 4. dataset and film metadata --
echo.
echo [4/5] Downloading MovieLens and fetching film metadata...
echo.
java -jar "target\movie-recommender.jar" --enrich
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo   WARNING: this step did not finish cleanly. The app will retry in the
    echo   background when you start it, so this is not fatal.
)

REM ----------------------------------------------------- 5. train the models --
echo.
echo [5/5] Training all the models and computing the scoreboard...
echo       This only happens once - everything is cached afterwards.
echo.
java -jar "target\movie-recommender.jar" --prepare
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo   ERROR: model training failed.
    goto :failed
)

echo.
echo   ========================================
echo   Setup complete.
echo.
echo   Double-click START-Windows.bat to start the app.
echo.
pause
exit /b 0

:failed
echo.
pause
exit /b 1
