package home.cleanframe.tv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static home.cleanframe.tv.UploadTests.check;
import static home.cleanframe.tv.UploadTests.eq;
import static home.cleanframe.tv.UploadTests.obj;
import static home.cleanframe.tv.UploadTests.run;

/**
 * UploadServer end to end over a real loopback socket in this JVM, against a copy of the shared
 * fixture tests/fixtures/user-library/R (its token 0123...cdef, code 482913, group gholiday1), with a
 * stub processor that writes files: start/stop and the bound address, the token, the code page and
 * its rate limit, every endpoint, the limits (Content-Length before the body, the photo size, room in
 * a group and on the TV, two uploads at a time, four connections), traversal, multipart edge cases
 * (a boundary split across reads, missing parts, oversize parts, a cut-off body), header injection,
 * timeouts and the head, body and upload deadlines (enforced inside reads, against clients that never
 * stop trickling), cross-origin POSTs refused before they count, the security headers, no stack
 * traces, and logs without tokens or codes.
 */
final class UploadServerTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef", CODE = "482913";
    static final long NOW = 1_761_901_000_000L, EXPIRES = 1_761_901_500_000L;
    static final Path FIXTURE = Paths.get("tests/fixtures/user-library/R");
    static final Path PAGE = Paths.get("android/app/src/main/assets/upload");
    /** Every running Rig's server: the request helpers wait until none of them holds their connection. */
    static final Set<UploadServer> LIVE = ConcurrentHashMap.newKeySet();

    /** Writes wall.jpg, thumb.jpg and meta.json (last), like the real one; or refuses / fails / blocks. */
    static final class Stub implements PhotoProcessor {
        final AtomicInteger calls = new AtomicInteger();
        volatile String refuse;
        volatile RuntimeException boom;
        volatile Error fatal;
        volatile boolean writeNothing;
        volatile CountDownLatch hold;
        volatile CountDownLatch entered = new CountDownLatch(0);
        volatile byte[] last;
        volatile long lastLength;
        volatile String lastName;
        volatile PhotoMath.Crop lastCrop;
        volatile long lastEdited;

        @Override
        public void recrop(File src, File dir, String pid, String name, long added, PhotoMath.Crop crop, long edited) throws Refused, IOException {
            lastEdited = edited;
            process(src, dir, pid, name, added, crop);
            Files.copy(src.toPath(), new File(dir, "src.jpg").toPath());
            Files.write(new File(dir, "meta.json").toPath(), PhotoMath.metaJson(pid, name, added,
                    new Readability.Fields("dark", "light", "mist", .12, 0, false, false), crop, edited));
        }

        @Override
        public void process(File upload, File dir, String pid, String name, long added, PhotoMath.Crop crop) throws Refused, IOException {
            calls.incrementAndGet();
            lastCrop = crop;
            lastLength = upload.length();
            last = lastLength <= 1 << 20 ? Files.readAllBytes(upload.toPath()) : null;
            lastName = name;
            entered.countDown();
            CountDownLatch h = hold;
            if (h != null) {
                try {
                    h.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
            }
            String r = refuse;
            if (r != null) {
                throw new Refused(r, r.equals("type") ? TYPE : r.equals("pixels") ? PIXELS : r.equals("small") ? SMALL : BROKEN);
            }
            if (boom != null) throw boom;
            if (fatal != null) throw fatal;
            if (writeNothing) return;
            Files.write(new File(dir, "wall.jpg").toPath(), UploadTests.jpeg(96, 64));
            Files.write(new File(dir, "thumb.jpg").toPath(), UploadTests.jpeg(48, 32));
            Files.write(new File(dir, "meta.json").toPath(), PhotoMath.metaJson(pid, name, added,
                    new Readability.Fields("dark", "light", "mist", .12, 0, false, false)));
        }
    }

    static final class Rig implements AutoCloseable {
        final Path r, tmpDir;
        final UploadTests.Clock clock = new UploadTests.Clock(NOW);
        final UserLibrary lib;
        final PendingPhotos pp;
        final Stub stub = new Stub();
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger wakes = new AtomicInteger();
        volatile boolean staticsBroken;
        final UploadServer srv;
        int port;

        Rig() throws Exception {
            this(3000, 3000);
        }

        Rig(int readTimeout, long headDeadline) throws Exception {
            Path base = UploadTests.tmp("cf-upload-server");
            r = base.resolve("R");
            UploadTests.copyTree(FIXTURE, r);
            Files.createDirectories(r.resolve("groups/gnojson03"));
            tmpDir = base.resolve("cache/upload");
            lib = new UserLibrary(r.toFile(), clock, new Random(3));
            pp = new PendingPhotos(lib, clock, new Random(5));
            srv = new UploadServer(lib, pp, stub, name -> {
                if (staticsBroken) throw new IllegalStateException("broken at home.cleanframe.tv.Secret");
                return Files.readAllBytes(PAGE.resolve(name));
            }, tmpDir.toFile(), clock, wakes::incrementAndGet, logs::add);
            srv.readTimeoutMs = readTimeout;
            srv.headDeadlineMs = headDeadline;
            srv.start(InetAddress.getLoopbackAddress(), 0);
            port = srv.address().getPort();
            LIVE.add(srv);
        }

        @Override
        public void close() {
            srv.stop();
            LIVE.remove(srv);
        }

        Resp get(String path) throws IOException {
            return send(port, ("GET " + path + " HTTP/1.1\r\nHost: tv\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }

        Resp request(String method, String path, String type, byte[] body) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            String h = method + " " + path + " HTTP/1.1\r\nHost: tv\r\n" + (type == null ? "" : "Content-Type: " + type + "\r\n")
                    + "Content-Length: " + body.length + "\r\n\r\n";
            b.write(h.getBytes(StandardCharsets.ISO_8859_1));
            b.write(body);
            return send(port, b.toByteArray());
        }

        Resp json(String method, String path, String body) throws IOException {
            return request(method, path, "application/json", body.getBytes(StandardCharsets.UTF_8));
        }

        Resp upload(Multipart m) throws IOException {
            return request("POST", "/api/" + TOKEN + "/photos", m.type(), m.body());
        }

        /** The upload through sendAll: every byte must be read by the server. */
        Resp uploadAll(Multipart m) throws IOException {
            byte[] body = m.body();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(("POST /api/" + TOKEN + "/photos HTTP/1.1\r\nHost: tv\r\nContent-Type: " + m.type() + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            b.write(body);
            return sendAll(port, b.toByteArray());
        }

        int tmpPhotos() throws IOException {
            return pp.count(null);
        }

        int parts() {
            String[] l = tmpDir.toFile().list();
            return l == null ? 0 : l.length;
        }
    }

    /** A head-deadline clock (UploadServer.headClock) the test steps by hand; counts the server's reads. */
    static final class StepClock implements LongSupplier {
        private long now;
        private int reads;

        @Override
        public synchronized long getAsLong() {
            reads++;
            notifyAll();
            return now;
        }

        synchronized void advanceMs(long ms) {
            now += ms * 1_000_000L;
        }

        synchronized long ms() {
            return now / 1_000_000L;
        }

        /** Wait (at most 20 s) until the server has read the clock n times. */
        synchronized void awaitReads(int n, String what) throws InterruptedException {
            long end = System.nanoTime() + 20_000_000_000L;
            while (reads < n) {
                long left = end - System.nanoTime();
                if (left <= 0) throw new UploadTests.Fail("not within 20 s: " + what + " (clock read " + reads + " times, want " + n + ")");
                wait(left / 1_000_000L + 1);
            }
        }
    }

    static final class Resp {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>();
        byte[] body = new byte[0];
        String raw;

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        Map<String, Object> json() {
            check(headers.getOrDefault("content-type", "").startsWith("application/json"), "not JSON: " + status + " " + text());
            return obj(text());
        }

        @Override
        public String toString() {
            return status + " " + text();
        }
    }

    static Resp parse(byte[] all) {
        Resp r = new Resp();
        String s = new String(all, StandardCharsets.ISO_8859_1);
        r.raw = s;
        int end = s.indexOf("\r\n\r\n");
        if (end < 0) {
            r.status = -1;
            return r;
        }
        String[] lines = s.substring(0, end).split("\r\n", -1);
        r.status = Integer.parseInt(lines[0].split(" ")[1]);
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            String n = lines[i].substring(0, c);
            r.names.add(n);
            r.headers.put(n.toLowerCase(), lines[i].substring(c + 1).trim());
        }
        r.body = Arrays.copyOfRange(all, end + 4, all.length);
        return r;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        try {
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        } catch (IOException e) {
            if (o.size() == 0) throw e; // a reset after the answer was read is fine
        }
        return o.toByteArray();
    }

    /**
     * Wait until no running Rig's server still holds the connection from this client port, so its
     * slot is free and its log line written. A slot is freed only after the server's lingering close
     * sees the client's EOF, so requests sent back to back found all MAX_CONNECTIONS slots still held
     * and got 503 busy under the gate's parallel load (2026-10-31: the Content-Length case, then the
     * token case). Every request helper calls this after reading its answer.
     */
    static void released(int clientPort) throws IOException {
        await(() -> {
            for (UploadServer srv : LIVE) if (srv.serving(clientPort)) return false;
            return true;
        }, "the server lets go of the connection from port " + clientPort + " after its answer");
    }

    /** Wait (at most 20 s, then fail naming what) for cond: an event, not a guess at a duration. */
    static void await(BooleanSupplier cond, String what) throws IOException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) throw new UploadTests.Fail("not within 20 s: " + what);
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new IOException("interrupted waiting for: " + what);
            }
        }
    }

    static Resp send(int port, byte[] req) throws IOException {
        Resp r;
        int local;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setSoTimeout(10_000);
            local = s.getLocalPort();
            OutputStream o = s.getOutputStream();
            try {
                o.write(req);
                o.flush();
                s.shutdownOutput();
            } catch (IOException e) {
                // refused early (413): the answer is still there to read
            }
            r = parse(readAll(s.getInputStream()));
        }
        released(local);
        return r;
    }

    /** Sends req in chunks of 1..17 bytes, flushing each (so the server sees every possible split). */
    /**
     * Sends all of req, failing if it cannot: with a small send buffer it blocks until the server reads,
     * so a server that answers and closes with the request unread resets this write, every time. Then the answer.
     */
    static Resp sendAll(int port, byte[] req) throws IOException {
        Resp r;
        int local;
        try (Socket s = new Socket()) {
            s.setSendBufferSize(32 * 1024);
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setSoTimeout(10_000);
            local = s.getLocalPort();
            OutputStream o = s.getOutputStream();
            try {
                o.write(req);
                o.flush();
                s.shutdownOutput();
            } catch (IOException e) {
                throw new UploadTests.Fail("the server closed with the request unread: " + e);
            }
            r = parse(readAll(s.getInputStream()));
        }
        released(local);
        return r;
    }

    static Resp sendTrickle(int port, byte[] req, int headLen) throws IOException {
        Resp r;
        int local;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(10_000);
            local = s.getLocalPort();
            OutputStream o = s.getOutputStream();
            o.write(req, 0, headLen);
            o.flush();
            int i = headLen, k = 0;
            while (i < req.length) {
                int n = Math.min(req.length - i, 1 + (k++ % 17));
                o.write(req, i, n);
                o.flush();
                i += n;
            }
            s.shutdownOutput();
            r = parse(readAll(s.getInputStream()));
        }
        released(local);
        return r;
    }

    /**
     * Sends head, then one byte every 20 ms (for at most 3 s) until the server answers; tookMs[0] is
     * how long the answer took from the first trickled byte. A client that never stops sending, so
     * only a deadline enforced inside the server's reads can end the request.
     */
    static Resp trickleUntilAnswer(int port, String head, long[] tookMs) throws Exception {
        Resp r;
        int local;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(4000);
            local = s.getLocalPort();
            OutputStream o = s.getOutputStream();
            o.write(head.getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            AtomicBoolean stop = new AtomicBoolean();
            long t0 = System.nanoTime();
            Thread w = new Thread(() -> {
                try {
                    while (!stop.get() && System.nanoTime() - t0 < 3_000_000_000L) {
                        o.write('a');
                        o.flush();
                        Thread.sleep(20);
                    }
                } catch (IOException | InterruptedException e) {
                    // the server closed the connection
                }
            });
            w.setDaemon(true);
            w.start();
            try {
                r = parse(readAll(s.getInputStream()));
                tookMs[0] = (System.nanoTime() - t0) / 1_000_000L;
            } finally {
                stop.set(true);
                w.join(5000);
            }
        }
        released(local);
        return r;
    }

    static final class Multipart {
        final String boundary;
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        boolean closed;

        Multipart() {
            this("----cfTestBoundary7MA4YWxkTrZu0gW");
        }

        Multipart(String boundary) {
            this.boundary = boundary;
        }

        Multipart raw(String s) {
            byte[] x = s.getBytes(StandardCharsets.UTF_8);
            b.write(x, 0, x.length);
            return this;
        }

        Multipart field(String name, String value) {
            return raw("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
        }

        Multipart file(String name, String filename, byte[] data) {
            raw("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
                    + "\"\r\nContent-Type: image/jpeg\r\n\r\n");
            b.write(data, 0, data.length);
            return raw("\r\n");
        }

        Multipart end() {
            closed = true;
            return raw("--" + boundary + "--\r\n");
        }

        String type() {
            return "multipart/form-data; boundary=" + boundary;
        }

        byte[] body() {
            return b.toByteArray();
        }
    }

    /** Data that looks like the delimiter in every way but one. */
    static byte[] nasty(String boundary, int size) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Random r = new Random(42);
        String d = "\r\n--" + boundary;
        while (o.size() < size) {
            int k = r.nextInt(d.length()); // a proper prefix of the delimiter, then a wrong byte
            byte[] p = d.substring(0, k).getBytes(StandardCharsets.ISO_8859_1);
            o.write(p, 0, p.length);
            o.write('X');
            for (int i = 0; i < r.nextInt(40); i++) o.write(r.nextInt(256));
        }
        return o.toByteArray();
    }

    static void error(Resp r, int status, String code, String message, String what) {
        eq(r.status, status, what + " status (" + r.text() + ")");
        Map<String, Object> o = r.json();
        eq(o.get("ok"), false, what + " ok");
        eq(o.get("error"), code, what + " error");
        eq(o.get("message"), message, what + " message");
    }

    static void page(Resp r, int status, String text, String what) {
        eq(r.status, status, what + " status");
        check(r.headers.getOrDefault("content-type", "").startsWith("text/html"), what + ": not HTML");
        check(r.text().contains(UploadServer.escapeHtml(text)), what + ": page lacks '" + text + "': " + r.text());
    }

    static void refusedClean(Rig g, int before, String what) throws IOException {
        eq(g.tmpPhotos(), before, what + ": no unfinished photo left");
        eq(g.parts(), 0, what + ": no upload temp file left");
    }

    static String api(String rest) {
        return "/api/" + TOKEN + "/" + rest;
    }

    /**
     * Nothing of this rig answers at a:port. Other cases' servers run in parallel on random loopback
     * ports and may get a just-freed one, so "refused" is not required: only that this rig's server
     * logged no request for it.
     */
    /**
     * True unless g's server answers on a:port. A refused connection is the usual answer; if something
     * accepts (another process may get the freed port), a probe with a method nothing else in the test
     * sends must not show up in g's log within 500 ms. Not "the log did not grow": the server logs a
     * request after its answer is read, so an earlier request's line can land late (it failed the gate
     * that way under parallel load, 2026-10-31).
     */
    static boolean notServedBy(Rig g, InetAddress a, int port) throws InterruptedException {
        if (refusesConnections(a, port)) return true;
        long before = traces(g);
        try {
            sendTo(a, port, "TRACE / HTTP/1.1\r\n\r\n");
        } catch (IOException e) {
            // closed on us: not served either
        }
        for (int i = 0; i < 50; i++) {
            if (traces(g) > before) return false;
            Thread.sleep(10);
        }
        return true;
    }

    /** Status and error code of each answer, for a failure message. */
    static String describe(List<Resp> got) {
        synchronized (got) {
            List<String> out = new ArrayList<>();
            for (Resp r : got) out.add(r == null ? "IOException" : r.status + " " + r.text().replaceAll("\\s+", " "));
            return out.toString();
        }
    }

    /** How many TRACE probes g's server has logged (an earlier probe's line stays in the log). */
    static long traces(Rig g) {
        synchronized (g.logs) {
            return g.logs.stream().filter(l -> l.startsWith("TRACE ")).count();
        }
    }

    /** send(), from the given local (client) address: 127.0.0.2 etc. are this host's too on Linux. */
    static Resp sendFrom(InetAddress local, int port, byte[] req) throws IOException {
        Resp r;
        int lport;
        try (Socket s = new Socket()) {
            s.bind(new InetSocketAddress(local, 0));
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setSoTimeout(10_000);
            lport = s.getLocalPort();
            s.getOutputStream().write(req);
            s.shutdownOutput();
            r = parse(readAll(s.getInputStream()));
        }
        released(lport);
        return r;
    }

    static Resp sendTo(InetAddress a, int port, String req) throws IOException {
        Resp r;
        int local;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(a, port), 2000);
            s.setSoTimeout(5000);
            local = s.getLocalPort();
            s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
            s.shutdownOutput();
            r = parse(readAll(s.getInputStream()));
        }
        released(local);
        return r;
    }

    static boolean refusesConnections(InetAddress a, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(a, port), 1000);
            return false;
        } catch (ConnectException e) {
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    public static void main(String[] a) {
        run("server: binds the given address only; start again is a no-op; stop closes", () -> {
            try (Rig g = new Rig()) {
                InetAddress lo = InetAddress.getLoopbackAddress();
                eq(g.srv.address().getAddress(), lo, "bound to the address given, not the wildcard");
                eq(g.get("/").status, 200, "serving");
                g.srv.start(lo, g.port);
                eq(g.srv.address().getPort(), g.port, "same address: kept");
                g.srv.start(lo, 0);
                eq(g.srv.address().getPort(), g.port, "port 0 on the same address: kept");
                eq(g.get("/").status, 200, "still serving");
                check(!notServedBy(g, lo, g.port), "the probe sees an address that serves");
                InetAddress other = InetAddress.getByName("127.0.0.2");
                g.srv.start(other, 0);
                eq(g.srv.address().getAddress(), other, "moved to the new address");
                check(notServedBy(g, lo, g.port), "old address no longer listens");
                int moved = g.srv.address().getPort();
                eq(sendTo(other, moved, "GET / HTTP/1.1\r\n\r\n").status, 200, "serving on the new address");
                g.srv.stop();
                check(!g.srv.running() && g.srv.address() == null, "stopped");
                check(notServedBy(g, other, moved), "nothing listens after stop");
                g.srv.stop();
                boolean thrown = false;
                try {
                    g.srv.start(InetAddress.getByName("192.0.2.1"), 0); // not this host's
                } catch (IOException e) {
                    thrown = true;
                }
                check(thrown && !g.srv.running(), "an address that is not ours fails and leaves it stopped");
                g.srv.start(lo, 0);
                g.port = g.srv.address().getPort();
                eq(g.get("/").status, 200, "restarted");
            }
        });
        run("server: stop drops open connections at once", () -> {
            try (Rig g = new Rig(); Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                s.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
                await(() -> g.srv.openConnections() == 1, "the server accepts the connection");
                long t0 = System.nanoTime();
                g.srv.stop();
                s.setSoTimeout(2000);
                int c;
                try {
                    c = s.getInputStream().read();
                } catch (IOException e) {
                    c = -1;
                }
                eq(c, -1, "closed");
                check(System.nanoTime() - t0 < 1_500_000_000L, "promptly");
            }
        });
        run("server: the token gates the page and the API", () -> {
            try (Rig g = new Rig()) {
                Resp ok = g.get("/u/" + TOKEN);
                eq(ok.status, 200, "page");
                check(Arrays.equals(ok.body, Files.readAllBytes(PAGE.resolve("index.html"))), "the phone page from assets/upload/");
                eq(ok.headers.get("content-type"), "text/html; charset=utf-8", "type");
                for (String bad : new String[]{"0123456789abcdef0123456789abcdee", TOKEN.toUpperCase(), TOKEN.substring(1), TOKEN + "0", "", "%30" + TOKEN.substring(1)}) {
                    page(g.get("/u/" + bad), 403, UploadServer.EXPIRED, "/u/" + bad);
                    error(g.get("/api/" + bad + "/state"), 403, "expired", UploadServer.EXPIRED, "api " + bad);
                }
                eq(g.get(api("state")).status, 200, "state with the token");
                g.clock.t = EXPIRES - 1;
                eq(g.get(api("state")).status, 200, "1 ms before expiry");
                g.clock.t = EXPIRES;
                page(g.get("/u/" + TOKEN), 403, UploadServer.EXPIRED, "expired page");
                error(g.get(api("state")), 403, "expired", UploadServer.EXPIRED, "expired api");
                g.clock.t = NOW;
                Map<String, Object> renewed = obj(g.lib.uploadStart("gholiday1", "http://127.0.0.1:8097"));
                eq(renewed.get("url"), "http://127.0.0.1:8097/u/" + TOKEN, "renewal keeps the token");
                g.clock.t = EXPIRES + 1;
                eq(g.get(api("state")).status, 200, "renewed: valid past the old expiry");
                g.lib.uploadStop();
                error(g.get(api("state")), 403, "expired", UploadServer.EXPIRED, "after uploadStop");
            }
        });
        run("server: the code page redirects to the token and refuses more than 10 wrong codes a minute", () -> {
            try (Rig g = new Rig()) {
                Resp p = g.get("/");
                eq(p.status, 200, "code page");
                check(p.text().contains("name=\"code\"") && p.text().contains("action=\"/\""), "a code form");
                eq(g.get("/?code=482913").status, 200, "query ignored");
                String form = "application/x-www-form-urlencoded";
                Resp go = g.request("POST", "/", form, "code=482913".getBytes());
                eq(go.status, 303, "right code");
                eq(go.headers.get("location"), "/u/" + TOKEN, "redirect");
                eq(go.body.length, 0, "empty body");
                eq(g.request("POST", "/", form, "x=1&code=+482913+".getBytes()).headers.get("location"), "/u/" + TOKEN, "spaces stripped");
                eq(g.request("POST", "/", form, "code=%34%38%32%39%31%33".getBytes()).status, 303, "percent-encoded");
                for (int i = 0; i < 9; i++) page(g.request("POST", "/", form, ("code=" + (100000 + i)).getBytes()), 403, UploadServer.WRONG_CODE, "wrong " + i);
                page(g.request("POST", "/", form, "code=48291".getBytes()), 403, UploadServer.WRONG_CODE, "5 digits (10th wrong)");
                page(g.request("POST", "/", form, "code=482913".getBytes()), 429, UploadServer.SLOW_DOWN, "11th try, even right");
                check(g.request("POST", "/", form, "code=482913".getBytes()).text().contains("name=\"code\""), "the form is shown again");
                g.clock.t += 60_001;
                eq(g.request("POST", "/", form, "code=482913".getBytes()).status, 303, "a minute later");
                g.lib.uploadStop();
                page(g.request("POST", "/", form, "code=482913".getBytes()), 403, UploadServer.NO_CODE, "no token");
                Resp nolen = send(g.port, "POST / HTTP/1.1\r\nHost: tv\r\n\r\ncode=482913".getBytes());
                page(nolen, 411, UploadServer.NO_LENGTH, "no Content-Length");
                page(g.request("POST", "/", form, new byte[4097]), 413, "The request is too big.", "body over 4 KB");
            }
        });
        run("server: the wrong-code limit is per client address, under a higher ceiling for all", () -> {
            // review 999ba07 (L2): one limit for everyone let any device on the LAN keep every phone at
            // "Too many wrong codes".
            try (Rig g = new Rig()) {
                InetAddress noisy = InetAddress.getByName("127.0.0.1"), phone = InetAddress.getByName("127.0.0.2");
                java.util.function.Function<String, byte[]> post = body -> ("POST / HTTP/1.1\r\nHost: tv\r\n"
                        + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                        .getBytes(StandardCharsets.ISO_8859_1);
                for (int i = 0; i < UploadServer.CODE_FAILS_PER_MIN; i++) {
                    page(sendFrom(noisy, g.port, post.apply("code=" + (100000 + i))), 403, UploadServer.WRONG_CODE, "noisy wrong " + i);
                }
                page(sendFrom(noisy, g.port, post.apply("code=" + CODE)), 429, UploadServer.SLOW_DOWN, "the noisy client is slowed down");
                eq(sendFrom(phone, g.port, post.apply("code=" + CODE)).status, 303, "another phone still gets in");
                for (int i = 0; i < UploadServer.CODE_FAILS_PER_MIN; i++) {
                    page(sendFrom(phone, g.port, post.apply("code=" + (200000 + i))), 403, UploadServer.WRONG_CODE, "phone wrong " + i);
                }
                page(sendFrom(phone, g.port, post.apply("code=" + CODE)), 429, UploadServer.SLOW_DOWN, "and has its own limit");
                eq(g.srv.codeClients(), 2, "two addresses held");
                // the ceiling for all: many addresses together (checked directly; 127/8 is all this host's)
                int all = 2 * UploadServer.CODE_FAILS_PER_MIN, n = 3;
                while (all < UploadServer.CODE_FAILS_ALL_PER_MIN) {
                    InetAddress addr = InetAddress.getByName("127.0.0." + n++);
                    for (int i = 0; i < UploadServer.CODE_FAILS_PER_MIN && all < UploadServer.CODE_FAILS_ALL_PER_MIN; i++, all++) {
                        try {
                            g.srv.checkCode("000000", addr);
                            throw new UploadTests.Fail("a wrong code was accepted");
                        } catch (UploadServer.Refused e) {
                            eq(e.status, 403, "wrong code " + all + " from " + addr);
                        }
                    }
                }
                check(UploadServer.CODE_FAILS_ALL_PER_MIN >= 5 * UploadServer.CODE_FAILS_PER_MIN, "the ceiling is well above one client's limit");
                // review 7c2b1e5: past the ceiling a wrong code from an address with no record was answered
                // 429 without being recorded, so a fresh address guessed without limit (303 vs 429 tells
                // right from wrong). Every compared wrong code counts against its address, past the ceiling too.
                InetAddress fresh = InetAddress.getByName("127.0.0.200");
                for (int i = 0; i < UploadServer.CODE_FAILS_PER_MIN; i++) {
                    int status = 0;
                    try {
                        g.srv.checkCode(String.format(Locale.ROOT, "%06d", 300000 + i), fresh);
                    } catch (UploadServer.Refused e) {
                        status = e.status;
                    }
                    eq(status, 429, "wrong code " + i + " from a fresh address past the ceiling for all");
                }
                eq(g.srv.codeClients(), 7, "the fresh address is recorded past the ceiling");
                int eleventh = 0;
                try {
                    g.srv.checkCode(CODE, fresh);
                } catch (UploadServer.Refused e) {
                    eleventh = e.status;
                }
                eq(eleventh, 429, "the fresh address is refused at its 11th guess, even right");
                // review 30e7356 (A3): the ceiling answered 429 before the code was compared, so a phone
                // typing the right code was kept out by other addresses' guesses
                eq(g.srv.checkCode(CODE, InetAddress.getByName("127.0.0.201")).get("token"), TOKEN,
                        "the right code from a fresh address past the ceiling for all gets in");
                eq(sendFrom(phone, g.port, post.apply("code=" + CODE)).status, 429, "an address over its own limit is still refused, even right");
                check(g.srv.codeClients() <= UploadServer.CODE_CLIENTS_MAX, "bounded: " + g.srv.codeClients());
                g.clock.t += 60_001;
                eq(sendFrom(noisy, g.port, post.apply("code=" + CODE)).status, 303, "a minute later");
                eq(g.srv.codeClients(), 0, "and every address is forgotten");
            }
        });
        run("server: the code limit holds at most CODE_CLIENTS_MAX addresses; while full a new one is refused", () -> {
            try (Rig g = new Rig()) {
                check(UploadServer.CODE_CLIENTS_MAX >= 64 && UploadServer.CODE_CLIENTS_MAX <= 1024, "a few hundred addresses");
                for (int i = 0; i < UploadServer.CODE_CLIENTS_MAX; i++) {
                    InetAddress addr = InetAddress.getByAddress(new byte[]{10, 9, (byte) (i >> 8), (byte) i});
                    try {
                        g.srv.checkCode("000000", addr);
                        throw new UploadTests.Fail("a wrong code was accepted");
                    } catch (UploadServer.Refused e) {
                        check(e.status == 403 || e.status == 429, "wrong code from " + addr + ": " + e.status);
                    }
                }
                eq(g.srv.codeClients(), UploadServer.CODE_CLIENTS_MAX, "the table is full");
                InetAddress late = InetAddress.getByName("10.8.0.1");
                for (String c : new String[]{"000000", CODE}) {
                    int status = 0;
                    try {
                        g.srv.checkCode(c, late);
                    } catch (UploadServer.Refused e) {
                        status = e.status;
                    }
                    eq(status, 429, "an address with no record is refused while the table is full (" + c + ")");
                }
                eq(g.srv.codeClients(), UploadServer.CODE_CLIENTS_MAX, "and is not added");
                g.clock.t += 60_001;
                eq(g.srv.checkCode(CODE, late).get("token"), TOKEN, "a minute later it gets in");
                eq(g.srv.codeClients(), 0, "every address forgotten");
            }
        });
        run("server: wrong codes past the ceiling for all count towards a new code too", () -> {
            try (Rig g = new Rig()) {
                // fill the ceiling, then renew: the code changes and the count starts again
                for (int i = 0; i < UploadServer.CODE_FAILS_ALL_PER_MIN; i++) {
                    try {
                        g.srv.checkCode("000000", InetAddress.getByAddress(new byte[]{10, 7, 0, (byte) (i / UploadServer.CODE_FAILS_PER_MIN)}));
                    } catch (UploadServer.Refused e) {
                        eq(e.status, 403, "below the ceiling");
                    }
                }
                String code1 = (String) obj(g.lib.uploadStart("gholiday1", "http://127.0.0.1:8097")).get("code");
                check(!CODE.equals(code1), "renewed with a new code");
                for (int i = 0; i < UserLibrary.CODE_ROTATE_AFTER; i++) {
                    try {
                        g.srv.checkCode("000000".equals(code1) ? "000001" : "000000", InetAddress.getByAddress(new byte[]{10, 6, 0, (byte) i}));
                        throw new UploadTests.Fail("a wrong code was accepted");
                    } catch (UploadServer.Refused e) {
                        eq(e.status, 429, "past the ceiling");
                    }
                }
                String code2 = (String) obj(g.lib.uploadStart("gholiday1", "http://127.0.0.1:8097")).get("code");
                check(!code1.equals(code2), "guesses past the ceiling counted: a new code again");
            }
        });
        run("server: wrong codes give the Add photo screen a new code at its next renewal (A4)", () -> {
            try (Rig g = new Rig()) {
                InetAddress[] from = new InetAddress[UserLibrary.CODE_ROTATE_AFTER];
                for (int i = 0; i < from.length; i++) from[i] = InetAddress.getByName("127.0.1." + (i + 1)); // under each address's limit
                for (InetAddress f : from) {
                    try {
                        g.srv.checkCode("000000", f);
                        throw new UploadTests.Fail("a wrong code was accepted");
                    } catch (UploadServer.Refused e) {
                        eq(e.status, 403, "wrong");
                    }
                }
                Map<String, Object> renewed = obj(g.lib.uploadStart("gholiday1", "http://127.0.0.1:8097"));
                check(!CODE.equals(renewed.get("code")), "renewed with a new code after " + from.length + " wrong ones");
                eq(renewed.get("url"), "http://127.0.0.1:8097/u/" + TOKEN, "same token");
                String form = "application/x-www-form-urlencoded";
                page(g.request("POST", "/", form, ("code=" + CODE).getBytes()), 403, UploadServer.WRONG_CODE, "the old code no longer works");
                eq(g.request("POST", "/", form, ("code=" + renewed.get("code")).getBytes()).status, 303, "the new one does");
            }
        });
        run("server: static files, and the security headers on every answer", () -> {
            try (Rig g = new Rig()) {
                Resp js = g.get("/static/app.js"), css = g.get("/static/style.css"), crop = g.get("/static/crop.js");
                eq(js.status + " " + js.headers.get("content-type"), "200 text/javascript; charset=utf-8", "app.js");
                check(Arrays.equals(js.body, Files.readAllBytes(PAGE.resolve("app.js"))), "app.js bytes");
                eq(crop.status + " " + crop.headers.get("content-type"), "200 text/javascript; charset=utf-8", "crop.js");
                check(Arrays.equals(crop.body, Files.readAllBytes(Paths.get("web/photo-crop.js"))), "crop.js is web/photo-crop.js, byte for byte");
                eq(css.status + " " + css.headers.get("content-type"), "200 text/css; charset=utf-8", "style.css");
                for (String p : new String[]{"/static/index.html", "/static/../app.js", "/static/", "/static/app.js/", "/static/APP.JS", "/nothing"}) {
                    page(g.get(p), 404, "Not found.", p);
                }
                error(g.get(api("nothing")), 404, "not-found", "Not found.", "api 404");
                List<Resp> all = Arrays.asList(js, g.get("/"), g.get("/u/" + TOKEN), g.get("/u/x"), g.get(api("state")), g.get(api("x")),
                        g.request("POST", "/", "application/x-www-form-urlencoded", "code=482913".getBytes()),
                        send(g.port, ("POST " + api("photos") + " HTTP/1.1\r\nContent-Length: 99999999999\r\n\r\n").getBytes()),
                        g.get(api("thumbs/gholiday1/pno00000000.jpg")));
                for (Resp r : all) {
                    eq(r.headers.get("content-security-policy"), UploadServer.CSP, r.status + " CSP");
                    check(r.headers.get("content-security-policy").contains("default-src 'none'"), "CSP default none");
                    eq(r.headers.get("referrer-policy"), "no-referrer", r.status + " referrer");
                    eq(r.headers.get("cache-control"), "no-store", r.status + " cache");
                    eq(r.headers.get("x-content-type-options"), "nosniff", r.status + " nosniff");
                    eq(r.headers.get("x-frame-options"), "DENY", r.status + " frames");
                    eq(r.headers.get("connection"), "close", r.status + " no keep-alive");
                    eq(r.headers.get("content-length"), Integer.toString(r.body.length), r.status + " length");
                    check(!r.headers.containsKey("server") && !r.headers.containsKey("set-cookie"), "no Server or cookie");
                }
            }
        });
        run("server: state lists groups with counts, photos with thumbs, unfinished photos and the limits", () -> {
            try (Rig g = new Rig()) {
                Map<String, Object> s = g.get(api("state")).json();
                eq(s.get("ok"), true, "ok");
                eq(s.get("group"), "gholiday1", "token group");
                eq(s.get("expires"), EXPIRES, "expires");
                eq(s.get("now"), NOW, "now");
                StringBuilder gs = new StringBuilder();
                for (Object o : (List<?>) s.get("groups")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    gs.append(m.get("id")).append(':').append(m.get("name")).append(':').append(m.get("builtin")).append(':').append(m.get("count")).append(' ');
                }
                eq(gs.toString().trim(), "scenery:Scenery:true:0 space:Space:true:1 cyberpunk:Cyberpunk:true:0 gholiday1:Holiday:false:2 gfamily02:Family:false:0", "groups");
                List<?> photos = (List<?>) s.get("photos");
                eq(photos.size(), 3, "photos");
                Map<?, ?> p0 = (Map<?, ?>) photos.get(0);
                eq(p0.get("group") + "/" + p0.get("id"), "gholiday1/pbeach00001", "first photo");
                eq(p0.get("thumb"), api("thumbs/gholiday1/pbeach00001.jpg"), "thumb url");
                eq(new java.util.TreeSet<>(((Map<?, ?>) p0).keySet()).toString(), "[added, group, id, name, thumb]", "photo keys");
                List<?> pend = (List<?>) s.get("pending");
                eq(pend.size(), 1, "the fixture's unfinished photo");
                eq(((Map<?, ?>) pend.get(0)).get("id"), "pinflight04", "pending id");
                eq(s.get("limits").toString(), "{groups=20, perGroup=60, total=300, bytes=41943040, megapixels=60, name=24}", "limits");
                check(!s.containsKey("restore"), "no restore in v2");
            }
        });
        run("server: the crop field reaches the processor; none is the centre; a bad one is refused", () -> {
            try (Rig g = new Rig()) {
                eq(g.upload(new Multipart().field("group", "gfamily02").field("crop", "0.25,0.75,1.5").file("photo", "a.jpg", new byte[]{1}).end()).status,
                        200, "with a crop");
                eq(g.stub.lastCrop.x + "," + g.stub.lastCrop.y + "," + g.stub.lastCrop.z, "0.25,0.75,1.5", "the crop as sent");
                eq(g.upload(new Multipart().file("photo", "b.jpg", new byte[]{1}).field("crop", "0.5,0.5,1").end()).status, 200, "crop after the photo part");
                eq(g.stub.lastCrop.z, 1.0, "still read");
                eq(g.upload(new Multipart().field("group", "gfamily02").file("photo", "c.jpg", new byte[]{1}).end()).status, 200, "no crop");
                check(g.stub.lastCrop == null, "none: the processor centres it");
                int before = g.stub.calls.get(), pending = g.pp.count(null);
                for (String bad : new String[]{"1,2", "x,y,z", "0.5,0.5,0", "0.5,0.5,1e2", ""}) {
                    error(g.upload(new Multipart().field("group", "gfamily02").field("crop", bad).file("photo", "d.jpg", new byte[]{1}).end()),
                            400, "bad-crop", UploadServer.BAD_CROP, "crop " + bad);
                }
                eq(g.stub.calls.get(), before, "nothing processed");
                eq(g.pp.count(null), pending, "no unfinished photo left by a refusal");
            }
        });
        run("server: an upload is prepared, queued, and listed once the page saves its materials", () -> {
            try (Rig g = new Rig()) {
                byte[] data = nasty("----cfTestBoundary7MA4YWxkTrZu0gW", 300_000);
                Resp r = g.upload(new Multipart().field("group", "gfamily02").file("photo", "My  Beach.JPG", data).end());
                eq(r.status, 200, "upload " + r.text());
                Map<?, ?> p = (Map<?, ?>) r.json().get("photo");
                String pid = (String) p.get("id");
                check(UserLibrary.validPid(pid), "pid " + pid);
                eq(p.get("group") + "|" + p.get("name") + "|" + p.get("added") + "|" + p.get("pending"), "gfamily02|My Beach|" + NOW + "|true", "record");
                eq(g.stub.calls.get(), 1, "processed once");
                check(Arrays.equals(g.stub.last, data), "the processor got the exact bytes");
                eq(g.stub.lastName, "My Beach", "display name");
                eq(g.wakes.get(), 1, "the page was woken");
                eq(g.parts(), 0, "upload temp file removed");
                check(g.pp.materialJobs().contains("\"pid\":\"" + pid + "\""), "a material job");
                Map<String, Object> s = g.get(api("state")).json();
                check(s.toString().contains(pid), "state lists it as pending");
                for (int k = 0; k < PendingPhotos.KINDS.length; k++) {
                    String ans = g.pp.saveMaterial("gfamily02", pid, PendingPhotos.KINDS[k],
                            UploadTests.dataUrl(UploadTests.jpeg(PendingPhotos.SIZES[k][0], PendingPhotos.SIZES[k][1])));
                    eq(ans, "{\"ok\":true,\"done\":" + (k == PendingPhotos.KINDS.length - 1) + "}", PendingPhotos.KINDS[k]);
                }
                Map<String, Object> s2 = g.get(api("state")).json();
                boolean listed = false;
                for (Object o : (List<?>) s2.get("photos")) listed |= pid.equals(((Map<?, ?>) o).get("id"));
                check(listed, "on the TV");
                Resp t = g.get(api("thumbs/gfamily02/" + pid + ".jpg"));
                eq(t.status + " " + t.headers.get("content-type"), "200 image/jpeg", "thumb");
                check(Arrays.equals(t.body, Files.readAllBytes(g.r.resolve("groups/gfamily02/photos/" + pid + "/thumb.jpg"))), "thumb from R");
                Resp d = g.upload(new Multipart().file("photo", "x.png", new byte[]{1, 2, 3}).end());
                eq(((Map<?, ?>) d.json().get("photo")).get("group"), "gholiday1", "no group field: the token's group");
                Resp b = g.upload(new Multipart().field("group", "scenery").file("photo", "x.jpg", new byte[]{9}).end());
                eq(((Map<?, ?>) b.json().get("photo")).get("group"), "scenery", "a built-in group");
            }
        });
        run("server: multipart edge cases", () -> {
            try (Rig g = new Rig()) {
                int before = g.tmpPhotos();
                String bd = "----cfTestBoundary7MA4YWxkTrZu0gW";
                // every split: the request trickled in 1..17 byte writes, data full of delimiter look-alikes
                byte[] data = nasty(bd, 20_000);
                Multipart m = new Multipart().field("group", "gfamily02").file("photo", "a.jpg", data).end();
                byte[] body = m.body();
                String head = "POST " + api("photos") + " HTTP/1.1\r\nContent-Type: " + m.type() + "\r\nContent-Length: " + body.length + "\r\n\r\n";
                ByteArrayOutputStream req = new ByteArrayOutputStream();
                req.write(head.getBytes(StandardCharsets.ISO_8859_1));
                req.write(body);
                Resp t = sendTrickle(g.port, req.toByteArray(), 0);
                eq(t.status, 200, "trickled " + t.text());
                check(Arrays.equals(g.stub.last, data), "trickled: exact bytes");
                before = g.tmpPhotos();
                // quoted boundary with a space, a preamble and an epilogue
                Multipart q = new Multipart("abc def").raw("This is a preamble.\r\n").file("photo", "q.jpg", "Q\r\n--abc de".getBytes()).end().raw("epilogue");
                Resp qr = g.request("POST", api("photos"), "multipart/form-data; charset=utf-8; boundary=\"abc def\"", q.body());
                eq(qr.status, 200, "quoted boundary " + qr.text());
                eq(new String(g.stub.last, StandardCharsets.ISO_8859_1), "Q\r\n--abc de", "quoted: bytes");
                // first photo part wins; a part that is not form-data, or has no name, is ignored
                Multipart dup = new Multipart().raw("--" + bd + "\r\nContent-Disposition: attachment; name=\"photo\"\r\n\r\nNOPE\r\n")
                        .raw("--" + bd + "\r\nContent-Type: text/plain\r\n\r\nNONAME\r\n")
                        .file("photo", "1.jpg", "FIRST".getBytes()).file("photo", "2.jpg", "SECOND".getBytes()).end();
                eq(g.upload(dup).status, 200, "duplicate");
                eq(new String(g.stub.last), "FIRST", "first photo part wins");
                int calls = g.stub.calls.get();
                before = g.tmpPhotos();
                error(g.upload(new Multipart().field("group", "gfamily02").end()), 400, "no-photo", UploadServer.NO_PHOTO, "no photo part");
                error(g.upload(new Multipart().file("photo", "e.jpg", new byte[0]).end()), 400, "no-photo", UploadServer.NO_PHOTO, "empty photo");
                Multipart ignored = new Multipart().raw("--" + bd + "\r\nContent-Disposition: attachment; name=\"photo\"\r\n\r\nX\r\n").end();
                error(g.upload(ignored), 400, "no-photo", UploadServer.NO_PHOTO, "only a non-form-data part");
                error(g.upload(new Multipart().file("photo", "c.jpg", "CUT".getBytes())), 400, "bad-request", UploadServer.CUT_OFF, "no close delimiter");
                byte[] cut = new Multipart().file("photo", "c.jpg", new byte[5000]).end().body();
                ByteArrayOutputStream half = new ByteArrayOutputStream();
                half.write(("POST " + api("photos") + " HTTP/1.1\r\nContent-Type: " + new Multipart().type() + "\r\nContent-Length: " + cut.length
                        + "\r\n\r\n").getBytes());
                half.write(cut, 0, 1000);
                error(send(g.port, half.toByteArray()), 400, "bad-request", UploadServer.CUT_OFF, "body shorter than Content-Length");
                error(g.request("POST", api("photos"), "application/json", "{}".getBytes()), 400, "bad-request", UploadServer.NOT_MULTIPART, "json");
                error(g.request("POST", api("photos"), "multipart/form-data", new Multipart().file("photo", "a", new byte[]{1}).end().body()), 400,
                        "bad-request", UploadServer.NOT_MULTIPART, "no boundary");
                String b71 = "b".repeat(71);
                error(g.request("POST", api("photos"), "multipart/form-data; boundary=" + b71, new Multipart(b71).file("photo", "a", new byte[]{1}).end().body()),
                        400, "bad-request", UploadServer.NOT_MULTIPART, "71-character boundary");
                error(g.request("POST", api("photos"), "multipart/form-data; boundary=" + bd, "no boundary anywhere".getBytes()), 400, "bad-request",
                        UploadServer.NOT_MULTIPART, "body without a delimiter");
                Multipart many = new Multipart();
                for (int i = 0; i < 17; i++) many.field("f" + i, "v");
                error(g.upload(many.file("photo", "a", new byte[]{1}).end()), 400, "bad-request", UploadServer.MALFORMED, "17 parts");
                error(g.upload(new Multipart().field("group", "g".repeat(1025)).file("photo", "a", new byte[]{1}).end()), 400, "bad-request",
                        UploadServer.MALFORMED, "a 1 KB group field");
                error(g.upload(new Multipart().raw("--" + bd + "\r\nX-Pad: " + "p".repeat(8200) + "\r\n\r\nx\r\n").end()), 400, "bad-request",
                        UploadServer.MALFORMED, "8 KB of part headers");
                error(g.upload(new Multipart().raw("--" + bd + "junk\r\n\r\nx\r\n").end()), 400, "bad-request", UploadServer.MALFORMED,
                        "junk after a delimiter");
                error(g.upload(new Multipart().raw("--" + bd + "\r\nno colon here\r\n\r\nx\r\n").end()), 400, "bad-request", UploadServer.MALFORMED,
                        "a part header without a colon");
                eq(g.stub.calls.get(), calls, "the processor never ran for a refused upload");
                refusedClean(g, before, "multipart refusals");
            }
        });
        run("server: Content-Length is checked before the body; the photo is capped at 40 MB", () -> {
            try (Rig g = new Rig()) {
                int before = g.tmpPhotos();
                long limit = UploadServer.MAX_UPLOAD + UploadServer.MULTIPART_SLACK;
                long t0 = System.nanoTime();
                Resp big = send(g.port, ("POST " + api("photos") + " HTTP/1.1\r\nContent-Type: " + new Multipart().type() + "\r\nContent-Length: "
                        + (limit + 1) + "\r\n\r\n").getBytes());
                error(big, 413, "too-big", UploadServer.TOO_BIG, "over the limit: no body sent, answered at once");
                check(System.nanoTime() - t0 < 2_000_000_000L, "without waiting for a body");
                error(send(g.port, ("POST " + api("photos") + " HTTP/1.1\r\n\r\n").getBytes()), 411, "length", UploadServer.NO_LENGTH, "no length");
                error(send(g.port, ("POST " + api("photos") + " HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").getBytes()), 411, "length",
                        UploadServer.NO_LENGTH, "chunked");
                error(send(g.port, ("POST " + api("photos") + " HTTP/1.1\r\nContent-Length: -1\r\n\r\n").getBytes()), 400, "bad-request",
                        "Bad Content-Length.", "negative");
                error(send(g.port, ("POST " + api("photos") + " HTTP/1.1\r\nContent-Length: 12abc\r\n\r\n").getBytes()), 411, "length",
                        UploadServer.NO_LENGTH, "not a number");
                byte[] max = new byte[(int) UploadServer.MAX_UPLOAD];
                Resp atMax = g.upload(new Multipart().file("photo", "max.jpg", max).end());
                eq(atMax.status, 200, "exactly 40 MB " + atMax.text());
                eq(g.stub.lastLength, UploadServer.MAX_UPLOAD, "all of it");
                before = g.tmpPhotos();
                byte[] over = new byte[(int) UploadServer.MAX_UPLOAD + 1];
                error(g.upload(new Multipart().file("photo", "over.jpg", over).end()), 413, "too-big", UploadServer.TOO_BIG, "40 MB + 1 within the slack");
                refusedClean(g, before, "size refusals");
                eq(g.stub.calls.get(), 1, "only the 40 MB one was processed");
            }
        });
        run("server: processor refusals map to v1's statuses and leave nothing behind", () -> {
            try (Rig g = new Rig()) {
                int before = g.tmpPhotos();
                String[][] cases = {{"type", "415", PhotoProcessor.TYPE}, {"pixels", "413", PhotoProcessor.PIXELS},
                        {"small", "422", PhotoProcessor.SMALL}, {"broken", "422", PhotoProcessor.BROKEN}};
                for (String[] c : cases) {
                    g.stub.refuse = c[0];
                    error(g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end()), Integer.parseInt(c[1]), c[0], c[2], c[0]);
                }
                g.stub.refuse = null;
                g.stub.boom = new IllegalStateException("decoder exploded at home.cleanframe.tv.X");
                Resp b = g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end());
                error(b, 500, "prepare", UploadServer.PREPARE, "processor failure");
                check(!b.text().contains("exploded") && !b.text().contains("home.cleanframe"), "no exception text to the phone");
                g.stub.boom = null;
                g.stub.writeNothing = true;
                error(g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end()), 500, "prepare", UploadServer.PREPARE, "processor wrote nothing");
                refusedClean(g, before, "processor refusals");
                eq(g.wakes.get(), 0, "nothing queued, nobody woken");
            }
        });
        run("server: a full disk while the photo is saved answers 507 with a message and leaves nothing", () -> {
            // review 006cfe9: an IOException writing the temp file escaped upload() and the connection
            // closed with no answer; the phone page shows the message of any refusal (app.js).
            try (Rig g = new Rig()) {
                int before = g.tmpPhotos();
                g.srv.partFiles = f -> new java.io.FileOutputStream(f) {
                    long n;

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        if ((n += len) > 50_000) throw new IOException("No space left on device");
                        super.write(b, off, len);
                    }
                };
                Resp r = g.upload(new Multipart().field("group", "gfamily02").file("photo", "big.jpg", nasty("----cfTestBoundary7MA4YWxkTrZu0gW", 300_000)).end());
                error(r, 507, "storage-full", UploadServer.STORAGE_FULL, "disk full");
                check(r.raw.startsWith("HTTP/1.1 507 Insufficient Storage\r\n"), "status line: " + r.raw.split("\r\n")[0]);
                check(UploadServer.STORAGE_FULL.startsWith("TV storage is full."), "says the TV's storage is full");
                eq(g.stub.calls.get(), 0, "nothing processed");
                refusedClean(g, before, "disk full");
                g.srv.partFiles = f -> {
                    throw new IOException("No space left on device");
                };
                error(g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end()), 507, "storage-full", UploadServer.STORAGE_FULL, "cannot open");
                refusedClean(g, before, "cannot open");
                g.srv.partFiles = java.io.FileOutputStream::new;
                eq(g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end()).status, 200, "space again: uploads work");
            }
        });
        run("server: a full disk before the photo is saved answers 507 too, and the body is read so the phone sees it", () -> {
            byte[] big = nasty("----cfTestBoundary7MA4YWxkTrZu0gW", 3_000_000); // far past what lingering reads
            try (Rig g = new Rig()) { // the temp folder cannot be made
                Files.createDirectories(g.tmpDir.getParent());
                Files.write(g.tmpDir, new byte[]{1});
                error(g.uploadAll(new Multipart().field("group", "gfamily02").file("photo", "big.jpg", big).end()), 507, "storage-full", UploadServer.STORAGE_FULL, "no temp folder");
                eq(g.stub.calls.get(), 0, "nothing processed");
                Files.delete(g.tmpDir);
                eq(g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end()).status, 200, "room again: uploads work");
            }
            try (Rig g = new Rig()) { // the temp file cannot be made
                g.srv.partFiles = new UploadServer.PartFiles() {
                    @Override
                    public OutputStream open(File f) throws IOException {
                        return new java.io.FileOutputStream(f);
                    }

                    @Override
                    public File create(File dir) throws IOException {
                        throw new IOException("No space left on device");
                    }
                };
                error(g.uploadAll(new Multipart().field("group", "gfamily02").file("photo", "big.jpg", big).end()), 507, "storage-full", UploadServer.STORAGE_FULL, "no temp file");
                eq(g.parts(), 0, "no part left");
            }
            try (Rig g = new Rig()) { // the photo's folder cannot be made
                Path photos = g.lib.photosDir("gfamily02");
                if (Files.exists(photos)) {
                    try (java.util.stream.Stream<Path> s = Files.walk(photos)) {
                        for (Path p : (Iterable<Path>) s.sorted(Collections.reverseOrder())::iterator) Files.delete(p);
                    }
                }
                Files.write(photos, new byte[]{1});
                int before = g.tmpPhotos();
                error(g.upload(new Multipart().field("group", "gfamily02").file("photo", "a.jpg", new byte[]{1}).end()), 507, "storage-full", UploadServer.STORAGE_FULL, "no photo folder");
                eq(g.stub.calls.get(), 0, "nothing processed");
                eq(g.parts(), 0, "no part left");
                eq(g.tmpPhotos(), before, "nothing unfinished left");
            }
        });
        run("server: the group must exist and have room (unfinished photos count)", () -> {
            try (Rig g = new Rig()) {
                error(g.upload(new Multipart().field("group", "gnothere1").file("photo", "a", new byte[]{1}).end()), 404, "no-group", UploadServer.NO_GROUP, "missing");
                error(g.upload(new Multipart().field("group", "gnojson03").file("photo", "a", new byte[]{1}).end()), 404, "no-group", UploadServer.NO_GROUP, "no group.json");
                for (String bad : new String[]{"Gbadgroup", "../gholiday1", "gholiday1/..", "g1"}) {
                    error(g.upload(new Multipart().field("group", bad).file("photo", "a", new byte[]{1}).end()), 400, "bad-id", UploadServer.UNKNOWN_GROUP, bad);
                }
                Path photos = g.r.resolve("groups/gfamily02/photos");
                Path src = g.r.resolve("groups/gholiday1/photos/pbeach00001");
                for (int i = 0; i < 59; i++) copyPhoto(src, photos, String.format("pfill%06d", i));
                Files.createDirectories(photos.resolve(".pinproc0001.tmp"));
                eq(g.lib.photos("gfamily02").size(), 59, "59 finished");
                error(g.upload(new Multipart().field("group", "gfamily02").file("photo", "a", new byte[]{1}).end()), 409, "group-full",
                        UploadServer.GROUP_FULL, "59 + 1 unfinished = 60");
                Files.delete(photos.resolve(".pinproc0001.tmp"));
                eq(g.upload(new Multipart().field("group", "gfamily02").file("photo", "a", new byte[]{1}).end()).status, 200, "the 60th");
                error(g.upload(new Multipart().field("group", "gfamily02").file("photo", "a", new byte[]{1}).end()), 409, "group-full",
                        UploadServer.GROUP_FULL, "61st");
                // 300 on the TV in all: 60 + 2 + 1 + 1 pending (pinflight04, the 60th) = 64; fill 4 groups more
                int n = 0;
                for (int k = 0; k < 4; k++) {
                    Map<String, Object> made = obj(g.lib.addGroup("Fill " + k));
                    Path ph = g.r.resolve("groups/" + made.get("id") + "/photos");
                    int want = k < 3 ? 60 : 300 - 64 - 180 - 1;
                    for (int i = 0; i < want; i++) copyPhoto(src, ph, String.format("pt%d%08d", k, n++));
                }
                eq(g.upload(new Multipart().field("group", "gholiday1").file("photo", "a", new byte[]{1}).end()).status, 200, "the 300th");
                error(g.upload(new Multipart().field("group", "gholiday1").file("photo", "a", new byte[]{1}).end()), 409, "tv-full",
                        UploadServer.TV_FULL, "301st");
            }
        });
        run("server: at most two uploads at a time", () -> {
            try (Rig g = new Rig()) {
                g.stub.hold = new CountDownLatch(1);
                g.stub.entered = new CountDownLatch(2);
                List<Resp> got = Collections.synchronizedList(new ArrayList<>());
                List<Thread> ts = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    Thread t = new Thread(() -> {
                        try {
                            got.add(g.upload(new Multipart().file("photo", "a", new byte[]{1}).end()));
                        } catch (IOException e) {
                            got.add(null);
                        }
                    });
                    t.start();
                    ts.add(t);
                }
                // Failed once under the gate's parallel load (2026-10-31) with no clue why: say what the
                // uploads answered (an early answer means one never reached the processor) and the log.
                check(g.stub.entered.await(5, TimeUnit.SECONDS), "two uploads in the processor (entered "
                        + (2 - g.stub.entered.getCount()) + "; answers so far " + describe(got) + "; log " + g.logs + ")");
                error(g.upload(new Multipart().file("photo", "a", new byte[]{1}).end()), 503, "busy", UploadServer.BUSY, "a third");
                eq(g.get(api("state")).status, 200, "other requests still answered");
                g.stub.hold.countDown();
                for (Thread t : ts) t.join(5000);
                eq(got.size(), 2, "both answered");
                for (Resp r : got) eq(r == null ? 0 : r.status, 200, "held upload");
                eq(g.upload(new Multipart().file("photo", "a", new byte[]{1}).end()).status, 200, "a slot is free again");
            }
        });
        run("server: a temp dir another upload just made is not a failure (mkdirs race)", () -> {
            // Two first uploads at once both found no temp dir; the loser's mkdirs() was false because
            // the winner had just made it, and its connection was dropped without an answer ("at most
            // two uploads at a time" failed under load, 2026-10-31: IOException "no temp dir"). This
            // File is that interleaving every time: absent until mkdirs(), which another upload wins.
            Path base = UploadTests.tmp("cf-upload-mkdir");
            File lost = new File(base.toFile(), "lost-race") {
                volatile boolean made;

                @Override
                public boolean isDirectory() {
                    return made;
                }

                @Override
                public boolean mkdirs() {
                    made = true;
                    return false;
                }
            };
            UploadServer.makeDir(lost);
            File plain = base.resolve("a/b").toFile();
            UploadServer.makeDir(plain);
            check(plain.isDirectory(), "made");
            UploadServer.makeDir(plain);
            File file = base.resolve("f").toFile();
            Files.write(file.toPath(), new byte[]{1});
            boolean thrown = false;
            try {
                UploadServer.makeDir(file);
            } catch (IOException e) {
                thrown = true;
            }
            check(thrown, "a file in the way is still a failure");
        });
        run("server: delete, new group and rename (v1's messages)", () -> {
            try (Rig g = new Rig()) {
                eq(g.request("DELETE", api("photos/gholiday1/pbeach00001"), null, new byte[0]).text(), "{\"ok\":true}", "delete");
                check(!Files.exists(g.r.resolve("groups/gholiday1/photos/pbeach00001")), "gone from R");
                error(g.request("DELETE", api("photos/gholiday1/pbeach00001"), null, new byte[0]), 404, "no-photo", UploadServer.GONE_PHOTO, "again");
                error(g.request("DELETE", api("photos/gholiday1/pinflight04"), null, new byte[0]), 404, "no-photo", UploadServer.GONE_PHOTO, "an unfinished one");
                error(g.request("DELETE", api("photos/Gbadgroup/pbeach00001"), null, new byte[0]), 400, "bad-id", UploadServer.UNKNOWN_PHOTO, "bad gid");
                error(g.request("DELETE", api("photos/gholiday1/..%2f"), null, new byte[0]), 400, "bad-id", UploadServer.UNKNOWN_PHOTO, "bad pid");
                Map<String, Object> made = g.json("POST", api("groups"), "{\"name\":\"  Trip  \"}").json();
                Map<?, ?> grp = (Map<?, ?>) made.get("group");
                check(UserLibrary.validUserGid((String) grp.get("id")), "new id");
                eq(grp.get("name") + "|" + grp.get("builtin") + "|" + grp.get("count"), "Trip|false|0", "new group");
                check(g.lib.readGroup((String) grp.get("id")) != null, "on disk");
                for (String bad : new String[]{"{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":5}", "{}", "{\"name\":\"" + "x".repeat(25) + "\"}",
                        "{\"name\":\"a\\u0007b\"}"}) {
                    error(g.json("POST", api("groups"), bad), 400, "bad-name", UploadServer.BAD_NAME, bad);
                }
                error(g.json("POST", api("groups"), "[\"name\"]"), 400, "bad-request", "Expected a JSON object.", "array");
                error(g.json("POST", api("groups"), "{name:1}"), 400, "bad-request", "Expected JSON.", "not JSON");
                error(g.json("POST", api("groups"), "{\"name\":\"" + "x".repeat(5000) + "\"}"), 413, "too-big", "The request is too big.", "over 4 KB");
                for (int i = 0; i < 17; i++) g.lib.addGroup("G" + i);
                error(g.json("POST", api("groups"), "{\"name\":\"One more\"}"), 409, "groups-full", UploadServer.GROUPS_FULL, "21st group");
                Map<?, ?> ren = (Map<?, ?>) g.json("PATCH", api("groups/gholiday1"), "{\"name\":\"Hols\"}").json().get("group");
                eq(ren.get("id") + "|" + ren.get("name") + "|" + ren.get("count"), "gholiday1|Hols|1", "rename");
                eq(g.lib.readGroup("gholiday1").name, "Hols", "renamed on disk");
                error(g.json("PATCH", api("groups/space"), "{\"name\":\"Stars\"}"), 400, "builtin", UploadServer.BUILTIN, "built-in");
                error(g.json("PATCH", api("groups/cyberpunk"), "{\"name\":\"Neon\"}"), 400, "builtin", UploadServer.BUILTIN, "built-in cyberpunk");
                error(g.json("PATCH", api("groups/gnothere1"), "{\"name\":\"X\"}"), 404, "no-group", UploadServer.NO_GROUP, "missing");
                error(g.json("PATCH", api("groups/BAD"), "{\"name\":\"X\"}"), 400, "bad-id", UploadServer.UNKNOWN_GROUP, "bad id");
                error(g.json("PATCH", api("groups/gholiday1"), "{\"name\":\"\"}"), 400, "bad-name", UploadServer.BAD_NAME, "bad name");
                error(g.json("PUT", api("groups/gholiday1"), "{}"), 501, "method", "Unsupported method.", "PUT");
            }
        });
        run("server: thumbs come from R; ids and names are validated", () -> {
            try (Rig g = new Rig()) {
                Resp t = g.get(api("thumbs/gholiday1/pbeach00001.jpg"));
                eq(t.status + " " + t.headers.get("content-type"), "200 image/jpeg", "thumb");
                check(Arrays.equals(t.body, Files.readAllBytes(g.r.resolve("groups/gholiday1/photos/pbeach00001/thumb.jpg"))), "bytes");
                error(g.get(api("thumbs/gholiday1/pnothere000.jpg")), 404, "not-found", "Not found.", "missing");
                error(g.get(api("thumbs/gholiday1/pinflight04.jpg")), 404, "not-found", "Not found.", "an unfinished photo");
                error(g.get(api("thumbs/gholiday1/pbeach00001.png")), 404, "not-found", "Not found.", "png");
                error(g.get(api("thumbs/../pbeach00001.jpg")), 400, "bad-id", UploadServer.UNKNOWN_PHOTO, "dot-dot gid");
                error(g.get(api("thumbs/gholiday1/..%2fpbeach00001.jpg")), 400, "bad-id", UploadServer.UNKNOWN_PHOTO, "encoded dot-dot pid");
                error(g.get(api("thumbs/gholiday1/pbeach00001.jpg/x")), 404, "not-found", "Not found.", "extra segment");
            }
        });
        run("server: traversal and odd request targets", () -> {
            try (Rig g = new Rig()) {
                page(g.get("/u/../api/" + TOKEN + "/state"), 403, UploadServer.EXPIRED, "/u/.. is just a wrong token");
                error(g.get(api("state/../photos")), 404, "not-found", "Not found.", "no dot-dot resolution");
                error(g.get(api("%2e%2e/state")), 404, "not-found", "Not found.", "no percent-decoding");
                error(g.get(api("state/")), 404, "not-found", "Not found.", "trailing slash");
                page(g.get("/static/%2e%2e/server"), 404, "Not found.", "static dot-dot");
                for (String target : new String[]{"*", "http://tv/", "api/x", "/a\u007fb", "/\u00e9"}) {
                    eq(send(g.port, ("GET " + target + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)).status, 400, "target " + target);
                }
                eq(send(g.port, "GET /a b HTTP/1.1\r\n\r\n".getBytes()).status, 400, "space in target");
            }
        });
        run("server: request heads cannot inject headers and are size-capped", () -> {
            try (Rig g = new Rig()) {
                String[] bad = {"X-A: a\rSet-Cookie: x\r\n", "X-A: a\r\n b\r\n", "X-A: a\u0000b\r\n", "Bad Name: x\r\n", ": x\r\n", "NoColon\r\n",
                        "Content-Length: 0\r\nContent-Length: 0\r\n", "Host: a\r\nHost: b\r\n", "Content-Type: a\r\ncontent-type: b\r\n",
                        "X-A: \u007f\r\n"};
                for (String h : bad) {
                    Resp r = send(g.port, ("GET / HTTP/1.1\r\n" + h + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    eq(r.status, 400, "refused: " + h.replace("\r", "\\r").replace("\n", "\\n"));
                }
                eq(send(g.port, "GET / HTTP/1.1\nHost: tv\nX-Many: a\nX-Many: b\n\n".getBytes()).status, 200, "LF-only lines, repeated header");
                eq(send(g.port, "\r\nGET / HTTP/1.1\r\n\r\n".getBytes()).status, 200, "one blank line before the request");
                eq(send(g.port, "GET / HTTP/1.0\r\n\r\n".getBytes()).status, 200, "HTTP/1.0");
                for (String line : new String[]{"GET / HTTP/2.0", "get / HTTP/1.1", "GET /  HTTP/1.1", "GET / HTTP/1.1 x", "GET /", "GARBAGE"}) {
                    eq(send(g.port, (line + "\r\n\r\n").getBytes()).status, 400, "request line " + line);
                }
                eq(send(g.port, ("GET /" + "a".repeat(2048) + " HTTP/1.1\r\n\r\n").getBytes()).status, 414, "2049-character target");
                eq(send(g.port, ("GET /" + "a".repeat(2200) + " HTTP/1.1\r\n\r\n").getBytes()).status, 414, "request line too long");
                eq(send(g.port, ("GET / HTTP/1.1\r\nX: " + "a".repeat(8200) + "\r\n\r\n").getBytes()).status, 431, "8 KB header line");
                StringBuilder many = new StringBuilder("GET / HTTP/1.1\r\n");
                for (int i = 0; i < 65; i++) many.append("X-").append(i).append(": 1\r\n");
                eq(send(g.port, many.append("\r\n").toString().getBytes()).status, 431, "65 headers");
                Resp inj = g.get("/u/%0d%0aSet-Cookie:%20x=1");
                eq(inj.status, 403, "encoded CRLF in a path");
                eq(inj.names, Arrays.asList("Content-Type", "Content-Length", "Cache-Control", "Referrer-Policy", "X-Content-Type-Options",
                        "X-Frame-Options", "Content-Security-Policy", "Connection"), "exactly our headers");
                boolean thrown = false;
                try {
                    UploadServer.headerLine(new StringBuilder(), "Location", "/u/x\r\nSet-Cookie: y");
                } catch (IllegalArgumentException e) {
                    thrown = true;
                }
                check(thrown, "a header value with CRLF is refused");
                thrown = false;
                try {
                    UploadServer.headerLine(new StringBuilder(), "Location", "/u/\u00e9");
                } catch (IllegalArgumentException e) {
                    thrown = true;
                }
                check(thrown, "a non-ASCII header value is refused");
                page(send(g.port, "HEAD / HTTP/1.1\r\n\r\n".getBytes()), 501, "Unsupported method.", "HEAD");
                page(send(g.port, "OPTIONS / HTTP/1.1\r\n\r\n".getBytes()), 501, "Unsupported method.", "OPTIONS");
                eq(UploadServer.escapeHtml("<a href=\"x\">'&'</a>"), "&lt;a href=&quot;x&quot;&gt;&#x27;&amp;&#x27;&lt;/a&gt;", "escapeHtml");
            }
        });
        run("server: an idle client is dropped after the read timeout", () -> {
            try (Rig g = new Rig(300, 700)) {
                try (Socket idle = new Socket()) {
                    idle.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                    idle.setSoTimeout(3000);
                    long t0 = System.nanoTime();
                    byte[] got = readAll(idle.getInputStream());
                    eq(got.length, 0, "an idle client gets nothing");
                    check(System.nanoTime() - t0 < 2_000_000_000L, "and is dropped after the read timeout");
                }
            }
        });
        run("server: a client trickling headers hits the head deadline (408)", () -> {
            // The head deadline (700 ms) runs on a clock this case steps by 150 ms per header line, in
            // lock step with the server's reads of it, and the read timeout is far away, so the answer
            // cannot depend on real time. (It used to sleep 150 ms per line against a 300 ms read
            // timeout; under the gate's load a sleep overran it (339 ms, 379 ms measured) and the
            // server dropped the client without an answer: "got -1, want 408", 2026-10-31.)
            try (Rig g = new Rig(10_000, 700)) {
                StepClock t = new StepClock();
                g.srv.headClock = t;
                try (Socket slow = new Socket()) {
                    slow.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                    slow.setSoTimeout(15_000);
                    OutputStream o = slow.getOutputStream();
                    o.write("GET / HTTP/1.1\r\n".getBytes());
                    o.flush();
                    // the clock is read when the deadline is set, then before every socket read
                    t.awaitReads(3, "the deadline set, the request line read and the next read begun");
                    int lines = 0;
                    while (t.ms() <= 700 && lines < 20) {
                        t.advanceMs(150);
                        o.write("X: y\r\n".getBytes());
                        o.flush();
                        lines++;
                        t.awaitReads(3 + lines, "header line " + lines + " read");
                    }
                    Resp r = parse(readAll(slow.getInputStream()));
                    eq(r.status, 408, "a header every 150 ms still hits the head deadline");
                    eq(lines, 5, "answered at the first line past it (5 x 150 ms > 700 ms)");
                }
            }
        });
        run("server: a client trickling bytes inside one header line is cut off at the head deadline (408)", () -> {
            // review 687edfd: the deadline was checked only between lines, so one byte every 29 s inside a
            // line (under the 30 s read timeout) held a slot for hours. Here: a byte every 20 ms, forever.
            try (Rig g = new Rig(10_000, 300)) {
                long[] took = new long[1];
                Resp r = trickleUntilAnswer(g.port, "GET / HTTP/1.1\r\nHost: tv\r\nX-Slow: ", took);
                page(r, 408, UploadServer.TOO_SLOW, "a header line that never ends");
                check(took[0] < 2500, "answered at the 300 ms deadline, not the 10 s read timeout: " + took[0] + " ms");
                await(() -> g.srv.openConnections() == 0, "the slot is freed");
            }
        });
        run("server: a slow body is cut off at its overall deadline (408), the upload slot freed", () -> {
            try (Rig g = new Rig(10_000, 10_000)) {
                g.srv.uploadDeadlineMs = 400;
                g.srv.bodyDeadlineMs = 10_000; // the photo's deadline alone must end it
                int before = g.tmpPhotos();
                long[] took = new long[1];
                Multipart m = new Multipart();
                Resp r = trickleUntilAnswer(g.port, "POST " + api("photos") + " HTTP/1.1\r\nHost: tv\r\nContent-Type: " + m.type()
                        + "\r\nContent-Length: 100000\r\n\r\n", took);
                error(r, 408, "timeout", UploadServer.TOO_SLOW, "a photo body at 50 bytes a second");
                check(took[0] < 2500, "answered at the 400 ms upload deadline, not the 10 s ones: " + took[0] + " ms");
                await(() -> g.srv.openConnections() == 0, "the slot is freed");
                refusedClean(g, before, "a slow upload");
                eq(g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1, 2, 3}).end()).status, 200, "the upload slot is free again");

                g.srv.bodyDeadlineMs = 400; // a small body (the code form) has its own deadline
                r = trickleUntilAnswer(g.port, "POST / HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded"
                        + "\r\nContent-Length: 4000\r\n\r\ncode=", took);
                page(r, 408, UploadServer.TOO_SLOW, "a code form at 50 bytes a second");
                check(took[0] < 2500, "answered at the 400 ms body deadline: " + took[0] + " ms");
            }
        });
        run("server: a POST from another site's page is refused before it counts against the code limit", () -> {
            // review 687edfd: any page a phone visits could POST wrong codes to http://<stick>:8097/ and
            // lock everyone out at "Too many wrong codes". Browsers send Origin on every cross-origin POST.
            try (Rig g = new Rig()) {
                String self = "http://127.0.0.1:" + g.port;
                java.util.function.BiFunction<String, String, byte[]> post = (origin, body) -> ("POST / HTTP/1.1\r\nHost: tv\r\n"
                        + (origin == null ? "" : "Origin: " + origin + "\r\n")
                        + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                        .getBytes(StandardCharsets.ISO_8859_1);
                for (int i = 0; i < 12; i++) {
                    page(send(g.port, post.apply("http://evil.example", "code=" + (100000 + i))), 403, UploadServer.CROSS_ORIGIN, "foreign " + i);
                }
                page(send(g.port, post.apply("null", "code=111111")), 403, UploadServer.CROSS_ORIGIN, "an opaque origin");
                page(send(g.port, post.apply(self + "0", "code=111111")), 403, UploadServer.CROSS_ORIGIN, "another port");
                page(send(g.port, post.apply("https://127.0.0.1:" + g.port, "code=111111")), 403, UploadServer.CROSS_ORIGIN, "another scheme");
                eq(send(g.port, post.apply(null, "code=482913")).status, 303, "none of those counted: no Origin still works");
                eq(send(g.port, post.apply(self, "code=482913")).status, 303, "the server's own origin works");
                for (int i = 0; i < 10; i++) page(send(g.port, post.apply(self, "code=" + (100000 + i))), 403, UploadServer.WRONG_CODE, "own " + i);
                page(send(g.port, post.apply(self, "code=482913")), 429, UploadServer.SLOW_DOWN, "own-origin wrong codes still count");
                byte[] group = "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8);
                Resp api = send(g.port, ("POST " + api("groups") + " HTTP/1.1\r\nHost: tv\r\nOrigin: http://evil.example\r\n"
                        + "Content-Type: application/json\r\nContent-Length: " + group.length + "\r\n\r\n{\"name\":\"x\"}").getBytes(StandardCharsets.ISO_8859_1));
                error(api, 403, "origin", UploadServer.CROSS_ORIGIN, "a foreign API POST");
                eq(send(g.port, ("GET / HTTP/1.1\r\nHost: tv\r\nOrigin: http://evil.example\r\n\r\n").getBytes()).status, 200, "a GET is not refused");
                check(g.logs.contains("POST origin 403"), "logged as refused by origin: " + g.logs);
            }
        });
        run("server: clients that stop reading are cut off at the write deadline, slots freed (G6)", () -> {
            try (Rig g = new Rig(3000, 3000)) {
                g.srv.writeDeadlineMs = 300;
                g.srv.sendBufferBytes = 4096;
                Path thumb = g.r.resolve("groups/gholiday1/photos/pbeach00001/thumb.jpg");
                Files.write(thumb, new byte[UploadServer.MAX_THUMB]); // the largest answer there is
                List<Socket> held = new ArrayList<>();
                try {
                    for (int i = 0; i < UploadServer.MAX_CONNECTIONS; i++) {
                        Socket s = new Socket();
                        s.setReceiveBufferSize(4096);
                        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                        s.getOutputStream().write(("GET " + api("thumbs/gholiday1/pbeach00001.jpg") + " HTTP/1.1\r\nHost: tv\r\n\r\n").getBytes());
                        held.add(s); // never read: the server's write blocks
                    }
                    await(() -> g.srv.openConnections() == UploadServer.MAX_CONNECTIONS, "the server takes all four");
                    long t0 = System.nanoTime();
                    await(() -> g.srv.openConnections() == 0, "the stalled answers are cut off and every slot freed");
                    check(System.nanoTime() - t0 < 10_000_000_000L, "at the write deadline, not later");
                    eq(g.get("/").status, 200, "a phone is served again, not 503");
                } finally {
                    for (Socket s : held) s.close();
                }
                g.srv.writeDeadlineMs = 10_000;
                Resp t = g.get(api("thumbs/gholiday1/pbeach00001.jpg"));
                eq(t.status + " " + t.body.length, "200 " + UploadServer.MAX_THUMB, "a client that reads gets the whole answer");
            }
        });
        run("server: an accept loop that gives up closes the socket, so the next start rebinds (A1)", () -> {
            try (Rig g = new Rig()) {
                g.srv.stop();
                AtomicInteger fails = new AtomicInteger();
                g.srv.acceptRetryMs = 1;
                g.srv.acceptor = s -> {
                    fails.incrementAndGet();
                    throw new IOException("accept failed (test)");
                };
                InetAddress lo = InetAddress.getLoopbackAddress();
                g.srv.start(lo, 0);
                int port = g.srv.address().getPort();
                await(() -> !g.srv.running(), "the server stops itself after " + UploadServer.MAX_ACCEPT_ERRORS + " failed accepts");
                eq(fails.get(), UploadServer.MAX_ACCEPT_ERRORS, "it gave up after MAX_ACCEPT_ERRORS in a row");
                check(notServedBy(g, lo, port), "and its socket is closed");
                g.srv.acceptor = java.net.ServerSocket::accept;
                g.srv.start(lo, 0); // the Add photo screen's next renewal
                check(g.srv.running(), "the next start rebinds instead of seeing 'already listening'");
                g.port = g.srv.address().getPort();
                eq(g.get("/").status, 200, "and serves");
            }
        });
        run("server: at most four connections; a fifth gets 503 at once", () -> {
            try (Rig g = new Rig(3000, 3000)) {
                List<Socket> held = new ArrayList<>();
                try {
                    for (int i = 0; i < UploadServer.MAX_CONNECTIONS; i++) {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                        s.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
                        held.add(s);
                    }
                    await(() -> g.srv.openConnections() == UploadServer.MAX_CONNECTIONS, "the server accepts all four");
                    error(g.get("/"), 503, "busy", UploadServer.TV_BUSY, "a fifth connection");
                } finally {
                    for (Socket s : held) s.close();
                }
                await(() -> g.srv.openConnections() == 0, "slots freed when the clients go");
                eq(g.get("/").status, 200, "served again once they have");
            }
        });
        run("server: a refused connection is drained, so a request with a body still reads its 503", () -> {
            // A 5th connection answered and closed with its request unread is reset, and the phone may
            // lose the 503 with it. 3 MB is past what the socket buffers hold unread here.
            try (Rig g = new Rig(3000, 3000)) {
                List<Socket> held = new ArrayList<>();
                try {
                    for (int i = 0; i < UploadServer.MAX_CONNECTIONS; i++) {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                        s.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
                        held.add(s);
                    }
                    await(() -> g.srv.openConnections() == UploadServer.MAX_CONNECTIONS, "the server accepts all four");
                    g.srv.lingerMs = 60_000; // the gate's load must not cut this drain short
                    error(g.uploadAll(new Multipart().file("photo", "a.jpg", new byte[3_000_000]).end()), 503, "busy", UploadServer.TV_BUSY, "3 MB sent to a full server");
                    await(() -> g.srv.refusedConnections() == 0, "that drain ends when the client does");
                    g.srv.lingerMs = UploadServer.LINGER_MS;
                    try (Socket idle = new Socket()) {
                        idle.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                        idle.setSoTimeout(10_000);
                        check(parse(readAll(idle.getInputStream())).status == 503, "an idle client reads its 503 and the server's end of it");
                        await(() -> g.srv.refusedConnections() == 0, "the drain of a client that stays open ends on its own");
                    }
                    // A drain never stalls the accept loop: with one held open, the next connection is still answered.
                    g.srv.lingerMs = 60_000;
                    g.srv.lingerReadMs = 60_000;
                    try (Socket idle = new Socket()) {
                        idle.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                        idle.setSoTimeout(10_000);
                        check(parse(readAll(idle.getInputStream())).status == 503, "the idle client reads its 503");
                        error(g.get("/"), 503, "busy", UploadServer.TV_BUSY, "answered while another is being drained");
                        await(() -> g.srv.refusedConnections() == 1, "the other's drain is over");
                    }
                    await(() -> g.srv.refusedConnections() == 0, "the held drain ends when its client goes");
                } finally {
                    for (Socket s : held) s.close();
                }
            }
        });
        run("server: at stop no refused connection is left open", () -> {
            Rig g = new Rig(3000, 3000);
            List<Socket> held = new ArrayList<>();
            try {
                for (int i = 0; i <= UploadServer.MAX_CONNECTIONS; i++) {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), g.port));
                    held.add(s);
                }
                await(() -> g.srv.refusedConnections() == 1, "the fifth is being drained");
                g.srv.stop();
                eq(g.srv.refusedConnections(), 0, "stop() closes it");
            } finally {
                for (Socket s : held) s.close();
                g.close();
            }
        });
        run("server: a thumb may be kept by this phone only; nothing else may be kept", () -> {
            try (Rig g = new Rig()) {
                Resp r = g.get(api("thumbs/gholiday1/pbeach00001.jpg"));
                eq(r.status, 200, "thumb");
                eq(r.headers.get("cache-control"), UploadServer.THUMB_CACHE, "thumb cache");
                check(r.headers.get("cache-control").startsWith("private,") && !r.headers.get("cache-control").contains("public"), "un-shareable");
                eq(r.headers.get("content-security-policy"), UploadServer.CSP, "still the CSP");
                eq(g.get(api("thumbs/gholiday1/pno00000000.jpg")).headers.get("cache-control"), "no-store", "a missing thumb is not kept");
                eq(g.get("/api/" + "f".repeat(32) + "/thumbs/gholiday1/pbeach00001.jpg").headers.get("cache-control"), "no-store", "nor a wrong token's answer");
            }
        });
        run("server: an OutOfMemoryError in a worker drops that connection only: nothing uncaught, the slot and upload freed", () -> {
            // Uncaught on a worker thread it would reach Android's handler, which kills the process: Home.
            Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
            AtomicInteger uncaught = new AtomicInteger();
            OutOfMemoryError oom = new OutOfMemoryError("planted in the processor");
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                if (e == oom) uncaught.incrementAndGet();
                else if (before != null) before.uncaughtException(t, e);
            });
            try (Rig g = new Rig()) {
                g.stub.fatal = oom;
                int tmp = g.tmpPhotos();
                for (int i = 0; i < UploadServer.MAX_CONNECTIONS + UploadServer.UPLOAD_SLOTS; i++) { // every slot, if any leaked
                    Resp r;
                    try {
                        r = g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end());
                    } catch (IOException e) {
                        r = null; // reset: as good as closed
                    }
                    check(r == null || r.status == -1, "no answer, the connection closes: " + r);
                }
                await(() -> g.srv.openConnections() == 0, "the slots are freed");
                eq(g.tmpPhotos(), tmp, "the unfinished photos are removed");
                Thread.sleep(200); // an uncaught throw reaches the handler just after the worker's finally
                eq(uncaught.get(), 0, "nothing uncaught");
                g.stub.fatal = null;
                eq(g.upload(new Multipart().file("photo", "a.jpg", new byte[]{1}).end()).status, 200, "the next upload works");
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(before);
            }
        });
        run("server: no stack traces to the phone, no tokens or codes in the logs", () -> {
            try (Rig g = new Rig()) {
                g.get("/u/" + TOKEN);
                g.get(api("state"));
                g.request("POST", "/", "application/x-www-form-urlencoded", "code=482913".getBytes());
                g.request("POST", "/", "application/x-www-form-urlencoded", "code=111111".getBytes());
                g.upload(new Multipart().file("photo", TOKEN + ".jpg", new byte[]{1}).end());
                g.get("/u/" + TOKEN + "?code=" + CODE);
                g.staticsBroken = true;
                Resp r = g.get("/u/" + TOKEN);
                error(r, 500, "internal", UploadServer.INTERNAL, "a broken asset");
                check(!r.text().contains("Exception") && !r.text().contains("Secret") && !r.text().contains("at home"), "no stack trace");
                // no wait here: send() returns once the server has let go, after its log line
                check(g.logs.size() >= 7, "a line per request: " + g.logs.size());
                for (String l : new ArrayList<>(g.logs)) {
                    check(!l.contains(TOKEN) && !l.contains(CODE) && !l.contains("111111") && !l.contains("0123456789abcdef"), "log leaks: " + l);
                }
                check(g.logs.contains("GET /u 200") && g.logs.contains("POST / 303") && g.logs.contains("POST /api/photos 200"), "lines: " + g.logs);
            }
        });
        run("server: the multipart parser finds every delimiter wherever a read splits it", () -> {
            String bd = "XyZ-split";
            byte[] photo = "A\r\n--XyZ-spli\r\n--XyZ-\r\r\n-XyZ-split--\n--XyZ-split\r".getBytes(StandardCharsets.ISO_8859_1); // look-alikes only
            byte[] body = new Multipart(bd).raw("pre\r\n").field("group", "gfamily02").file("photo", "s.jpg", photo).field("x", "y").end().body();
            for (int cut = 0; cut <= body.length; cut++) {
                final int at = cut;
                InputStream in = new InputStream() { // the first read stops at byte `at`, then the rest
                    int pos;

                    @Override
                    public int read() {
                        return pos < body.length ? body[pos++] & 0xff : -1;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        if (pos >= body.length) return -1;
                        int end = pos < at ? Math.min(at, pos + len) : Math.min(body.length, pos + len);
                        if (end == pos) end = pos + 1;
                        System.arraycopy(body, pos, b, off, end - pos);
                        int n = end - pos;
                        pos = end;
                        return n;
                    }
                };
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                UploadServer.Form f = UploadServer.parseMultipart(in, bd, out, 1000);
                check(f.photo && Arrays.equals(out.toByteArray(), photo), "split at " + cut + ": photo " + new String(out.toByteArray(), StandardCharsets.ISO_8859_1));
                eq(f.group + "|" + f.filename + "|" + f.photoBytes, "gfamily02|s.jpg|" + photo.length, "split at " + cut);
            }
        });
        run("server: form fields and multipart parameters parse like v1", () -> {
            eq(UploadServer.formField("a=1&code=%20482913+&code=9", "code"), " 482913 ", "first non-empty value, decoded");
            eq(UploadServer.formField("code=&code=123456", "code"), "123456", "blank values skipped (parse_qs)");
            eq(UploadServer.formField("nocode", "code"), "", "missing");
            eq(UploadServer.formField("code=%zz1", "code"), "%zz1", "a bad escape kept");
            eq(UploadServer.urlDecode("caf%C3%A9+x"), "café x", "UTF-8");
            eq(UploadServer.boundary("multipart/form-data; boundary=----WebKitFormBoundaryABC"), "----WebKitFormBoundaryABC", "webkit");
            eq(UploadServer.boundary("Multipart/Form-Data; Boundary=\"a b\""), "a b", "quoted, any case");
            eq(UploadServer.boundary("multipart/form-data;boundary=x;charset=utf-8"), "x", "more params");
            eq(UploadServer.boundary("multipart/mixed; boundary=x"), null, "not form-data");
            eq(UploadServer.boundary("multipart/form-data"), null, "no boundary");
            eq(UploadServer.boundary("multipart/form-data; boundary=a b "), "a b", "an unquoted value is trimmed");
            eq(UploadServer.boundary("multipart/form-data; boundary=\"ends \""), null, "ends in a space");
            eq(UploadServer.boundary(null), null, "null");
            Map<String, String> p = UploadServer.params(" name=\"photo\"; filename=\"a \\\"b\\\".jpg\"; x; Y=z");
            eq(p.toString(), "{name=photo, filename=a \"b\".jpg, y=z}", "params");
        });
    }

    static void copyPhoto(Path src, Path photos, String pid) throws IOException {
        Path d = photos.resolve(pid);
        Files.createDirectories(d);
        for (String f : UserLibrary.PHOTO_FILES) Files.copy(src.resolve(f), d.resolve(f));
        String meta = new String(Files.readAllBytes(src.resolve("meta.json")), StandardCharsets.UTF_8).replace("pbeach00001", pid);
        Files.write(d.resolve("meta.json"), meta.getBytes(StandardCharsets.UTF_8));
    }
}
