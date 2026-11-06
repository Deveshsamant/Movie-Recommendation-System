@echo off
REM ===========================================================================
REM  Movie Recommender System (Java) - start the app and open it in the browser
REM
REM  Run setup.bat once first. The app opens your browser itself as soon as the
REM  server is ready to answer.
REM ===========================================================================
setlocal
cd /d "%~dp0"
title Movie Recommender System (Java)

where java >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo   ERROR: Java was not found. Install a JDK, version 17 or newer, from
    echo   https://adoptium.net/ and then run setup.bat.
    echo.
    pause
    exit /b 1
)

if not exist "target\movie-recommender.jar" (
    echo.
    echo   ERROR: the app has not been built on this machine yet.
    echo.
    echo   Run setup.bat first. It builds the app and trains the models, then
    echo   run.bat will work.
    echo.
    pause
    exit /b 1
)

echo.
echo   Movie Recommender System (Java)
echo   ================================
echo.
echo   Starting the server. Your browser opens by itself once it is ready.
echo   Keep this window open while you use the app.
echo   Press Ctrl+C here to stop the server.
echo.

java -jar "target\movie-recommender.jar"

echo.
echo   Server stopped.
pause
exit /b 0
