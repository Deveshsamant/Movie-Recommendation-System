@echo off
REM ===========================================================================
REM  Movie Recommender System - double-click this file to start the website.
REM
REM  It uses Java 17 or newer if the computer has it. If not, it downloads a
REM  private copy into the "runtime" folder, once. No admin rights are needed.
REM ===========================================================================
setlocal
cd /d "%~dp0"
title Movie Recommender System

if not exist "target\movie-recommender.jar" (
    echo.
    echo   The website's program file was not found.
    echo.
    echo   If you opened this from inside the zip file, close it, right-click the
    echo   zip, choose "Extract All", and run START-Windows.bat from the new folder.
    echo.
    echo   If you are working from the source code, run setup.bat first.
    echo.
    goto :stop
)

echo.
echo   Movie Recommender System
echo   ========================
echo.

REM ---- 1. a private copy downloaded earlier, 2. JAVA_HOME, 3. the system's Java
set "JAVA_EXE="
call :try_java "%~dp0runtime\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME call :try_java "%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
    where java >nul 2>&1
    if not errorlevel 1 call :try_java java
)

if not defined JAVA_EXE (
    echo   Java 17 or newer was not found, so a private copy will be downloaded.
    echo   This only happens once and needs an internet connection.
    echo.
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\download-java.ps1" -Destination "%~dp0runtime"
    if errorlevel 1 goto :download_failed
    call :try_java "%~dp0runtime\bin\java.exe"
    if not defined JAVA_EXE goto :download_failed
    echo.
)

echo   Starting the website. Your browser opens by itself when it is ready.
echo   Keep this window open while you use it. Close it to stop the website.
echo.

"%JAVA_EXE%" -jar "target\movie-recommender.jar" %*

echo.
echo   The website has stopped.
goto :stop

:download_failed
echo.
echo   Java could not be downloaded. Check the internet connection and try again,
echo   or install Java 17 or newer from https://adoptium.net/ and run this file again.
echo.

:stop
pause
exit /b 0

REM Sets JAVA_EXE when the given Java runs and is version 17 or newer.
:try_java
REM Check first: running a missing program in a pipeline ends this whole script.
if /i not "%~1"=="java" if not exist "%~1" exit /b 0
"%~1" -version 2>&1 | findstr /r /c:"version \"1[7-9]" /c:"version \"[2-9][0-9]" >nul
if not errorlevel 1 set "JAVA_EXE=%~1"
exit /b 0
