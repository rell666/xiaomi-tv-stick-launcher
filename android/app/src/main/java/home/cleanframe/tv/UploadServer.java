package home.cleanframe.tv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The phone's upload page, served by the TV app itself (docs/PHOTO-GROUPS.md, contract 4): a
 * minimal HTTP/1.1 server on one ServerSocket bound to the stick's LAN IPv4 address, port 8097,
 * started by AndroidHome.uploadStart() and stopped with the Add photo screen. Plain Java (no
 * Android types): the library, the processor, the clock and the static files are injected, so
 * tests/java/UploadServerTest.java runs it end to end over a loopback socket in a plain JVM.
 *
 * Endpoints, messages and limits are v1's (tools/photo-upload/server.py, removed), so the phone page
 * carried over; there is no restore endpoint (no archive in v2). Errors are
 * {"ok":false,"error":&lt;code&gt;,"message":&lt;text&gt;} under /api/, an HTML page elsewhere.
 *   GET  /                                  code page            POST / code=NNNNNN -> 303 /u/&lt;token&gt;
 *   GET  /u/&lt;token&gt;                         the phone page       GET  /static/{app.js,style.css}
 *   GET  /api/&lt;token&gt;/state                 groups, photos, unfinished photos, limits
 *   POST /api/&lt;token&gt;/photos                multipart: photo=&lt;file&gt; [group=&lt;gid&gt;] [crop=x,y,z]
 *   DELETE /api/&lt;token&gt;/photos/&lt;gid&gt;/&lt;pid&gt;  POST /api/&lt;token&gt;/groups {"name"}  PATCH /api/&lt;token&gt;/groups/&lt;gid&gt;
 *   GET  /api/&lt;token&gt;/thumbs/&lt;gid&gt;/&lt;pid&gt;.jpg  from R
 *
 * Auth is UserLibrary's upload token (constant-time compare; expiry and renewal are UserLibrary's).
 * Safety: one connection per request (Connection: close), at most MAX_CONNECTIONS at once (more get
 * 503, drained so the phone can read it: refuseBusy), a read timeout on every socket read, and overall deadlines for the request head, the body and
 * the lingering close, checked inside every socket read (Timed), so no byte-at-a-time client keeps a
 * connection past them, and a deadline on every answer's writes (writeDeadlineMs: a client that stops
 * reading has its socket closed, review 30e7356 G6); a state-changing request (anything but GET) whose Origin header is present
 * and not this server's own origin is refused before it is counted (a web page a phone visits cannot
 * spend the code rate limit; the limit is per client address, under a higher ceiling for all), strict request-line
 * and header parsing (no bare CR, no folding, no duplicate framing headers, size caps), the
 * Content-Length checked before any body is read, a streaming multipart parser that writes the photo
 * to a temp file (never the whole body in memory), ids validated before use, response header values
 * that cannot carry a line break, CSP/no-referrer on every answer, no-store on all but a thumb (THUMB_CACHE), no stack traces. Logs
 * name only the method, the route and the status: never a token, a code, a path or a query.
 */
final class UploadServer {
    static final int PORT = 8097;
    static final long MAX_UPLOAD = 40L * 1024 * 1024;
    static final long MULTIPART_SLACK = 64 * 1024;
    static final int MAX_JSON = 4096;
    /** Wrong codes a minute from one client address (checkCode). */
    static final int CODE_FAILS_PER_MIN = 10;
    /** Wrong codes a minute from every address together: well above one client's, still a ceiling on guessing. */
    static final int CODE_FAILS_ALL_PER_MIN = 60;
    /**
     * Client addresses the code rate limit remembers at once (each for a minute after its last wrong
     * code); while it is full an address it does not hold is treated as over its own limit.
     */
    static final int CODE_CLIENTS_MAX = 256;
    static final int MAX_CONNECTIONS = 4;
    static final int UPLOAD_SLOTS = 2;
    /**
     * A thumb is the one answer a browser may keep: its address holds the token, so it is only ever
     * this phone's ("private": no proxy may store it) and it goes when the token does. The phone page
     * would otherwise fetch every thumb again, one connection each, whenever it rebuilds an image.
     */
    static final String THUMB_CACHE = "private, max-age=300";
    /** Refused connections being drained at once, and waiting for a thread; past that, closed at once. */
    static final int REFUSE_THREADS = 2, REFUSE_QUEUE = 14;
    static final int MAX_REQUEST_LINE = 2048 + 32, MAX_HEADER_LINE = 8192, MAX_HEADERS = 64;
    static final int MAX_PARTS = 16, MAX_FIELD = 1024, MAX_PART_HEADERS = 8192, MAX_PREAMBLE = 64 * 1024;
    static final int MAX_THUMB = 2 * 1024 * 1024;
    /**
     * Socket read timeout (each read), and the overall deadlines: the request head, a small body (the
     * code form, JSON) and a photo's body (40 MB in 5 minutes is ~140 KB/s, far below any home Wi-Fi).
     * Each deadline is enforced inside every socket read (Timed), not between lines. Tests shorten them.
     */
    int readTimeoutMs = 30_000;
    long headDeadlineMs = 15_000;
    long bodyDeadlineMs = 15_000;
    long uploadDeadlineMs = 300_000;
    /**
     * Every answer must be written (and flushed) within this long, or its socket is closed (review
     * 30e7356, G6: only reads had deadlines, so a paired client that stopped reading held a worker in
     * write() for ever, and four of them answered every phone 503). The largest answer is a 2 MB thumb.
     */
    long writeDeadlineMs = 30_000;
    /** The socket send buffer, 0 for the system's; tests make it small so a write blocks at once. */
    int sendBufferBytes;
    /** Waits between failed accept()s; after MAX_ACCEPT_ERRORS in a row the server closes itself (A1). */
    long acceptRetryMs = 100;
    static final int MAX_ACCEPT_ERRORS = 50;

    /** Accepts one connection; tests swap in one that fails as a broken socket does. */
    interface Acceptor {
        Socket accept(ServerSocket s) throws IOException;
    }

    volatile Acceptor acceptor = ServerSocket::accept;
    /** The answer is sent: the client's unread bytes are drained for at most this long, 1 s per read. */
    static final long LINGER_MS = 2_000;
    static final int LINGER_READ_MS = 1_000;
    /** The same, per server; a test lengthens them to hold a drain open. */
    long lingerMs = LINGER_MS;
    int lingerReadMs = LINGER_READ_MS;
    /** The clock every deadline runs on, in nanoseconds; tests step it by hand. */
    volatile LongSupplier headClock = System::nanoTime;

    /** Opens the upload's temp file; tests swap in one whose writes fail as on a full disk. */
    interface PartFiles {
        OutputStream open(File f) throws IOException;

        /** Makes the temp file in dir. */
        default File create(File dir) throws IOException {
            return File.createTempFile("upload-", ".part", dir);
        }
    }

    volatile PartFiles partFiles = FileOutputStream::new;

    /**
     * The photo's temp file, which never throws: the first failed write or close (a full disk) is
     * kept in failed and every later byte dropped, so the parser still reads the whole body and the
     * phone is answered 507 instead of the connection closing on it (review 006cfe9). A temp file that
     * could not even be made is the same: out is null and failed is set from the start.
     */
    static final class Spool extends OutputStream {
        private final OutputStream out;
        IOException failed;

        Spool(OutputStream out) {
            this.out = out;
        }

        Spool(IOException failed) {
            this.out = null;
            this.failed = failed;
        }

        @Override
        public void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (failed != null) return;
            try {
                out.write(b, off, len);
            } catch (IOException e) {
                failed = e;
            }
        }

        @Override
        public void close() {
            if (out == null) return;
            try {
                out.close();
            } catch (IOException e) {
                if (failed == null) failed = e;
            }
        }
    }

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; "
            + "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    static final String EXPIRED = "This code has expired. Open Add photo on the TV again.";
    static final String TOO_BIG = "That photo is over " + MAX_UPLOAD / 1048576 + " MB.";
    static final String NO_LENGTH = "Send the upload with a Content-Length.";
    static final String NOT_MULTIPART = "Expected a multipart/form-data upload.";
    static final String BAD_CROP = "The crop was not understood. Choose the photo again.";
    static final String MALFORMED = "The upload is malformed.";
    static final String CUT_OFF = "The upload was cut off.";
    static final String NO_PHOTO = "No photo was sent.";
    static final String BUSY = "Another photo is being added. Try again in a moment.";
    static final String TV_BUSY = "The TV is busy. Try again in a moment.";
    static final String PREPARE = "The photo could not be prepared. Try another one.";
    static final String INTERNAL = "Something went wrong on the TV.";
    static final String UNKNOWN_GROUP = "Unknown group.", NO_GROUP = "That group is no longer on the TV.";
    static final String UNKNOWN_PHOTO = "Unknown photo.", GONE_PHOTO = "That photo is no longer on the TV.";
    static final String BAD_NAME = "A group name is 1 to " + UserLibrary.NAME_MAX + " characters.";
    static final String GROUPS_FULL = "The TV already has " + UserLibrary.MAX_GROUPS + " groups, the most it can hold.";
    static final String GROUP_FULL = "This group already has " + UserLibrary.MAX_PHOTOS_PER_GROUP + " photos, the most it can hold.";
    static final String TV_FULL = "The TV already has " + UserLibrary.MAX_PHOTOS + " photos, the most it can hold. Remove some first.";
    static final String BUILTIN = "The built-in groups cannot be renamed.";
    static final String SLOW_DOWN = "Too many wrong codes. Wait a minute, then try again.";
    static final String NO_CODE = "Open Add photo on the TV first, then type the code it shows.";
    static final String WRONG_CODE = "That code is not right, or it has expired. Check the TV and try again.";
    static final String CROSS_ORIGIN = "Open the address the TV shows and send this from that page.";
    static final String TOO_SLOW = "The request took too long.";
    static final String STORAGE_FULL = "TV storage is full. Remove some photos or apps on the TV, then try again.";
    static final Map<String, String> STATIC = new LinkedHashMap<>();
    static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}"), CODE = Pattern.compile("[0-9]{6}");
    static final Pattern BOUNDARY = Pattern.compile("[0-9A-Za-z'()+_,\\-./:=? ]{0,69}[0-9A-Za-z'()+_,\\-./:=?]");

    static {
        STATIC.put("app.js", "text/javascript; charset=utf-8");
        STATIC.put("style.css", "text/css; charset=utf-8");
        STATIC.put("crop.js", "text/javascript; charset=utf-8");
    }

    static final String CODE_PAGE = "<!doctype html>\n"
            + "<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            + "<title>CleanFrame photos</title><link rel=\"stylesheet\" href=\"/static/style.css\"></head>\n"
            + "<body><main class=\"page code-page\">\n"
            + "<h1>Add photos to the TV</h1>\n"
            + "<p>On the TV, open <b>Add photo</b> in a group. Type the 6-digit code it shows.</p>\n"
            + "<form method=\"post\" action=\"/\" autocomplete=\"off\">\n"
            + "<label for=\"code\">Code</label>\n"
            + "<input id=\"code\" name=\"code\" type=\"text\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" maxlength=\"6\"\n"
            + " autocomplete=\"one-time-code\" required autofocus placeholder=\"123456\">\n"
            + "<button class=\"primary\" type=\"submit\">Continue</button>\n"
            + "</form>\n"
            + "%s\n"
            + "</main></body></html>\n";
    static final String EXPIRED_PAGE = "<!doctype html>\n"
            + "<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            + "<title>CleanFrame photos</title><link rel=\"stylesheet\" href=\"/static/style.css\"></head>\n"
            + "<body><main class=\"page code-page\"><h1>Add photos to the TV</h1>\n"
            + "<p class=\"error\" role=\"alert\">%s</p>\n"
            + "<p><a class=\"button\" href=\"/\">Type the code instead</a></p></main></body></html>\n";

    /** The phone page's files: "index.html", "app.js", "style.css" (MainActivity: assets/upload/). */
    interface Statics {
        byte[] get(String name) throws IOException;
    }

    /** One log line per request; never given a token, a code, a path or a body. */
    interface Log {
        void line(String s);
    }

    /** An answer other than 200: status, error code, message for the phone. */
    static final class Refused extends Exception {
        final int status;
        final String code;

        Refused(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private final UserLibrary lib;
    private final PendingPhotos pending;
    private final PhotoProcessor processor;
    private final Statics statics;
    private final File tmpDir;
    private final LongSupplier clock;
    private final Runnable onQueued;
    private final Log log;
    /** Orders every library change made here (check, then change). */
    private final Object changes = new Object();
    private final Semaphore uploads = new Semaphore(UPLOAD_SLOTS);
    /** Wrong codes in the last minute: all of them, and per client address (guarded by codeFails). */
    private final ArrayDeque<Long> codeFails = new ArrayDeque<>();
    private final Map<InetAddress, ArrayDeque<Long>> clientFails = new HashMap<>();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    /** Connections answered 503 and being drained (refuseBusy); not in open: they hold no slot. */
    private final Set<Socket> refused = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private ExecutorService workers;
    /** Drains refused connections, so the accept loop never waits on one. */
    private ExecutorService refusers;
    /** Closes a socket whose answer is overdue (writeDeadlineMs); one thread per start, stopped with it. */
    private ScheduledExecutorService reaper;

    UploadServer(UserLibrary lib, PendingPhotos pending, PhotoProcessor processor, Statics statics, File tmpDir,
                 LongSupplier clock, Runnable onQueued, Log log) {
        this.lib = lib;
        this.pending = pending;
        this.processor = processor;
        this.statics = statics;
        this.tmpDir = tmpDir;
        this.clock = clock;
        this.onQueued = onQueued;
        this.log = log;
    }

    // ------------------------------------------------------------------ start / stop

    /**
     * Listen on addr:port only (port 0: any free port, for tests). Already listening there: nothing
     * changes (the Add photo screen renews its code every few minutes). Elsewhere: moved.
     */
    synchronized void start(InetAddress addr, int port) throws IOException {
        if (server != null && !server.isClosed() && addr.equals(server.getInetAddress())
                && (port == 0 || port == server.getLocalPort())) return;
        stop();
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(addr, port), 16);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
        ExecutorService w = Executors.newFixedThreadPool(MAX_CONNECTIONS, r -> {
            Thread t = new Thread(r, "cleanframe-upload");
            t.setDaemon(true);
            return t;
        });
        Semaphore slots = new Semaphore(MAX_CONNECTIONS);
        ScheduledThreadPoolExecutor r = new ScheduledThreadPoolExecutor(1, t0 -> {
            Thread t = new Thread(t0, "cleanframe-upload-reaper");
            t.setDaemon(true);
            return t;
        });
        r.setRemoveOnCancelPolicy(true); // an answer written in time leaves nothing queued (the phone polls every 2 s)
        ThreadPoolExecutor d = new ThreadPoolExecutor(REFUSE_THREADS, REFUSE_THREADS, 5, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(REFUSE_QUEUE), t0 -> {
            Thread t = new Thread(t0, "cleanframe-upload-refuse");
            t.setDaemon(true);
            return t;
        });
        d.allowCoreThreadTimeOut(true); // none are kept unless the server is really over its limit
        server = s;
        workers = w;
        reaper = r;
        refusers = d;
        Thread acceptor = new Thread(() -> acceptLoop(s, w, slots, r, d), "cleanframe-upload-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Stop listening and drop every open connection (an upload in progress fails). Never throws. */
    synchronized void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // closed anyway
            }
        }
        for (Socket c : open) quietClose(c);
        for (Socket c : refused) quietClose(c);
        refused.clear(); // a drain still queued never runs
        if (workers != null) workers.shutdownNow();
        workers = null;
        if (refusers != null) refusers.shutdownNow();
        refusers = null;
        if (reaper != null) reaper.shutdownNow();
        reaper = null;
    }

    /**
     * The accept loop of s gave up (MAX_ACCEPT_ERRORS failures in a row): s is closed and, if it is still
     * this server's, the server stops, so running() is false and the next start() rebinds (review
     * 30e7356, A1: the socket was left open, so every renewal's start() saw "already listening" while
     * nothing accepted).
     */
    private synchronized void acceptFailed(ServerSocket s) {
        if (server == s) {
            stop();
        } else {
            try {
                s.close();
            } catch (IOException e) {
                // closed anyway
            }
        }
    }

    /** Connections being served (a slot is released before its socket leaves this count). */
    int openConnections() {
        return open.size();
    }

    /** Refused connections still being drained (tests: the drain ends on its own). */
    int refusedConnections() {
        return refused.size();
    }

    /** Whether the connection from this client port is still open here (its slot is released first). */
    boolean serving(int clientPort) {
        for (Socket c : open) {
            if (c.getPort() == clientPort) return true;
        }
        return false;
    }

    synchronized boolean running() {
        return server != null && !server.isClosed();
    }

    /** The bound address and port, or null when stopped. */
    synchronized InetSocketAddress address() {
        return running() ? new InetSocketAddress(server.getInetAddress(), server.getLocalPort()) : null;
    }

    /** The address the Add photo screen shows: http://&lt;ip&gt;:8097 (UserLibrary.validUploadBase accepts it). */
    static String baseUrl(InetAddress a) {
        return "http://" + a.getHostAddress() + ":" + PORT;
    }

    /** One network interface as NetworkInterface describes it (MainActivity fills these in). */
    static final class Iface {
        final String name;
        final boolean up, loopback;
        final List<InetAddress> addresses;

        Iface(String name, boolean up, boolean loopback, List<InetAddress> addresses) {
            this.name = name == null ? "" : name;
            this.up = up;
            this.loopback = loopback;
            this.addresses = addresses;
        }
    }

    /** Interfaces that are never the home LAN: VPNs, Wi-Fi Direct, mobile data, test devices. */
    static final String[] NOT_LAN = {"tun", "ppp", "p2p", "dummy", "rmnet", "ccmni", "v4-", "ipsec", "wg", "lo"};

    /**
     * The stick's LAN IPv4 address: on an interface that is up and not loopback or a VPN/P2P link, a
     * unicast IPv4 address that is not loopback, link-local or the wildcard. A private (site-local)
     * address beats a public one, then wlan and eth interfaces beat others, then list order. null if none.
     */
    static InetAddress pickAddress(List<Iface> ifaces) {
        InetAddress best = null;
        int bestScore = -1;
        for (Iface f : ifaces) {
            if (!f.up || f.loopback) continue;
            boolean skip = false;
            for (String p : NOT_LAN) skip |= f.name.startsWith(p);
            if (skip) continue;
            for (InetAddress a : f.addresses) {
                if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isAnyLocalAddress() || a.isMulticastAddress()) continue;
                int score = (a.isSiteLocalAddress() ? 2 : 0) + (f.name.startsWith("wlan") || f.name.startsWith("eth") ? 1 : 0);
                if (score > bestScore) {
                    best = a;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private void acceptLoop(ServerSocket s, ExecutorService w, Semaphore slots, ScheduledExecutorService r, ExecutorService d) {
        int errors = 0;
        while (!s.isClosed()) {
            Socket c;
            try {
                c = acceptor.accept(s);
                errors = 0;
            } catch (IOException | RuntimeException e) {
                if (s.isClosed()) return;
                if (++errors >= MAX_ACCEPT_ERRORS) { // something is badly wrong: close, so the next uploadStart() rebinds
                    acceptFailed(s);
                    return;
                }
                try {
                    Thread.sleep(acceptRetryMs);
                } catch (InterruptedException ie) {
                    acceptFailed(s);
                    return;
                }
                continue;
            }
            if (!slots.tryAcquire()) {
                refuseBusy(c, r, d);
                continue;
            }
            open.add(c);
            try {
                w.execute(() -> {
                    try {
                        serve(c, r);
                    } catch (Throwable e) {
                        // An OutOfMemoryError on the 2 GB stick, say: uncaught on this thread it would kill the
                        // whole process, and this is Home. This connection is dropped, the rest go on.
                    } finally {
                        quietClose(c);
                        slots.release();
                        open.remove(c); // last: no open socket means every slot is free (openConnections)
                    }
                });
            } catch (RejectedExecutionException e) {
                quietClose(c);
                slots.release();
                open.remove(c);
            }
        }
    }

    /**
     * Every slot is taken: answered 503, then drained like any other answer (lingeringClose), because
     * closing with the request still unread resets the connection and the phone may lose the 503 with it.
     * The answer fits a fresh socket's buffer, so it is written here; the drain, which can last LINGER_MS,
     * is another thread's, or a client that sends nothing would stall the accept loop. Too many at once:
     * closed undrained, and the phone page retries a reset as it does a 503.
     */
    private void refuseBusy(Socket c, ScheduledExecutorService r, ExecutorService d) {
        boolean draining = false;
        try {
            c.setSoTimeout(1000);
            Timed timed = new Timed(c);
            Exchange x = new Exchange(timed, new BufferedOutputStream(c.getOutputStream()), timed, null);
            x.guard(c, r);
            x.json(503, err("busy", TV_BUSY));
            log("- busy 503");
            refused.add(c);
            d.execute(() -> {
                try {
                    lingeringClose(c, x);
                } catch (Throwable e) {
                    // closing anyway
                } finally {
                    quietClose(c);
                    refused.remove(c);
                }
            });
            draining = true;
        } catch (IOException | RuntimeException e) {
            // the client goes away, or too many are being drained
        } finally {
            if (!draining) {
                quietClose(c);
                refused.remove(c);
            }
        }
    }

    private static void quietClose(Socket c) {
        try {
            c.close();
        } catch (IOException e) {
            // closed anyway
        }
    }

    private void log(String s) {
        try {
            if (log != null) log.line(s);
        } catch (RuntimeException e) {
            // logging never breaks a request
        }
    }

    // ------------------------------------------------------------------ one exchange

    /** A socket read found its deadline passed (answered 408 if nothing was sent yet). */
    static final class Late extends IOException {
        Late() {
            super("deadline passed");
        }
    }

    /**
     * A connection's input under one overall deadline on headClock: every read from the socket first
     * checks it (Late once it has passed) and cuts the socket timeout to what is left of it. Until
     * 2026-11-02 the head deadline was checked only between lines and the body had none, so a LAN
     * client sending one byte every 29 s inside one line held a connection slot for hours (four of
     * them answered every phone 503).
     */
    final class Timed extends InputStream {
        private final Socket socket;
        private final InputStream in;
        private long deadline;
        private int cap, set = -1;

        Timed(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
        }

        /** The deadline is ms from now; a single read still waits at most cap ms. */
        void arm(long ms, int cap) {
            deadline = headClock.getAsLong() + ms * 1_000_000L;
            this.cap = cap;
        }

        private void before() throws IOException {
            long left = deadline - headClock.getAsLong();
            if (left <= 0) throw new Late();
            int t = (int) Math.max(1, Math.min(cap, (left + 999_999) / 1_000_000L));
            if (t != set) {
                socket.setSoTimeout(t);
                set = t;
            }
        }

        @Override
        public int read() throws IOException {
            while (true) {
                before();
                try {
                    return in.read();
                } catch (SocketTimeoutException e) {
                    if (set >= cap) throw e; // the read timeout: a stalled client, dropped
                    // only the deadline's share ran out: before() decides, on the deadline's clock
                }
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (true) {
                before();
                try {
                    return in.read(b, off, len);
                } catch (SocketTimeoutException e) {
                    if (set >= cap) throw e;
                }
            }
        }
    }

    /** One request and its answer on one connection. */
    final class Exchange {
        final InputStream in;
        final OutputStream out;
        /** The connection's deadline. */
        final Timed timed;
        /** This server's own origin as a browser names it: http://&lt;address it serves on&gt;:&lt;port&gt;. */
        final String self;
        /** The client's address (the code rate limit is counted per address); null for refuseBusy's answer. */
        InetAddress client;
        String method, target, path, route = "-";
        final Map<String, String> headers = new LinkedHashMap<>();
        boolean sent;
        int status;
        /** This answer's Cache-Control. */
        String cache = "no-store";
        /** The socket send() closes when its writes outlast writeDeadlineMs, and the thread that does it. */
        private Socket socket;
        private ScheduledExecutorService reaper;

        Exchange(InputStream in, OutputStream out, Timed timed, String self) {
            this.in = in;
            this.out = out;
            this.timed = timed;
            this.self = self;
        }

        /** send() closes socket (on reaper) when an answer is not written within writeDeadlineMs. */
        void guard(Socket socket, ScheduledExecutorService reaper) {
            this.socket = socket;
            this.reaper = reaper;
        }

        /** Every read from here on must be done ms from now. */
        void arm(long ms) {
            if (timed != null) timed.arm(ms, readTimeoutMs);
        }

        /** No Origin header (not a browser, or a same-origin GET), or exactly this server's own. */
        boolean sameOrigin() {
            String o = header("origin");
            return o == null || (self != null && o.equalsIgnoreCase(self));
        }

        boolean api() {
            return path != null && path.startsWith("/api/");
        }

        String header(String name) {
            return headers.get(name);
        }

        /**
         * Request line and headers. false: the client closed before sending anything. The head
         * deadline is the input's (Timed, armed by serve()): a read past it throws Late.
         */
        boolean readHead() throws IOException, Refused {
            String line;
            int blank = 0;
            do {
                line = readLine(in, MAX_REQUEST_LINE, 414, blank == 0 && method == null);
                if (line == null) return false;
            } while (line.isEmpty() && ++blank <= 2);
            String[] p = line.split(" ", -1);
            if (p.length != 3 || !p[0].matches("[A-Z]{1,16}") || !(p[2].equals("HTTP/1.1") || p[2].equals("HTTP/1.0"))) {
                throw new Refused(400, "bad-request", "Bad request.");
            }
            method = p[0];
            target = p[1];
            if (target.isEmpty() || target.charAt(0) != '/') throw new Refused(400, "bad-request", "Bad request.");
            if (target.length() > 2048) throw new Refused(414, "bad-request", "The address is too long.");
            for (int i = 0; i < target.length(); i++) {
                char c = target.charAt(i);
                if (c <= 0x20 || c >= 0x7f) throw new Refused(400, "bad-request", "Bad request.");
            }
            int q = target.indexOf('?');
            path = q >= 0 ? target.substring(0, q) : target;
            int n = 0;
            while (true) {
                line = readLine(in, MAX_HEADER_LINE, 431, false);
                if (line == null) throw new Refused(400, "bad-request", "Bad request.");
                if (line.isEmpty()) break;
                if (++n > MAX_HEADERS) throw new Refused(431, "bad-request", "Too many headers.");
                char first = line.charAt(0);
                int colon = line.indexOf(':');
                if (first == ' ' || first == '\t' || colon <= 0) throw new Refused(400, "bad-request", "Bad request.");
                String name = line.substring(0, colon);
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new Refused(400, "bad-request", "Bad request.");
                String value = line.substring(colon + 1).trim();
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if ((c < 0x20 && c != '\t') || c == 0x7f) throw new Refused(400, "bad-request", "Bad request.");
                }
                String key = name.toLowerCase(Locale.ROOT);
                if (headers.containsKey(key)) {
                    if (key.equals("content-length") || key.equals("transfer-encoding") || key.equals("content-type")
                            || key.equals("host")) throw new Refused(400, "bad-request", "Bad request.");
                    headers.put(key, headers.get(key) + ", " + value);
                } else {
                    headers.put(key, value);
                }
            }
            return true;
        }

        void send(int code, String type, byte[] body, String... extra) throws IOException {
            StringBuilder h = new StringBuilder(512);
            h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            headerLine(h, "Content-Type", type);
            headerLine(h, "Content-Length", Integer.toString(body.length));
            headerLine(h, "Cache-Control", cache);
            headerLine(h, "Referrer-Policy", "no-referrer");
            headerLine(h, "X-Content-Type-Options", "nosniff");
            headerLine(h, "X-Frame-Options", "DENY");
            headerLine(h, "Content-Security-Policy", CSP);
            headerLine(h, "Connection", "close");
            for (int i = 0; i + 1 < extra.length; i += 2) headerLine(h, extra[i], extra[i + 1]);
            h.append("\r\n");
            ScheduledFuture<?> overdue = null;
            if (socket != null && reaper != null) {
                Socket c = socket;
                try {
                    overdue = reaper.schedule(() -> quietClose(c), writeDeadlineMs, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    // stopping: stop() closes every socket anyway
                }
            }
            try {
                out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush(); // a client that stops reading blocks here until the reaper closes its socket
            } finally {
                if (overdue != null) overdue.cancel(false);
            }
            sent = true;
            status = code;
        }

        void json(int code, String body) throws IOException {
            send(code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        }

        void html(int code, String text) throws IOException {
            send(code, "text/html; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
        }

        /** The Content-Length, checked against limit before anything is read. */
        long length(long limit, String what) throws Refused {
            if (header("transfer-encoding") != null) throw new Refused(411, "length", NO_LENGTH);
            String cl = header("content-length");
            if (cl != null && cl.matches("-[0-9]+")) throw new Refused(400, "bad-request", "Bad Content-Length.");
            if (cl == null || !cl.matches("[0-9]{1,15}")) throw new Refused(411, "length", NO_LENGTH);
            long n = Long.parseLong(cl);
            if (n > limit) throw new Refused(413, "too-big", what == null ? TOO_BIG : what + " is too big.");
            return n;
        }

        byte[] body(long limit, String what) throws IOException, Refused {
            int n = (int) length(limit, what);
            byte[] b = new byte[n];
            int got = 0;
            while (got < n) {
                int r = in.read(b, got, n - got);
                if (r < 0) throw new Refused(400, "bad-request", CUT_OFF);
                got += r;
            }
            return b;
        }

        Map<String, Object> jsonBody() throws IOException, Refused {
            Object o;
            try {
                o = UserLibrary.Json.parse(new String(body(MAX_JSON, "The request"), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new Refused(400, "bad-request", "Expected JSON.");
            }
            if (!(o instanceof Map)) throw new Refused(400, "bad-request", "Expected a JSON object.");
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            return m;
        }
    }

    /** One header line; a value that could end the header (CR, LF, any control) is refused. */
    static void headerLine(StringBuilder h, String name, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c >= 0x7f) throw new IllegalArgumentException("unsafe header value");
        }
        h.append(name).append(": ").append(value).append("\r\n");
    }

    /**
     * One CRLF (or LF) terminated line of at most max bytes, as ISO-8859-1, without its ending; null
     * at a clean end of stream when allowEof. A bare CR or a NUL inside the line is refused.
     */
    static String readLine(InputStream in, int max, int tooLong, boolean allowEof) throws IOException, Refused {
        StringBuilder b = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (allowEof && b.length() == 0) return null;
                throw new Refused(400, "bad-request", "Bad request.");
            }
            if (c == '\n') break;
            if (b.length() >= max) throw new Refused(tooLong, "bad-request", "The request is too big.");
            b.append((char) c);
        }
        int n = b.length();
        if (n > 0 && b.charAt(n - 1) == '\r') b.setLength(n - 1);
        for (int i = 0; i < b.length(); i++) {
            if (b.charAt(i) == '\r' || b.charAt(i) == 0) throw new Refused(400, "bad-request", "Bad request.");
        }
        return b.toString();
    }

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 414: return "URI Too Long";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 507: return "Insufficient Storage";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return "Internal Server Error";
        }
    }

    static String err(String code, String message) {
        return new UserLibrary.Json.W().beginObject().key("ok").value(false).key("error").value(code).key("message").value(message)
                .endObject().toString();
    }

    static String escapeHtml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#x27;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private void serve(Socket c, ScheduledExecutorService r) {
        Exchange x = null;
        try {
            c.setTcpNoDelay(true);
            if (sendBufferBytes > 0) c.setSendBufferSize(sendBufferBytes);
            Timed timed = new Timed(c);
            timed.arm(headDeadlineMs, readTimeoutMs);
            x = new Exchange(new BufferedInputStream(timed, 16 * 1024), new BufferedOutputStream(c.getOutputStream(), 16 * 1024),
                    timed, "http://" + c.getLocalAddress().getHostAddress() + ":" + c.getLocalPort());
            x.client = c.getInetAddress();
            x.guard(c, r);
            try {
                if (!x.readHead()) return;
                x.arm(bodyDeadlineMs); // a small body; upload() gives a photo's body its own
                route(x);
            } catch (Refused e) {
                refuse(x, e);
            } catch (Late e) {
                refuse(x, new Refused(408, "timeout", TOO_SLOW));
            } catch (SocketTimeoutException e) {
                return; // a stalled client: dropped
            } catch (IOException e) {
                return; // the client went away
            } catch (RuntimeException e) {
                if (!x.sent) x.json(500, err("internal", INTERNAL));
            }
            lingeringClose(c, x);
        } catch (IOException | RuntimeException e) {
            // nothing more to say to this client
        } finally {
            if (x != null) log((x.method == null ? "-" : x.method) + " " + x.route + " " + (x.sent ? x.status : 0));
        }
    }

    private static void refuse(Exchange x, Refused e) throws IOException {
        if (x.sent) return;
        if (x.api()) x.json(e.status, err(e.code, e.getMessage()));
        else x.html(e.status, String.format(Locale.ROOT, EXPIRED_PAGE, escapeHtml(e.getMessage())));
    }

    /**
     * Answer sent: let the client read it before the connection goes (unread body bytes would reset
     * it). At most 256 KB, LINGER_MS in all, LINGER_READ_MS per read; a 503 takes any body the upload
     * limit allows in that time, since the phone page retries it and must be able to read it.
     */
    private void lingeringClose(Socket c, Exchange x) {
        try {
            c.shutdownOutput();
            if (x.timed != null) x.timed.arm(lingerMs, lingerReadMs);
            byte[] sink = new byte[8192];
            long left = x.status == 503 ? MAX_UPLOAD + MULTIPART_SLACK : 256 * 1024;
            while (left > 0) {
                int n = x.in.read(sink, 0, (int) Math.min(sink.length, left));
                if (n < 0) break;
                left -= n;
            }
        } catch (IOException e) {
            // closing anyway
        }
    }

    // ------------------------------------------------------------------ routes

    private void route(Exchange x) throws IOException, Refused {
        String p = x.path, m = x.method;
        if (!m.equals("GET") && !m.equals("POST") && !m.equals("PATCH") && !m.equals("DELETE")) {
            x.route = "?";
            throw new Refused(501, "method", "Unsupported method.");
        }
        if (!m.equals("GET") && !x.sameOrigin()) {
            x.route = "origin"; // before anything is read or counted: no wrong code from another site's page
            throw new Refused(403, "origin", CROSS_ORIGIN);
        }
        if (p.equals("/")) {
            x.route = "/";
            if (m.equals("GET")) {
                x.html(200, String.format(Locale.ROOT, CODE_PAGE, ""));
                return;
            }
            if (m.equals("POST")) {
                code(x);
                return;
            }
        } else if (p.startsWith("/static/") && m.equals("GET")) {
            x.route = "/static";
            String name = p.substring("/static/".length());
            if (STATIC.containsKey(name)) {
                x.send(200, STATIC.get(name), statics.get(name));
                return;
            }
        } else if (p.startsWith("/u/") && m.equals("GET")) {
            x.route = "/u";
            try {
                auth(p.substring(3));
            } catch (Refused e) {
                x.html(403, String.format(Locale.ROOT, EXPIRED_PAGE, escapeHtml(e.getMessage())));
                return;
            }
            x.send(200, "text/html; charset=utf-8", statics.get("index.html"));
            return;
        } else if (p.startsWith("/api/")) {
            x.route = "/api";
            api(x);
            return;
        }
        throw new Refused(404, "not-found", "Not found.");
    }

    /** The live token record (UserLibrary.currentToken()) if token is it, else 403 expired. */
    Map<String, Object> auth(String token) throws Refused {
        Map<String, Object> t = lib.currentToken();
        if (t == null || token == null || !TOKEN.matcher(token).matches()
                || !MessageDigest.isEqual(((String) t.get("token")).getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII))) {
            throw new Refused(403, "expired", EXPIRED);
        }
        return t;
    }

    private void code(Exchange x) throws IOException, Refused {
        String code;
        Map<String, Object> t;
        try {
            code = formField(new String(x.body(MAX_JSON, "The request"), StandardCharsets.UTF_8), "code").trim();
            t = checkCode(code, x.client);
        } catch (Refused e) {
            if (e.status == 403 || e.status == 429) {
                x.html(e.status, String.format(Locale.ROOT, CODE_PAGE,
                        "<p class=\"error\" role=\"alert\">" + escapeHtml(e.getMessage()) + "</p>"));
                return;
            }
            throw e;
        }
        x.send(303, "text/plain", new byte[0], "Location", "/u/" + t.get("token"));
    }

    /**
     * At most CODE_FAILS_PER_MIN wrong codes a minute from one client address, and CODE_FAILS_ALL_PER_MIN
     * from all of them together. Review 999ba07 (L2): one limit for everyone let any device on the LAN keep
     * every phone at "Too many wrong codes"; the ceiling for all still bounds guessing from many addresses.
     * The code is compared first (constant time): an address over its own limit is refused even when
     * right, but the ceiling for all refuses only wrong codes, so a phone typing the right code is never
     * kept out by guesses from other addresses (review 30e7356, A3: the ceiling answered 429 before the
     * comparison). The trade: past the ceiling a guess still learns right (303) from wrong (429), so
     * EVERY compared wrong code counts, past the ceiling too (review 7c2b1e5: past it a wrong code from
     * an address with no record was answered 429 without being recorded, so a fresh address guessed
     * without limit): against its address (CODE_FAILS_PER_MIN a minute, then refused even when right)
     * and towards a new code at the next renewal (UserLibrary.codeWrong(), CODE_ROTATE_AFTER).
     * Memory stays bounded: at most CODE_CLIENTS_MAX addresses are held, each with at most
     * CODE_FAILS_PER_MIN times (an address at its limit is refused before anything is recorded), and
     * every check drops what is over a minute old. While CODE_CLIENTS_MAX addresses are held, an
     * address with no record is refused like one over its limit (so guessing never gets cheaper by
     * filling the table).
     */
    Map<String, Object> checkCode(String code, InetAddress client) throws Refused {
        Map<String, Object> t = lib.currentToken();
        boolean ok = t != null && code != null && CODE.matcher(code).matches()
                && MessageDigest.isEqual(((String) t.get("code")).getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII));
        long now = clock.getAsLong();
        boolean overCeiling = false;
        synchronized (codeFails) {
            expire(codeFails, now);
            clientFails.values().removeIf(q -> expire(q, now));
            ArrayDeque<Long> mine = clientFails.get(client);
            if (mine != null ? mine.size() >= CODE_FAILS_PER_MIN : clientFails.size() >= CODE_CLIENTS_MAX) {
                throw new Refused(429, "slow-down", SLOW_DOWN);
            }
            if (!ok) {
                overCeiling = codeFails.size() >= CODE_FAILS_ALL_PER_MIN;
                codeFails.addLast(now);
                if (mine == null) clientFails.put(client, mine = new ArrayDeque<>());
                mine.addLast(now);
            }
        }
        if (ok) return t;
        if (t != null) lib.codeWrong(); // towards a new code at the next renewal (A4), past the ceiling too
        if (overCeiling) throw new Refused(429, "slow-down", SLOW_DOWN);
        if (t == null) throw new Refused(403, "no-code", NO_CODE);
        throw new Refused(403, "wrong-code", WRONG_CODE);
    }

    /** Drops the times over a minute old (or ahead of now: the clock went back); true if none is left. */
    private static boolean expire(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && (now - q.peekFirst() > 60_000 || now < q.peekFirst())) q.pollFirst();
        return q.isEmpty();
    }

    /** How many client addresses the code rate limit holds (tests: it stays bounded). */
    int codeClients() {
        synchronized (codeFails) {
            return clientFails.size();
        }
    }

    /** The first non-empty value of key in an application/x-www-form-urlencoded body, or "". */
    static String formField(String body, String key) {
        for (String pair : body.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (key.equals(urlDecode(pair.substring(0, eq)))) {
                String v = urlDecode(pair.substring(eq + 1));
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    static String urlDecode(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                b.write(' ');
            } else if (c == '%' && i + 2 < s.length()
                    && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) {
                b.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else {
                byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                b.write(u, 0, u.length);
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private void api(Exchange x) throws IOException, Refused {
        String[] parts = x.path.split("/", -1); // "", "api", token, ...
        if (parts.length < 4) throw new Refused(404, "not-found", "Not found.");
        String token = parts[2], m = x.method;
        int n = parts.length - 3;
        String r0 = parts[3];
        Map<String, Object> tok = auth(token);
        if (n == 1 && r0.equals("state") && m.equals("GET")) {
            x.route = "/api/state";
            x.json(200, state(tok));
        } else if (n == 1 && r0.equals("photos") && m.equals("POST")) {
            x.route = "/api/photos";
            x.json(200, upload(x, tok));
        } else if (n == 3 && r0.equals("photos") && m.equals("DELETE")) {
            x.route = "/api/photos/delete";
            deletePhoto(parts[4], parts[5]);
            x.json(200, UserLibrary.ok());
        } else if (n == 1 && r0.equals("groups") && m.equals("POST")) {
            x.route = "/api/groups";
            x.json(200, createGroup(x.jsonBody().get("name")));
        } else if (n == 2 && r0.equals("groups") && m.equals("PATCH")) {
            x.route = "/api/groups/rename";
            x.json(200, renameGroup(parts[4], x.jsonBody().get("name")));
        } else if (n == 3 && r0.equals("thumbs") && m.equals("GET") && parts[5].endsWith(".jpg")) {
            x.route = "/api/thumbs";
            String gid = parts[4], pid = parts[5].substring(0, parts[5].length() - 4);
            if (!UserLibrary.validGid(gid) || !UserLibrary.validPid(pid)) throw new Refused(400, "bad-id", UNKNOWN_PHOTO);
            File f = lib.photoFile(gid + "/" + pid + "/thumb.jpg");
            if (f == null || f.length() > MAX_THUMB) throw new Refused(404, "not-found", "Not found.");
            byte[] thumb = Files.readAllBytes(f.toPath());
            x.cache = THUMB_CACHE;
            x.send(200, "image/jpeg", thumb);
        } else {
            throw new Refused(404, "not-found", "Not found.");
        }
    }

    private String state(Map<String, Object> tok) {
        List<UserLibrary.Group> gs;
        List<UserLibrary.Photo> ps;
        List<PendingPhotos.Job> jobs;
        synchronized (changes) {
            UserLibrary.Listing l = lib.listing(); // cached between changes (review 006cfe9: the phone polls every 2 s)
            gs = l.groups;
            ps = l.photos;
            jobs = pending.jobs();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (UserLibrary.Photo p : ps) counts.merge(p.group, 1, Integer::sum);
        String token = (String) tok.get("token");
        UserLibrary.Json.W w = new UserLibrary.Json.W().beginObject().key("ok").value(true).key("v").value(1)
                .key("group").value((String) tok.get("group")).key("expires").value((Long) tok.get("expires"))
                .key("now").value(clock.getAsLong()).key("groups").beginArray();
        for (UserLibrary.Group g : gs) {
            w.beginObject().key("id").value(g.id).key("name").value(g.name).key("builtin").value(g.builtin)
                    .key("count").value(counts.getOrDefault(g.id, 0)).endObject();
        }
        w.endArray().key("photos").beginArray();
        for (UserLibrary.Photo p : ps) {
            w.beginObject().key("group").value(p.group).key("id").value(p.id).key("name").value(p.name).key("added").value(p.added)
                    .key("thumb").value("/api/" + token + "/thumbs/" + p.group + "/" + p.id + ".jpg").endObject();
        }
        w.endArray().key("pending").beginArray();
        for (PendingPhotos.Job j : jobs) {
            w.beginObject().key("group").value(j.gid).key("id").value(j.pid).key("name").value(j.name).key("added").value(j.added).endObject();
        }
        return w.endArray().key("limits").beginObject().key("groups").value(UserLibrary.MAX_GROUPS)
                .key("perGroup").value(UserLibrary.MAX_PHOTOS_PER_GROUP).key("total").value(UserLibrary.MAX_PHOTOS)
                .key("bytes").value(MAX_UPLOAD).key("megapixels").value(PhotoMath.MAX_PIXELS / 1_000_000)
                .key("name").value(UserLibrary.NAME_MAX).endObject().endObject().toString();
    }

    /** Refused unless gid is a listed group with room (unfinished photos count against the limits). */
    private void roomFor(String gid) throws Refused {
        if (!UserLibrary.validGid(gid)) throw new Refused(400, "bad-id", UNKNOWN_GROUP);
        if (!lib.groupExists(gid)) throw new Refused(404, "no-group", NO_GROUP);
        int here = lib.photos(gid).size() + pending.count(gid);
        if (here >= UserLibrary.MAX_PHOTOS_PER_GROUP) throw new Refused(409, "group-full", GROUP_FULL);
        int all = pending.count(null);
        for (UserLibrary.Group g : lib.groups()) all += lib.photos(g.id).size();
        if (all >= UserLibrary.MAX_PHOTOS) throw new Refused(409, "tv-full", TV_FULL);
    }

    private String upload(Exchange x, Map<String, Object> tok) throws IOException, Refused {
        long n = x.length(MAX_UPLOAD + MULTIPART_SLACK, null);
        String boundary = boundary(x.header("content-type"));
        if (!uploads.tryAcquire()) throw new Refused(503, "busy", BUSY);
        File part = null;
        try {
            if (boundary == null) throw new Refused(400, "bad-request", NOT_MULTIPART);
            x.arm(uploadDeadlineMs);
            Form form;
            Spool photo;
            try {
                makeDir(tmpDir);
                part = partFiles.create(tmpDir);
                photo = new Spool(partFiles.open(part));
            } catch (IOException e) {
                photo = new Spool(e); // no room even to begin: the body is still read, so the 507 reaches the phone
            }
            try {
                form = parseMultipart(new Bounded(x.in, n), boundary, photo, MAX_UPLOAD);
            } finally {
                photo.close();
            }
            if (photo.failed != null) throw new Refused(507, "storage-full", STORAGE_FULL); // the temp file is removed below
            if (!form.photo) throw new Refused(400, "no-photo", NO_PHOTO);
            String gid = form.group != null && !form.group.isEmpty() ? form.group : (String) tok.get("group");
            if (!UserLibrary.validGid(gid)) throw new Refused(400, "bad-id", UNKNOWN_GROUP);
            if (form.photoBytes == 0) throw new Refused(400, "no-photo", NO_PHOTO);
            PhotoMath.Crop crop = form.crop == null ? null : PhotoMath.parseCrop(form.crop);
            if (form.crop != null && crop == null) throw new Refused(400, "bad-crop", BAD_CROP);
            String pid;
            synchronized (changes) {
                roomFor(gid);
                try {
                    pid = pending.create(gid);
                } catch (IOException e) {
                    if (!lib.groupExists(gid)) throw new Refused(404, "no-group", NO_GROUP);
                    throw new Refused(507, "storage-full", STORAGE_FULL); // it could not make the photo's folder
                }
            }
            String name = PhotoMath.photoName(form.filename);
            long added = clock.getAsLong();
            File dir = pending.tmpDir(gid, pid).toFile();
            boolean ok = false;
            try {
                processor.process(part, dir, pid, name, added, crop);
                for (String f : new String[]{"wall.jpg", "thumb.jpg", "meta.json"}) {
                    if (!new File(dir, f).isFile()) throw new IOException("processor wrote no " + f);
                }
                ok = true;
            } catch (PhotoProcessor.Refused e) {
                int status = e.code.equals("type") ? 415 : e.code.equals("pixels") ? 413 : 422;
                throw new Refused(status, e.code, e.getMessage());
            } catch (IOException | RuntimeException e) {
                throw new Refused(500, "prepare", PREPARE);
            } finally {
                if (!ok) pending.discard(gid, pid);
            }
            try {
                if (onQueued != null) onQueued.run();
            } catch (RuntimeException e) {
                // the page also asks for jobs when it loads
            }
            return new UserLibrary.Json.W().beginObject().key("ok").value(true).key("photo").beginObject()
                    .key("group").value(gid).key("id").value(pid).key("name").value(name).key("added").value(added)
                    .key("pending").value(true).endObject().endObject().toString();
        } finally {
            uploads.release();
            if (part != null && !part.delete()) part.deleteOnExit();
        }
    }

    /**
     * d exists as a directory afterwards, or IOException. mkdirs() first: it is false when d already
     * exists, including when another upload made it a moment ago. The old check-then-make
     * (`!isDirectory() && !mkdirs()`) refused the loser of that race, and two first uploads at once
     * dropped one connection without an answer.
     */
    static void makeDir(File d) throws IOException {
        if (!d.mkdirs() && !d.isDirectory()) throw new IOException("no temp dir");
    }

    private void deletePhoto(String gid, String pid) throws Refused {
        if (!UserLibrary.validGid(gid) || !UserLibrary.validPid(pid)) throw new Refused(400, "bad-id", UNKNOWN_PHOTO);
        synchronized (changes) {
            boolean there = false;
            for (UserLibrary.Photo p : lib.photos(gid)) there |= p.id.equals(pid);
            if (!there) throw new Refused(404, "no-photo", GONE_PHOTO);
            String r = lib.deletePhoto(gid, pid);
            if (!r.equals(UserLibrary.ok())) throw new Refused(500, "internal", INTERNAL);
        }
    }

    private String createGroup(Object name) throws Refused {
        String n = name instanceof String ? UserLibrary.cleanName((String) name) : null;
        if (n == null) throw new Refused(400, "bad-name", BAD_NAME);
        String gid;
        synchronized (changes) {
            if (lib.groups().size() - UserLibrary.BUILTINS.length >= UserLibrary.MAX_GROUPS) throw new Refused(409, "groups-full", GROUPS_FULL);
            Object r = UserLibrary.Json.parse(lib.addGroup(n));
            Object id = r instanceof Map ? ((Map<?, ?>) r).get("id") : null;
            if (!(id instanceof String)) throw new Refused(500, "internal", INTERNAL);
            gid = (String) id;
        }
        return groupJson(gid, n, 0);
    }

    private String renameGroup(String gid, Object name) throws Refused {
        if (!UserLibrary.validGid(gid)) throw new Refused(400, "bad-id", UNKNOWN_GROUP);
        if (UserLibrary.isBuiltin(gid)) throw new Refused(400, "builtin", BUILTIN);
        String n = name instanceof String ? UserLibrary.cleanName((String) name) : null;
        if (n == null) throw new Refused(400, "bad-name", BAD_NAME);
        int count;
        synchronized (changes) {
            if (lib.readGroup(gid) == null) throw new Refused(404, "no-group", NO_GROUP);
            if (!lib.renameGroup(gid, n).equals(UserLibrary.ok())) throw new Refused(500, "internal", INTERNAL);
            count = lib.photos(gid).size();
        }
        return groupJson(gid, n, count);
    }

    private static String groupJson(String gid, String name, int count) {
        return new UserLibrary.Json.W().beginObject().key("ok").value(true).key("group").beginObject().key("id").value(gid)
                .key("name").value(name).key("builtin").value(false).key("count").value(count).endObject().endObject().toString();
    }

    // ------------------------------------------------------------------ multipart

    /** The boundary of a multipart/form-data Content-Type, or null. */
    static String boundary(String contentType) {
        if (contentType == null) return null;
        int semi = contentType.indexOf(';');
        String type = (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        if (!type.equals("multipart/form-data") || semi < 0) return null;
        String b = params(contentType.substring(semi + 1)).get("boundary");
        return b != null && BOUNDARY.matcher(b).matches() ? b : null;
    }

    /** name=value parameters after a header's first ';' (names lower-cased; quoted strings unescaped). */
    static Map<String, String> params(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (s.charAt(i) == ';' || s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            int eq = s.indexOf('=', i);
            int semi = s.indexOf(';', i);
            if (eq < 0 || (semi >= 0 && semi < eq)) { // a bare token: skipped
                i = semi < 0 ? n : semi + 1;
                continue;
            }
            String name = s.substring(i, eq).trim().toLowerCase(Locale.ROOT);
            i = eq + 1;
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            StringBuilder v = new StringBuilder();
            if (i < n && s.charAt(i) == '"') {
                i++;
                while (i < n && s.charAt(i) != '"') {
                    char c = s.charAt(i++);
                    if (c == '\\' && i < n) c = s.charAt(i++);
                    v.append(c);
                }
                i++; // the closing quote
                while (i < n && s.charAt(i) != ';') i++;
            } else {
                int end = s.indexOf(';', i);
                if (end < 0) end = n;
                v.append(s.substring(i, end).trim());
                i = end;
            }
            if (!name.isEmpty() && !out.containsKey(name)) out.put(name, v.toString());
        }
        return out;
    }

    /** What the upload form held: the group and crop fields, and whether a photo part came (its name and size). */
    static final class Form {
        String group, filename, crop;
        boolean photo;
        long photoBytes;
    }

    /** An InputStream that ends after n bytes (the request body). */
    static final class Bounded extends InputStream {
        private final InputStream in;
        private long left;

        Bounded(InputStream in, long n) {
            this.in = in;
            this.left = n;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int c = in.read();
            if (c >= 0) left--;
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }

    private static final class TooBig extends Exception {
    }

    private static final class Cut extends Exception {
    }

    /**
     * Streaming multipart/form-data (RFC 7578): the first "photo" part goes to photoOut (at most
     * maxPhoto bytes, else 413), the first "group" and "crop" parts are kept (at most MAX_FIELD bytes), anything
     * else is read and dropped. Holds at most one buffer (64 KB) in memory, so a delimiter split
     * across reads is still found. The body must end with the close delimiter.
     */
    static Form parseMultipart(InputStream body, String boundary, OutputStream photoOut, long maxPhoto) throws IOException, Refused {
        Scanner sc = new Scanner(body, ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
        Form f = new Form();
        try {
            sc.copyToDelimiter(null, MAX_PREAMBLE);
        } catch (Cut | TooBig e) {
            throw new Refused(400, "bad-request", NOT_MULTIPART);
        }
        Set<String> seen = new HashSet<>();
        int parts = 0;
        try {
            while (true) {
                int a = sc.read(), b = sc.read();
                if (a == '-' && b == '-') return f; // close delimiter; the epilogue is ignored
                while (a == ' ' || a == '\t') {
                    a = b;
                    b = sc.read();
                }
                if (a < 0 || b < 0) throw new Cut();
                if (a != '\r' || b != '\n') throw new Refused(400, "bad-request", MALFORMED);
                if (++parts > MAX_PARTS) throw new Refused(400, "bad-request", MALFORMED);
                Map<String, String> hs = sc.partHeaders();
                String cd = hs.get("content-disposition");
                String name = null, filename = null;
                if (cd != null) {
                    int semi = cd.indexOf(';');
                    String type = (semi < 0 ? cd : cd.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
                    if (type.equals("form-data") && semi >= 0) {
                        Map<String, String> ps = params(cd.substring(semi + 1));
                        name = ps.get("name");
                        filename = ps.get("filename");
                    }
                }
                boolean first = name != null && seen.add(name);
                if (first && name.equals("photo")) {
                    f.photo = true;
                    f.filename = filename;
                    try {
                        f.photoBytes = sc.copyToDelimiter(photoOut, maxPhoto);
                    } catch (TooBig e) {
                        throw new Refused(413, "too-big", TOO_BIG);
                    }
                } else if (first && (name.equals("group") || name.equals("crop"))) {
                    ByteArrayOutputStream g = new ByteArrayOutputStream();
                    try {
                        sc.copyToDelimiter(g, MAX_FIELD);
                    } catch (TooBig e) {
                        throw new Refused(400, "bad-request", MALFORMED);
                    }
                    String v = new String(g.toByteArray(), StandardCharsets.US_ASCII).trim();
                    if (name.equals("group")) f.group = v;
                    else f.crop = v;
                } else {
                    try {
                        sc.copyToDelimiter(null, Long.MAX_VALUE);
                    } catch (TooBig e) {
                        throw new Refused(400, "bad-request", MALFORMED);
                    }
                }
            }
        } catch (Cut e) {
            throw new Refused(400, "bad-request", CUT_OFF);
        }
    }

    /** The body as a byte stream with a delimiter search that survives any split across reads. */
    private static final class Scanner {
        private final InputStream in;
        private final byte[] delim;
        private final byte[] buf = new byte[64 * 1024];
        private int pos, lim;
        private boolean eof;

        Scanner(InputStream in, byte[] delim) {
            this.in = in;
            this.delim = delim;
            buf[0] = '\r'; // the body starts right after a virtual CRLF, so "--boundary" there is a delimiter
            buf[1] = '\n';
            lim = 2;
        }

        private boolean more() throws IOException {
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, lim - pos);
                lim -= pos;
                pos = 0;
            }
            if (eof || lim == buf.length) return false;
            int n = in.read(buf, lim, buf.length - lim);
            if (n < 0) {
                eof = true;
                return false;
            }
            lim += n;
            return true;
        }

        int read() throws IOException {
            while (pos >= lim) if (!more()) return -1;
            return buf[pos++] & 0xff;
        }

        private int indexOf() {
            int last = lim - delim.length;
            outer:
            for (int i = pos; i <= last; i++) {
                if (buf[i] != delim[0]) continue;
                for (int k = 1; k < delim.length; k++) if (buf[i + k] != delim[k]) continue outer;
                return i;
            }
            return -1;
        }

        /** Copies up to the next delimiter (consumed) into sink (null: dropped); its byte count. */
        long copyToDelimiter(OutputStream sink, long limit) throws IOException, TooBig, Cut {
            long total = 0;
            while (true) {
                int i = indexOf();
                if (i >= 0) {
                    total = emit(sink, i - pos, total, limit);
                    pos = i + delim.length;
                    return total;
                }
                int safe = Math.max(pos, lim - (delim.length - 1));
                total = emit(sink, safe - pos, total, limit);
                pos = safe;
                if (!more()) throw new Cut();
            }
        }

        private long emit(OutputStream sink, int len, long total, long limit) throws IOException, TooBig {
            if (total + len > limit) throw new TooBig();
            if (sink != null && len > 0) sink.write(buf, pos, len);
            return total + len;
        }

        /** A part's header lines up to the blank line (names lower-cased, values UTF-8), at most MAX_PART_HEADERS bytes. */
        Map<String, String> partHeaders() throws IOException, Refused, Cut {
            Map<String, String> out = new LinkedHashMap<>();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int total = 0;
            String last = null;
            while (true) {
                int c = read();
                if (c < 0) throw new Cut();
                if (++total > MAX_PART_HEADERS) throw new Refused(400, "bad-request", MALFORMED);
                if (c != '\n') {
                    line.write(c);
                    continue;
                }
                byte[] l = line.toByteArray();
                line.reset();
                int len = l.length > 0 && l[l.length - 1] == '\r' ? l.length - 1 : l.length;
                String s = new String(l, 0, len, StandardCharsets.UTF_8);
                if (s.isEmpty()) return out;
                if ((s.charAt(0) == ' ' || s.charAt(0) == '\t') && last != null) {
                    out.put(last, out.get(last) + " " + s.trim());
                    continue;
                }
                int colon = s.indexOf(':');
                if (colon <= 0) throw new Refused(400, "bad-request", MALFORMED);
                last = s.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (!out.containsKey(last)) out.put(last, s.substring(colon + 1).trim());
            }
        }
    }
}
