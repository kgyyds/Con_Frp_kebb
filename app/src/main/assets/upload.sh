#!/system/bin/sh

# ====== self-background ======
if [ -z "$UPLOAD_BG" ]; then
    export UPLOAD_BG=1
    nohup "$0" "$@" >/dev/null 2>&1 &
    exit 0
fi
# ====== background process below ======

SERVER="http://111.170.155.141:27938"
FILE="$1"
PARTS=20

FILENAME=$(basename "$FILE")
FILESIZE=$(stat -c %s "$FILE")
FILEID=$(md5sum "$FILE" | awk '{print $1}')
LOG="./upload_${FILENAME}.log"

PART_SIZE=$((FILESIZE / PARTS))

# 查询服务器已有分块
EXIST=$(curl -s "$SERVER/status.php?file_id=$FILEID")

echo "[*] server has parts: $EXIST" >> "$LOG"

has_part() {
    echo ",$EXIST," | grep -q ",$1,"
}

i=0
while [ $i -lt $PARTS ]; do
    if has_part "$i"; then
        echo "[=] skip part $i (already exists)" >> "$LOG"
        i=$((i + 1))
        continue
    fi

    OFFSET=$((i * PART_SIZE))
    if [ $i -eq $((PARTS - 1)) ]; then
        SIZE=$((FILESIZE - OFFSET))
    else
        SIZE=$PART_SIZE
    fi

    TMP="/tmp/${FILEID}_part_$i"
    dd if="$FILE" bs=1 skip="$OFFSET" count="$SIZE" status=none > "$TMP"
    PART_MD5=$(md5sum "$TMP" | awk '{print $1}')

    echo "[*] upload part $i md5=$PART_MD5" >> "$LOG"

    curl -s -X POST \
        -F "file_id=$FILEID" \
        -F "filename=$FILENAME" \
        -F "index=$i" \
        -F "total=$PARTS" \
        -F "part_md5=$PART_MD5" \
        -F "chunk=@$TMP" \
        "$SERVER/upload.php" >> "$LOG"

    rm -f "$TMP"
    i=$((i + 1))
done

echo "[*] upload finished" >> "$LOG"