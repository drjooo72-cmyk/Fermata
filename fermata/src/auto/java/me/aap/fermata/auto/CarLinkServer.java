package me.aap.fermata.auto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * Car link (added in the "Youssef Auto" fork of Fermata).
 * <p>
 * A tiny, read-only HTTP server that lets the owner's own computer ask the phone for the car data
 * (battery, fuel, speed, ...). It answers only:
 * <ul>
 * <li>requests coming from the owner's private Tailscale network (or the phone itself),</li>
 * <li>with the secret token that was built into the app,</li>
 * <li>to {@code GET /car}, returning JSON.</li>
 * </ul>
 * It can not change anything in the car or the phone.
 * <p>
 * Plain Java on purpose, so it can be tested without Android.
 */
final class CarLinkServer {
	static final int PORT = 8765;
	private static final int MAX_HEADER = 8192;
	private static final int TIMEOUT = 5000;

	private final int port;
	private final byte[] token;
	private final Supplier<String> data;
	private volatile ServerSocket server;

	CarLinkServer(int port, String token, Supplier<String> data) {
		this.port = port;
		this.token = token.getBytes(StandardCharsets.UTF_8);
		this.data = data;
	}

	synchronized void start() throws IOException {
		if (server != null) return;
		ServerSocket s = new ServerSocket();
		s.setReuseAddress(true);
		s.bind(new InetSocketAddress(port));
		server = s;
		Thread t = new Thread(() -> acceptLoop(s), "CarLinkServer");
		t.setDaemon(true);
		t.start();
	}

	synchronized void stop() {
		ServerSocket s = server;
		server = null;
		if (s == null) return;
		try {
			s.close();
		} catch (IOException ignore) {
		}
	}

	boolean isRunning() {
		return server != null;
	}

	private void acceptLoop(ServerSocket s) {
		while (!s.isClosed()) {
			Socket c;
			try {
				c = s.accept();
			} catch (IOException ex) {
				if (s.isClosed()) return;
				continue;
			}
			// One request at a time is enough - the computer asks only now and then
			try (Socket sock = c) {
				sock.setSoTimeout(TIMEOUT);
				handle(sock);
			} catch (Throwable ignore) {
			}
		}
	}

	private void handle(Socket sock) throws IOException {
		OutputStream out = sock.getOutputStream();

		if (!isAllowedAddress(sock.getInetAddress())) {
			reply(out, 403, "{\"error\":\"forbidden\"}");
			return;
		}

		String head = readHead(sock.getInputStream());
		if (head == null) {
			reply(out, 400, "{\"error\":\"bad request\"}");
			return;
		}

		String[] lines = head.split("\r\n");
		String[] req = lines[0].split(" ");
		if (req.length < 2) {
			reply(out, 400, "{\"error\":\"bad request\"}");
			return;
		}

		String tok = null;
		for (int i = 1; i < lines.length; i++) {
			String l = lines[i];
			int idx = l.indexOf(':');
			if (idx <= 0) continue;
			String name = l.substring(0, idx).trim();
			String value = l.substring(idx + 1).trim();
			if (name.equalsIgnoreCase("X-Car-Token")) tok = value;
			else if (name.equalsIgnoreCase("Authorization") && value.startsWith("Bearer "))
				tok = value.substring(7).trim();
		}

		if ((tok == null) ||
				!MessageDigest.isEqual(token, tok.getBytes(StandardCharsets.UTF_8))) {
			reply(out, 401, "{\"error\":\"unauthorized\"}");
			return;
		}

		String path = req[1];
		int q = path.indexOf('?');
		if (q >= 0) path = path.substring(0, q);

		if (!"GET".equals(req[0]) || !"/car".equals(path)) {
			reply(out, 404, "{\"error\":\"not found\"}");
			return;
		}

		String json;
		try {
			json = data.get();
		} catch (Throwable ex) {
			reply(out, 500, "{\"error\":\"" + ex.getClass().getSimpleName() + "\"}");
			return;
		}
		reply(out, 200, json);
	}

	private static String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream(512);
		int state = 0;
		while (b.size() < MAX_HEADER) {
			int c = in.read();
			if (c < 0) return null;
			b.write(c);
			// Looking for \r\n\r\n
			if ((c == '\r') && ((state == 0) || (state == 2))) state++;
			else if ((c == '\n') && ((state == 1) || (state == 3))) state++;
			else state = (c == '\r') ? 1 : 0;
			if (state == 4) return b.toString(StandardCharsets.ISO_8859_1.name());
		}
		return null;
	}

	private static void reply(OutputStream out, int code, String body) throws IOException {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		String status = switch (code) {
			case 200 -> "OK";
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			default -> "Internal Server Error";
		};
		String head = "HTTP/1.1 " + code + ' ' + status + "\r\n" +
				"Content-Type: application/json; charset=utf-8\r\n" +
				"Cache-Control: no-store\r\n" +
				"Content-Length: " + b.length + "\r\n" +
				"Connection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.ISO_8859_1));
		out.write(b);
		out.flush();
	}

	/**
	 * The phone itself, or the Tailscale ranges: 100.64.0.0/10 and fd7a:115c:a1e0::/48.
	 */
	static boolean isAllowedAddress(InetAddress a) {
		if (a == null) return false;
		if (a.isLoopbackAddress()) return true;
		byte[] b = a.getAddress();

		if (a instanceof Inet6Address) {
			if (isMappedV4(b)) return isTailscaleV4(b[12], b[13]);
			return ((b[0] & 0xFF) == 0xFD) && ((b[1] & 0xFF) == 0x7A) && ((b[2] & 0xFF) == 0x11) &&
					((b[3] & 0xFF) == 0x5C) && ((b[4] & 0xFF) == 0xA1) && ((b[5] & 0xFF) == 0xE0);
		}

		return (a instanceof Inet4Address) && isTailscaleV4(b[0], b[1]);
	}

	private static boolean isTailscaleV4(byte b0, byte b1) {
		return ((b0 & 0xFF) == 100) && ((b1 & 0xC0) == 64);
	}

	private static boolean isMappedV4(byte[] b) {
		for (int i = 0; i < 10; i++) {
			if (b[i] != 0) return false;
		}
		return ((b[10] & 0xFF) == 0xFF) && ((b[11] & 0xFF) == 0xFF);
	}
}
