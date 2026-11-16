package io.freemytv;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Plain HTTP/1.1 to a server on the home network (Home Assistant at http://192.168.0.10:8123). The app keeps Android's
 * cleartext block for everything else (usesCleartextTraffic="false"): only HomeAssistant comes here, and only for a
 * local host (HomeAssistant.local). One request per connection, no redirects, answers up to 4 MB.
 */
final class LanHttp {
    private LanHttp() {}

    private static final int MAX_BODY = 4 << 20;

    static String request(URL url, String method, String token, String body) throws IOException {
        int port = url.getPort() < 0 ? 80 : url.getPort();
        String path = url.getFile().isEmpty() ? "/" : url.getFile();
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(url.getHost(), port), 3000);
            s.setSoTimeout(8000);
            StringBuilder head = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(url.getHost()).append(port == 80 ? "" : ":" + port).append("\r\n")
                    .append("Authorization: Bearer ").append(token).append("\r\n")
                    .append("User-Agent: FreeMyTV\r\nAccept: application/json\r\nConnection: close\r\n");
            if (body != null) head.append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");
            head.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            return response(new BufferedInputStream(s.getInputStream()));
        }
    }

    private static String response(InputStream in) throws IOException {
        String status = line(in);
        String[] parts = status.split(" ");
        int code;
        try {
            code = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP answer");
        }
        long length = -1;
        boolean chunked = false;
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            String lower = h.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) length = Long.parseLong(lower.substring(15).trim());
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
        }
        if (code == 401) throw new IOException("HTTP 401");
        if (code >= 300 && code < 400) throw new IOException("HTTP " + code + ": redirects aren't followed");
        if (code != 200 && code != 201) throw new IOException("HTTP " + code);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (chunked) {
            for (long size = chunkSize(in); size > 0; size = chunkSize(in)) {
                copy(in, body, size);
                line(in);   // the chunk's CRLF
            }
        } else {
            copy(in, body, length);
        }
        return body.toString(StandardCharsets.UTF_8.name());
    }

    private static long chunkSize(InputStream in) throws IOException {
        String l = line(in);
        int semi = l.indexOf(';');
        try {
            return Long.parseLong((semi < 0 ? l : l.substring(0, semi)).trim(), 16);
        } catch (NumberFormatException e) {
            throw new IOException("bad chunk");
        }
    }

    /** `length` bytes, or up to the end with -1 (Connection: close). */
    private static void copy(InputStream in, ByteArrayOutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long left = length;
        while (length < 0 || left > 0) {
            int n = in.read(buffer, 0, (int) (length < 0 ? buffer.length : Math.min(buffer.length, left)));
            if (n < 0) {
                if (length < 0) return;
                throw new IOException("the answer was cut");
            }
            out.write(buffer, 0, n);
            left -= n;
            if (out.size() > MAX_BODY) throw new IOException("answer too large");
        }
    }

    /** One header line, without its CRLF. */
    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c = in.read(); c >= 0; c = in.read()) {
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new IOException("header too long");
        }
        if (b.length() == 0) throw new IOException("the server closed the connection");
        return b.toString();
    }
}
