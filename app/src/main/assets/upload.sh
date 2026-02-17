#!/system/bin/sh

# ====================== 配置区 ======================
URL="http://服务器IP/upload.php"
LOG="./upload.log"
# ====================================================

if [ $# -ne 1 ]; then
    echo "用法: $0 <文件路径>"
    exit 1
fi

FILE="$1"
if [ ! -f "$FILE" ]; then
    echo "文件不存在: $FILE"
    exit 1
fi

# 后台运行自己，不堵塞 shell
if [ -z "$BACKGROUND" ]; then
    export BACKGROUND=1
    nohup sh "$0" "$FILE" >> "$LOG" 2>&1 &
    echo "上传已后台运行"
    echo "查看日志: tail -f $LOG"
    exit 0
fi

# ====================== 正式上传逻辑 ======================
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log "==================== 开始上传 ===================="
log "文件: $FILE"

while true; do
    # 获取服务器已上传大小
    UPLOADED=$(curl -s "$URL" -d "name=$(basename "$FILE")" 2>/dev/null \
        | grep -o '"total_size":[0-9]*' \
        | cut -d: -f2 \
        | tr -cd '0-9')

    UPLOADED=${UPLOADED:-0}
    LOCAL_SIZE=$(ls -l "$FILE" | awk '{print $5}')

    log "进度: $UPLOADED / $LOCAL_SIZE"

    # 传完退出
    if [ "$UPLOADED" -ge "$LOCAL_SIZE" ]; then
        log "上传完成"
        break
    fi

    # 断点续传
    curl -s -X POST \
        -H "Content-Type: application/octet-stream" \
        -d "name=$(basename "$FILE")" \
        -d "offset=$UPLOADED" \
        --data-binary "@$FILE#$UPLOADED" \
        "$URL" >/dev/null 2>&1

    sleep 2
done
