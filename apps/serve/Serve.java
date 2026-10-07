///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms16m -Xmx256m -XX:CICompilerCount=2 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback -H:IncludeResourceBundles=sun.net.httpserver.simpleserver.resources.simpleserver

package serve;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Lightweight HTTP file server utility inspired by `python -m http.server`.
///
/// Built using Java's built-in `SimpleFileServer` and `HttpServer` APIs.
@Command(name = "serve", mixinStandardHelpOptions = true, version = "serve 2.0", description = "Simple HTTP file server inspired by python -m http.server")
@SuppressWarnings("unused")
class Serve implements Callable<Integer> {

	@Option(names = { "-p", "--port" }, description = "Port to listen on (default: 8080)")
	private Integer port;

	@Option(names = { "-d", "--directory" }, description = "Directory to serve (default: current directory)")
	private Path directory;

	@Option(names = { "-b", "--bind" }, description = "Address to bind to (default: 0.0.0.0)")
	private String bind = "0.0.0.0";

	@Option(names = { "-v", "--verbose" }, description = "Enable verbose request logging")
	private boolean verbose;

	@Option(names = { "-a",
			"--download" }, description = "Force browser to download files instead of displaying inline")
	private boolean download;

	@Option(names = { "--spa" }, description = "Single Page Application mode: fallback 404 requests to index.html")
	private boolean spaMode;

	@Option(names = { "-r",
			"--live-reload" }, description = "Enable live reload: auto-refresh browser on file changes")
	private boolean liveReload;

	@Option(names = { "--auth" }, description = "HTTP Basic Authentication credentials (format: user:password)")
	private String authCredentials;

	@Parameters(arity = "0..2", paramLabel = "[dirOrPort]", description = "Optional directory path and/or port number")
	private List<String> positionalArgs = new ArrayList<>();

	private HttpServer server;
	private volatile Thread serverThread;
	private LiveReloadManager liveReloadManager;

	public void stop() {
		if (liveReloadManager != null) {
			liveReloadManager.close();
		}
		if (server != null) {
			server.stop(0);
		}
		if (serverThread != null) {
			serverThread.interrupt();
		}
	}

	/// Helper method checking whether a string represents a valid integer.
	///
	/// @param s String to check.
	/// @return `true` if string can be parsed as an integer, `false` otherwise.
	private static boolean isInteger(String s) {
		try {
			Integer.parseInt(s);
			return true;
		} catch (NumberFormatException _) {
			return false;
		}
	}

	/// Main entry point for the JBang script execution.
	///
	/// @param args Command-line arguments.
	void main(String... args) {
		var exitCode = new CommandLine(this).execute(args);
		System.exit(exitCode);
	}

	/// Resolves arguments, validates directory and port parameters, and launches
	/// the file server.
	///
	/// @return Status code 0 for success, 1 for errors.
	@SuppressWarnings("HttpUrlsUsage")
	@Override
	public Integer call() {
		if (!positionalArgs.isEmpty() && (directory != null || port != null)) {
			System.err
				.println("Error: Positional directory/port arguments cannot be combined with --directory or --port.");
			return 1;
		}

		resolveArguments();

		if (!Files.exists(directory)) {
			System.err.printf("Error: Directory '%s' does not exist.%n", directory);
			return 1;
		}
		if (!Files.isDirectory(directory)) {
			System.err.printf("Error: Path '%s' is not a directory.%n", directory);
			return 1;
		}

		if (port < 1 || port > 65535) {
			System.err.printf("Error: Invalid port %d. Port must be between 1 and 65535.%n", port);
			return 1;
		}

		if (authCredentials != null && (authCredentials.indexOf(':') < 1
				|| authCredentials.indexOf(':') == authCredentials.length() - 1)) {
			System.err.println("Error: --auth requires non-empty user:password credentials.");
			return 1;
		}

		var absDir = directory.toAbsolutePath().normalize();
		var addr = new InetSocketAddress(bind, port);

		// Initialize server instance

		try {
			HttpHandler fileHandler = SimpleFileServer.createFileHandler(absDir);

			HttpHandler finalHandler;
			if (liveReload || spaMode) {
				finalHandler = exchange -> {
					var method = exchange.getRequestMethod();
					if (!"GET".equals(method) && !"HEAD".equals(method)) {
						fileHandler.handle(exchange);
						return;
					}

					var reqPath = exchange.getRequestURI().getPath();
					var relPath = reqPath.startsWith("/") ? reqPath.substring(1) : reqPath;
					var targetFile = absDir.resolve(relPath).normalize();

					if (!targetFile.startsWith(absDir)) {
						fileHandler.handle(exchange);
						return;
					}

					Path htmlFile = null;
					if (liveReload && Files.isRegularFile(targetFile)
							&& (relPath.endsWith(".html") || relPath.endsWith(".htm"))) {
						htmlFile = targetFile;
					} else if (liveReload && Files.isDirectory(targetFile)) {
						if (Files.isRegularFile(targetFile.resolve("index.html"))) {
							htmlFile = targetFile.resolve("index.html");
						} else if (Files.isRegularFile(targetFile.resolve("index.htm"))) {
							htmlFile = targetFile.resolve("index.htm");
						}
					} else if (spaMode && !Files.exists(targetFile)
							&& Files.isRegularFile(absDir.resolve("index.html"))) {
						htmlFile = absDir.resolve("index.html");
					}

					if (htmlFile != null) {
						if (Files.isDirectory(targetFile) && !reqPath.endsWith("/")) {
							var query = exchange.getRequestURI().getRawQuery();
							var loc = query != null ? reqPath + "/?" + query : reqPath + "/";
							exchange.getResponseHeaders().set("Location", loc);
							exchange.sendResponseHeaders(301, -1);
							return;
						}
						var rawBytes = Files.readAllBytes(htmlFile);
						var bytes = liveReload ? injectLiveReloadScript(rawBytes) : rawBytes;
						exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
						if ("HEAD".equals(method)) {
							exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
							exchange.sendResponseHeaders(200, -1);
						} else {
							exchange.sendResponseHeaders(200, bytes.length);
							try (var os = exchange.getResponseBody()) {
								os.write(bytes);
							}
						}
						return;
					}

					fileHandler.handle(exchange);
				};
			} else {
				finalHandler = fileHandler;
			}

			var logFilter = createLoggingFilter();
			server = HttpServer.create(addr, 0);
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

			var context = server.createContext("/", finalHandler);
			context.getFilters().add(logFilter);
			Filter authFilter = null;
			if (authCredentials != null) {
				var expectedAuth = "Basic "
						+ Base64.getEncoder().encodeToString(authCredentials.getBytes(StandardCharsets.UTF_8));
				authFilter = new Filter() {
					@Override
					public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
						var authHeader = exchange.getRequestHeaders().getFirst("Authorization");
						if (authHeader == null || !authHeader.equals(expectedAuth)) {
							exchange.getResponseHeaders()
								.set("WWW-Authenticate",
										"Basic realm=\"Access to serve\"");
							exchange.sendResponseHeaders(401, -1);
							return;
						}
						chain.doFilter(exchange);
					}

					@Override
					public String description() {
						return "Basic Auth Filter";
					}
				};
				context.getFilters().add(authFilter);
			}

			if (liveReload) {
				liveReloadManager = new LiveReloadManager(absDir, verbose);
				liveReloadManager.start();

				var sseContext = server.createContext("/__serve_live_reload", exchange -> {
					if (!"GET".equals(exchange.getRequestMethod())) {
						exchange.sendResponseHeaders(405, -1);
						return;
					}
					var headers = exchange.getResponseHeaders();
					headers.set("Content-Type", "text/event-stream; charset=utf-8");
					headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
					headers.set("Connection", "keep-alive");
					headers.set("Access-Control-Allow-Origin", "*");

					exchange.sendResponseHeaders(200, 0);
					var os = exchange.getResponseBody();
					try {
						os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
						os.flush();
					} catch (IOException _) {
						return;
					}

					liveReloadManager.handleClient(exchange, os);
				});
				sseContext.getFilters().add(logFilter);
				if (authFilter != null) {
					sseContext.getFilters().add(authFilter);
				}
			}

			if (download) {
				Filter downloadFilter = new Filter() {
					@Override
					public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
						exchange.getResponseHeaders().set("Content-Disposition", "attachment");
						chain.doFilter(exchange);
					}

					@Override
					public String description() {
						return "Force Download Filter";
					}
				};
				context.getFilters().add(downloadFilter);
			}
		} catch (Exception e) {
			System.err.printf("Error creating file server: %s%n", e.getMessage());
			return 1;
		}

		var displayHost = "0.0.0.0".equals(bind) || "::".equals(bind) ? "localhost" : bind;

		System.out.printf("Serving HTTP on %s port %d (http://%s:%d/) ...%n", bind, port, displayHost,
				port);
		System.out.printf("Document root: %s%n", absDir);

		if (download) {
			System.out.println("Mode: Force download enabled (Content-Disposition: attachment)");
		}
		if (spaMode) {
			System.out.println("Mode: Single Page Application (SPA) fallback to index.html enabled");
		}
		if (liveReload) {
			System.out.println("Mode: Live reload enabled (auto-refresh browser on file changes)");
		}
		if (authCredentials != null) {
			System.out.println("Auth: Basic Authentication enabled");
		}

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			System.out.println("\nStopping server...");
			stop();
		}));

		try {
			server.start();
		} catch (UncheckedIOException e) {
			if (e.getCause() instanceof BindException) {
				System.err.printf("Error: Could not bind to port %d on %s (Address already in use).%n",
						port, bind);
			} else {
				System.err.printf("Error starting server: %s%n", e.getMessage());
			}
			return 1;
		} catch (Exception e) {
			System.err.printf("Error starting server: %s%n", e.getMessage());
			return 1;
		}

		try {
			serverThread = Thread.currentThread();
			Thread.currentThread().join();
		} catch (InterruptedException _) {
			// Thread interrupted on server stop
		} finally {
			serverThread = null;
		}

		return 0;
	}

	/// Resolves positional arguments to determine directory and port options.
	private void resolveArguments() {
		if (directory == null && port == null) {
			if (positionalArgs.size() == 1) {
				String arg = positionalArgs.getFirst();
				if (isInteger(arg)) {
					port = Integer.parseInt(arg);
					directory = Path.of(".");
				} else {
					directory = Path.of(arg);
					port = 8080;
				}
			} else if (positionalArgs.size() >= 2) {
				String arg1 = positionalArgs.getFirst();
				String arg2 = positionalArgs.get(1);

				if (!isInteger(arg1) && isInteger(arg2)) {
					directory = Path.of(arg1);
					port = Integer.parseInt(arg2);
				} else if (isInteger(arg1) && !isInteger(arg2)) {
					port = Integer.parseInt(arg1);
					directory = Path.of(arg2);
				} else if (isInteger(arg1) && isInteger(arg2)) {
					port = Integer.parseInt(arg1);
					directory = Path.of(".");
				} else {
					directory = Path.of(arg1);
					port = 8080;
				}
			} else {
				directory = Path.of(".");
				port = 8080;
			}
		} else if (directory == null) {
			directory = Path.of(".");
		} else if (port == null) {
			port = 8080;
		}
	}

	/// Creates an HTTP request logging filter that formats output using Common Log
	/// Format while gracefully suppressing aborted client connections (such as
	/// browser speculative preconnects or socket cancellations).
	///
	/// @return A configured [Filter] instance.
	private Filter createLoggingFilter() {
		final var formatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");
		return new Filter() {
			@Override
			public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
				try {
					chain.doFilter(exchange);
					logExchange(exchange, formatter);
				} catch (IOException e) {
					if (isClientDisconnect(e)) {
						if (verbose) {
							System.err.printf("[debug] Client disconnected: %s%n",
									e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
						}
						try {
							exchange.close();
						} catch (Exception _) {
							// Ignored on aborted socket
						}
						return;
					}
					System.err.printf("Error handling request: %s%n",
							e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
					throw e;
				} catch (Throwable t) {
					System.err.printf("Error handling request: %s%n",
							t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
					throw t;
				}
			}

			@Override
			public String description() {
				return "Request Logging Filter";
			}
		};
	}

	/// Logs an HTTP exchange in Common Log Format to standard output.
	///
	/// @param exchange  The completed exchange.
	/// @param formatter Date-time formatter for log timestamps.
	private void logExchange(HttpExchange exchange, DateTimeFormatter formatter) {
		int code = exchange.getResponseCode();
		if (code <= 0) {
			return;
		}
		var addr = exchange.getRemoteAddress();
		String host = addr != null ? addr.getHostString() : "-";
		System.out.printf("%s - - [%s] \"%s %s %s\" %d -%n",
				host,
				OffsetDateTime.now().format(formatter),
				exchange.getRequestMethod(),
				exchange.getRequestURI(),
				exchange.getProtocol(),
				code);

		if (verbose) {
			if (exchange.getAttribute("request-path") instanceof String reqPath) {
				System.out.printf("Resource requested: %s%n", reqPath);
			}
			logHeaders(">", exchange.getRequestHeaders());
			logHeaders("<", exchange.getResponseHeaders());
		}
	}

	/// Prints HTTP request or response headers in verbose mode.
	///
	/// @param sign    Header direction indicator (`>` for request, `<` for
	///                response).
	/// @param headers HTTP headers collection.
	private static void logHeaders(String sign, Headers headers) {
		headers.forEach((name, values) -> {
			System.out.printf("%s %s: %s%n", sign, name, String.join(", ", values));
		});
		System.out.println(sign);
	}

	/// Detects if an exception represents a normal client socket disconnection.
	///
	/// @param t The throwable to check.
	/// @return `true` if the exception is due to client socket termination.
	private static boolean isClientDisconnect(Throwable t) {
		for (Throwable cur = t; cur != null; cur = cur.getCause()) {
			if (cur instanceof ClosedChannelException) {
				return true;
			}
			String cls = cur.getClass().getSimpleName();
			if ("StreamClosedException".equals(cls)) {
				return true;
			}
			String msg = cur.getMessage();
			if (msg != null) {
				String lower = msg.toLowerCase();
				if (lower.contains("broken pipe") || lower.contains("connection reset")
						|| lower.contains("socket closed") || lower.contains("stream closed")
						|| lower.contains("stream is closed") || lower.contains("headers already sent")) {
					return true;
				}
			}
		}
		return false;
	}

	private static final String LIVE_RELOAD_SCRIPT = """
			<!-- serve live-reload -->
			<script>
			(() => {
			  const es = new EventSource("/__serve_live_reload");
			  es.onmessage = (e) => {
			    if (e.data === "reload") {
			      location.reload();
			    }
			  };
			})();
			</script>
			""";

	/// Injects the client-side live reload EventSource script into an HTML payload.
	///
	/// @param htmlBytes Original HTML bytes.
	/// @return Transformed HTML bytes with live reload script injected.
	private static byte[] injectLiveReloadScript(byte[] htmlBytes) {
		String html = new String(htmlBytes, StandardCharsets.UTF_8);
		int idx = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
		String injected;
		if (idx != -1) {
			injected = html.substring(0, idx) + LIVE_RELOAD_SCRIPT + html.substring(idx);
		} else {
			injected = html + "\n" + LIVE_RELOAD_SCRIPT;
		}
		return injected.getBytes(StandardCharsets.UTF_8);
	}

	/// Manages live reload file watching and Server-Sent Events (SSE) broadcasting.
	private static class LiveReloadManager implements AutoCloseable {
		private static final long DEBOUNCE_MS = 100;
		private final Path rootDir;
		private final boolean verbose;
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final AtomicBoolean reloadScheduled = new AtomicBoolean(false);
		private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
		private final Map<WatchKey, Path> watchKeys = new ConcurrentHashMap<>();
		private WatchService watchService;
		private volatile long lastEventTime;

		private record SseClient(HttpExchange exchange, OutputStream os) {
		}

		LiveReloadManager(Path rootDir, boolean verbose) {
			this.rootDir = rootDir;
			this.verbose = verbose;
		}

		void start() throws IOException {
			this.watchService = FileSystems.getDefault().newWatchService();
			registerAll(rootDir);
			Thread.ofVirtual().name("live-reload-watcher").start(this::watchLoop);
		}

		private void registerAll(Path start) throws IOException {
			Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					Path fileName = dir.getFileName();
					if (fileName != null && fileName.toString().startsWith(".") && !dir.equals(rootDir)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					WatchKey key = dir.register(watchService,
							StandardWatchEventKinds.ENTRY_CREATE,
							StandardWatchEventKinds.ENTRY_DELETE,
							StandardWatchEventKinds.ENTRY_MODIFY);
					watchKeys.put(key, dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}

		private void watchLoop() {
			try {
				while (running.get()) {
					WatchKey key = watchService.take();
					Path dir = watchKeys.get(key);
					if (dir == null) {
						key.cancel();
						continue;
					}

					boolean triggered = false;
					for (WatchEvent<?> event : key.pollEvents()) {
						var kind = event.kind();
						if (kind == StandardWatchEventKinds.OVERFLOW) {
							triggered = true;
							continue;
						}

						@SuppressWarnings("unchecked")
						var ev = (WatchEvent<Path>) event;
						Path filename = ev.context();
						Path child = dir.resolve(filename);

						if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
							try {
								registerAll(child);
							} catch (IOException _) {
								// Ignored
							}
						}

						if (!isIgnored(filename)) {
							triggered = true;
						}
					}

					boolean valid = key.reset();
					if (!valid) {
						watchKeys.remove(key);
					}

					if (triggered) {
						scheduleReload();
					}
				}
			} catch (InterruptedException | ClosedWatchServiceException _) {
				// Normal shutdown
			} catch (Exception e) {
				if (running.get() && verbose) {
					System.err.printf("[debug] Live reload watch error: %s%n", e.getMessage());
				}
			}
		}

		private static boolean isIgnored(Path filename) {
			if (filename == null) {
				return true;
			}
			String name = filename.toString();
			return name.startsWith(".") || name.endsWith("~") || name.endsWith(".swp")
					|| name.endsWith(".tmp") || name.endsWith(".bak");
		}

		private void scheduleReload() {
			lastEventTime = System.currentTimeMillis();
			if (reloadScheduled.compareAndSet(false, true)) {
				Thread.ofVirtual().name("live-reload-debouncer").start(() -> {
					try {
						while (running.get()) {
							long elapsed = System.currentTimeMillis() - lastEventTime;
							if (elapsed >= DEBOUNCE_MS) {
								break;
							}
							Thread.sleep(DEBOUNCE_MS - elapsed);
						}
						if (running.get()) {
							broadcastReload();
						}
					} catch (InterruptedException _) {
						// Shutdown
					} finally {
						reloadScheduled.set(false);
					}
				});
			}
		}

		void broadcastReload() {
			byte[] msg = "data: reload\n\n".getBytes(StandardCharsets.UTF_8);
			int count = 0;
			for (var client : clients) {
				try {
					client.os().write(msg);
					client.os().flush();
					count++;
				} catch (Exception _) {
					clients.remove(client);
					try {
						client.exchange().close();
					} catch (Exception _) {
						// Ignored
					}
				}
			}
			if (count > 0 && verbose) {
				System.out.printf("[debug] Live reload: refreshed %d browser tab(s)%n", count);
			}
		}

		void handleClient(HttpExchange exchange, OutputStream os) {
			var client = new SseClient(exchange, os);
			clients.add(client);
			try {
				while (running.get()) {
					Thread.sleep(15_000);
					os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
					os.flush();
				}
			} catch (Exception _) {
				// Client disconnected or server shutting down
			} finally {
				clients.remove(client);
				try {
					exchange.close();
				} catch (Exception _) {
					// Ignored
				}
			}
		}

		@Override
		public void close() {
			if (!running.compareAndSet(true, false)) {
				return;
			}
			try {
				if (watchService != null) {
					watchService.close();
				}
			} catch (Exception _) {
				// Ignored
			}
			for (var client : clients) {
				try {
					client.exchange().close();
				} catch (Exception _) {
					// Ignored
				}
			}
			clients.clear();
			watchKeys.clear();
		}
	}
}
