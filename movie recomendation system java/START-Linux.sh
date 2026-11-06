#!/bin/sh
# Movie Recommender System - starts the website on macOS or Linux.
#
# It uses Java 17 or newer if the computer has it. If not, it downloads a private
# copy into the "runtime" folder, once. No admin rights are needed.

cd "$(dirname "$0")" || exit 1

if [ ! -f target/movie-recommender.jar ]; then
    echo
    echo "  The website's program file was not found."
    echo "  Unzip the whole zip file first, then run this from the new folder."
    echo
    exit 1
fi

# True when the given Java runs and is version 17 or newer.
java_ok() {
    version=$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
    [ -n "$version" ] && [ "$version" -ge 17 ] 2>/dev/null
}

# The java program inside the private copy, whose folder layout differs on macOS.
private_java() {
    find runtime -type f -name java -path '*/bin/java' 2>/dev/null | head -n 1
}

download_java() {
    case "$(uname -s)" in
        Darwin) os=mac ;;
        Linux) os=linux ;;
        *) echo "  This system is not supported by the automatic download."; return 1 ;;
    esac
    case "$(uname -m)" in
        x86_64 | amd64) arch=x64 ;;
        arm64 | aarch64) arch=aarch64 ;;
        *) echo "  This processor is not supported by the automatic download."; return 1 ;;
    esac

    if ! command -v curl >/dev/null 2>&1; then
        echo "  The curl program is needed to download Java."
        return 1
    fi

    assets="https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=$arch&image_type=jre&os=$os&vendor=eclipse"
    info=$(curl -fsSL --max-time 60 "$assets") || return 1
    link=$(printf '%s' "$info" | tr -d '\n ' | sed -n 's/.*"package":{"checksum":"[0-9a-f]*","checksum_link":"[^"]*","download_count":[0-9]*,"link":"\([^"]*\)".*/\1/p')
    sum=$(printf '%s' "$info" | tr -d '\n ' | sed -n 's/.*"package":{"checksum":"\([0-9a-f]*\)".*/\1/p')
    if [ -z "$link" ] || [ -z "$sum" ]; then
        echo "  The Java download details could not be read."
        return 1
    fi

    work=$(mktemp -d) || return 1
    echo "  Downloading Java for $os $arch..."
    if ! curl -fL --max-time 900 -o "$work/java.tar.gz" "$link"; then
        rm -rf "$work"
        return 1
    fi

    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$work/java.tar.gz" | cut -d ' ' -f 1)
    else
        actual=$(shasum -a 256 "$work/java.tar.gz" | cut -d ' ' -f 1)
    fi
    if [ "$actual" != "$sum" ]; then
        echo "  The download failed its checksum test, so it was not used."
        rm -rf "$work"
        return 1
    fi

    echo "  Download verified. Unpacking..."
    rm -rf runtime
    mkdir runtime
    tar -xzf "$work/java.tar.gz" -C runtime --strip-components=1
    status=$?
    rm -rf "$work"
    return $status
}

echo
echo "  Movie Recommender System"
echo "  ========================"
echo

JAVA_BIN=""
candidate=$(private_java)
if [ -n "$candidate" ] && java_ok "$candidate"; then
    JAVA_BIN=$candidate
elif [ -n "$JAVA_HOME" ] && java_ok "$JAVA_HOME/bin/java"; then
    JAVA_BIN=$JAVA_HOME/bin/java
elif command -v java >/dev/null 2>&1 && java_ok java; then
    JAVA_BIN=java
fi

if [ -z "$JAVA_BIN" ]; then
    echo "  Java 17 or newer was not found, so a private copy will be downloaded."
    echo "  This only happens once and needs an internet connection."
    echo
    if download_java; then
        candidate=$(private_java)
        if [ -n "$candidate" ] && java_ok "$candidate"; then
            JAVA_BIN=$candidate
        fi
    fi
    if [ -z "$JAVA_BIN" ]; then
        echo
        echo "  Java could not be downloaded. Check the internet connection and try again,"
        echo "  or install Java 17 or newer from https://adoptium.net/ and run this again."
        echo
        exit 1
    fi
    echo
fi

echo "  Starting the website. Your browser opens by itself when it is ready."
echo "  Keep this window open while you use it. Press Ctrl+C or close it to stop."
echo

exec "$JAVA_BIN" -jar target/movie-recommender.jar "$@"
