#!/bin/sh
set -e
if [ -d /fonts ]; then
    count=0
    for f in /fonts/*.ttf /fonts/*.ttc /fonts/*.otf; do
        if [ -f "$f" ]; then
            cp "$f" /usr/share/fonts/custom/
            count=$((count+1))
        fi
    done
    if [ "$count" -gt 0 ]; then
        fc-cache -f 2>/dev/null
        echo "Loaded $count custom fonts from /fonts"
    fi
fi
exec java -jar /app/jrxml-renderer.jar
