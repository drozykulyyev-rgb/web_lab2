#!/bin/sh
set -eu

cd "$(dirname "$0")"
LAB_ROOT=$(pwd -P)
LAB_USER=$(id -un)
LAB_GROUP=$(id -gn 2>/dev/null || printf '#%s' "$(id -g)")
HTTPD=${HTTPD:-/usr/local/sbin/httpd}
JAVA_BIN=${JAVA_BIN:-java}
APACHE_MODULE_DIR=${APACHE_MODULE_DIR:-/usr/local/libexec/apache24}
APACHE_MIME_FILE=${APACHE_MIME_FILE:-/usr/local/etc/apache24/mime.types}
export LAB_ROOT LAB_USER LAB_GROUP APACHE_MODULE_DIR APACHE_MIME_FILE

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

check_ports() {
    for port in "$HTTP_PORT" "$FCGI_PORT"; do
        case "$port" in
            ''|*[!0-9]*|0*) fail "Ports must be integers from 1024 to 65535." ;;
        esac
        [ "${#port}" -le 5 ] || fail "Port is too large."
        [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] || fail "Invalid port: $port"
    done
    [ "$HTTP_PORT" != "$FCGI_PORT" ] || fail "HTTP and FastCGI ports must differ."
    export HTTP_PORT FCGI_PORT
}

read_ports() {
    [ -f config/helios.ports ] || fail "Run first: sh helios.sh setup HTTP_PORT FCGI_PORT"
    read -r HTTP_PORT FCGI_PORT < config/helios.ports
    check_ports
}

check_files() {
    command -v "$HTTPD" >/dev/null 2>&1 || fail "Apache not found: $HTTPD"
    command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "Java not found: $JAVA_BIN"
    for file in build/lab2.jar public/index.html public/lab2.css public/lab2.js config/httpd-helios.conf; do
        [ -r "$file" ] || fail "Missing file: $file"
    done
    version=$("$JAVA_BIN" -Xms16m -Xmx128m -version 2>&1) || fail "Java cannot start: $version"
    major=$(printf '%s\n' "$version" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
    case "$major" in
        ''|*[!0-9]*) fail "Cannot determine Java version: $version" ;;
    esac
    [ "$major" -ge 17 ] || fail "Java 17+ is required. Found: $version"
    mkdir -p logs run
    "$HTTPD" -t -f "$LAB_ROOT/config/httpd-helios.conf"
}

read_pid() {
    pid=$(cat "$1" 2>/dev/null) || return 1
    case "$pid" in
        ''|*[!0-9]*) fail "Invalid PID file: $1" ;;
    esac
    [ "$pid" -gt 1 ] || fail "Invalid PID in $1"
    kill -0 "$pid" 2>/dev/null
}

check_stopped() {
    for file in run/java.pid run/httpd.pid; do
        if read_pid "$file"; then
            fail "Process $pid is running. Use: sh helios.sh stop"
        fi
    done
}

verify_process() {
    process=$(ps -ww -p "$pid" -o args=) || fail "Cannot inspect process $pid."
    case "$process" in
        *"$1"*) return 0 ;;
        *) fail "PID $pid belongs to another process; it was not stopped." ;;
    esac
}

stop_java() {
    if read_pid run/java.pid; then
        verify_process "$LAB_ROOT/build/lab2.jar --fcgi $FCGI_PORT"
        kill "$pid"
        count=0
        while kill -0 "$pid" 2>/dev/null; do
            count=$((count + 1))
            [ "$count" -lt 10 ] || fail "Java did not stop; see logs/java.log."
            sleep 1
        done
    fi
    rm -f run/java.pid
}

case "${1:-help}" in
    setup)
        [ "$#" -eq 3 ] || fail "Usage: sh helios.sh setup HTTP_PORT FCGI_PORT"
        HTTP_PORT=$2
        FCGI_PORT=$3
        check_ports
        check_stopped
        check_files
        printf '%s %s\n' "$HTTP_PORT" "$FCGI_PORT" > config/helios.ports
        printf 'Configured for %s in %s\nRun: sh helios.sh start\n' "$LAB_USER" "$LAB_ROOT"
        ;;
    check)
        read_ports
        check_files
        printf 'Files, Java and Apache syntax: OK. Port availability is checked on start.\n'
        ;;
    start)
        read_ports
        check_stopped
        check_files
        nohup "$JAVA_BIN" -Xms16m -Xmx128m -jar "$LAB_ROOT/build/lab2.jar" --fcgi "$FCGI_PORT" > logs/java.log 2>&1 < /dev/null &
        java_pid=$!
        printf '%s\n' "$java_pid" > run/java.pid
        count=0
        until grep -F "FastCGI: 127.0.0.1:$FCGI_PORT" logs/java.log >/dev/null 2>&1; do
            if ! kill -0 "$java_pid" 2>/dev/null; then
                cat logs/java.log >&2
                rm -f run/java.pid
                fail "Java failed to start."
            fi
            count=$((count + 1))
            if [ "$count" -ge 10 ]; then
                stop_java
                fail "Java startup timeout; see logs/java.log."
            fi
            sleep 1
        done
        if ! "$HTTPD" -f "$LAB_ROOT/config/httpd-helios.conf" -k start; then
            stop_java
            fail "Apache failed to start; see logs/apache-error.log."
        fi
        sleep 1
        if ! read_pid run/httpd.pid; then
            stop_java
            fail "Apache is not running; see logs/apache-error.log."
        fi
        if command -v curl >/dev/null 2>&1; then
            if ! curl --noproxy '*' -fsS --max-time 10 "http://127.0.0.1:$HTTP_PORT/api/history" > run/health.json; then
                fail "Processes started, but API check failed. Check logs; stop with: sh helios.sh stop"
            fi
            if ! grep -F '"results":[' run/health.json >/dev/null; then
                fail "Unexpected API response. Check run/health.json and logs."
            fi
            printf 'Apache -> Java API: OK\n'
        else
            printf 'curl is unavailable; verify the page and API in a browser.\n'
        fi
        printf 'Started. HTTP port: %s; FastCGI port: %s\n' "$HTTP_PORT" "$FCGI_PORT"
        printf 'Local check: http://127.0.0.1:%s/\n' "$HTTP_PORT"
        ;;
    status)
        read_ports
        for file in run/java.pid run/httpd.pid; do
            if read_pid "$file"; then
                case "$file" in
                    run/java.pid) verify_process "$LAB_ROOT/build/lab2.jar --fcgi $FCGI_PORT" ;;
                    run/httpd.pid) verify_process "$LAB_ROOT/config/httpd-helios.conf" ;;
                esac
                printf '%s: running (PID %s)\n' "$file" "$pid"
            else
                printf '%s: stopped\n' "$file"
            fi
        done
        ;;
    stop)
        read_ports
        if read_pid run/httpd.pid; then
            verify_process "$LAB_ROOT/config/httpd-helios.conf"
            "$HTTPD" -f "$LAB_ROOT/config/httpd-helios.conf" -k stop
            count=0
            while kill -0 "$pid" 2>/dev/null; do
                count=$((count + 1))
                [ "$count" -lt 10 ] || fail "Apache did not stop; see logs/apache-error.log."
                sleep 1
            done
        fi
        stop_java
        printf 'Stop requested. In-memory history is cleared when Java stops.\n'
        ;;
    *)
        printf 'Usage: sh helios.sh setup HTTP_PORT FCGI_PORT\n'
        printf '       sh helios.sh check|start|status|stop\n'
        ;;
esac
