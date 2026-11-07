package com.trae.olvt.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 本机反向代理：127.0.0.1:12401 -> 127.0.0.1:12393。
 *
 * 存在的唯一原因：OLVT 的 index.html 没有 <meta name="viewport">，Gecko 会按
 * 980px 桌面视口排版再整体缩小，导致左侧会话面板固定占约 46%、比例失真。
 * 在 HTML 响应里插入 viewport 声明即可让页面按手机宽度排版。
 *
 * WebSocket（Upgrade: websocket）按原始 TCP 隧道转发，保证 /client-ws 不受影响。
 */
final class LocalProxy {

    static final int PROXY_PORT = 12401;
    private static final String TARGET_HOST = "127.0.0.1";
    private static final int TARGET_PORT = 12393;
    // initial-scale=0.9：布局视口放宽到约 400 CSS px 再整体缩到 90%，
    // 视觉上所有元素（文字/按钮/立绘）小 10%，但仍铺满屏幕。
    private static final String VIEWPORT_TAG =
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=0.9, viewport-fit=cover\">";

    private static ServerSocket server;

    private LocalProxy() {
    }

    static void start() {
        if (server != null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress("127.0.0.1", PROXY_PORT));
                server = s;
                while (true) {
                    final Socket client = s.accept();
                    new Thread(() -> handle(client), "olvt-proxy-conn").start();
                }
            } catch (IOException ignored) {
                // 端口被占用或已关闭，保持静默
            }
        }, "olvt-proxy");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(20000);
            InputStream in = client.getInputStream();
            byte[] head = readHead(in);
            if (head == null) {
                return;
            }
            String text = new String(head, StandardCharsets.ISO_8859_1);
            String lower = text.toLowerCase();

            if (lower.contains("\r\nupgrade: websocket")) {
                upstream = new Socket(TARGET_HOST, TARGET_PORT);
                upstream.setTcpNoDelay(true);
                OutputStream uo = upstream.getOutputStream();
                uo.write(head);
                uo.flush();
                client.setSoTimeout(0);
                pipeBoth(client, upstream);
                return;
            }

            String[] lines = text.split("\r\n");
            String[] reqLine = lines[0].split(" ");
            String method = reqLine.length > 0 ? reqLine[0] : "GET";
            String path = reqLine.length > 1 ? reqLine[1] : "/";

            int contentLength = 0;
            for (String line : lines) {
                int i = line.indexOf(':');
                if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(i + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            byte[] reqBody = new byte[contentLength];
            readFully(in, reqBody);

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://" + TARGET_HOST + ":" + TARGET_PORT + path).openConnection();
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            for (String line : lines) {
                int i = line.indexOf(':');
                if (i <= 0) {
                    continue;
                }
                String key = line.substring(0, i).trim();
                String value = line.substring(i + 1).trim();
                if (key.equalsIgnoreCase("Content-Type") || key.equalsIgnoreCase("Accept")
                        || key.equalsIgnoreCase("Cookie") || key.equalsIgnoreCase("Range")
                        || key.equalsIgnoreCase("User-Agent") || key.equalsIgnoreCase("Origin")) {
                    conn.setRequestProperty(key, value);
                }
            }
            if (contentLength > 0) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(reqBody);
            }

            int code = conn.getResponseCode();
            InputStream respStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = readAll(respStream);
            String contentType = conn.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                data = injectViewport(data);
            }

            String reason = conn.getResponseMessage();
            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 ").append(code).append(' ')
                    .append(reason == null || reason.isEmpty() ? "OK" : reason).append("\r\n");
            if (contentType != null) {
                header.append("Content-Type: ").append(contentType).append("\r\n");
            }
            header.append("Content-Length: ").append(data.length).append("\r\n");
            header.append("Cache-Control: no-store\r\n");
            header.append("Connection: close\r\n\r\n");

            OutputStream out = client.getOutputStream();
            out.write(header.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(data);
            out.flush();
        } catch (Exception ignored) {
            // 单个请求失败不影响其它请求
        } finally {
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private static byte[] injectViewport(byte[] html) {
        String s = new String(html, StandardCharsets.UTF_8);
        int i = s.indexOf("<head>");
        if (i >= 0) {
            s = s.substring(0, i + 6) + VIEWPORT_TAG + s.substring(i + 6);
        } else if (s.contains("<html")) {
            int j = s.indexOf('>', s.indexOf("<html"));
            s = s.substring(0, j + 1) + "<head>" + VIEWPORT_TAG + "</head>" + s.substring(j + 1);
        } else {
            s = VIEWPORT_TAG + s;
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            int n = buf.size();
            if (n >= 4) {
                byte[] a = buf.toByteArray();
                if (a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                    return a;
                }
            }
            if (n > 65536) {
                return buf.toByteArray();
            }
        }
        return buf.size() == 0 ? null : buf.toByteArray();
    }

    private static void readFully(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void pipeBoth(Socket a, Socket b) {
        Thread t = new Thread(() -> pipe(a, b), "olvt-proxy-up");
        t.start();
        pipe(b, a);
        try {
            t.join();
        } catch (InterruptedException ignored) {
        }
        closeQuietly(a);
        closeQuietly(b);
    }

    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
