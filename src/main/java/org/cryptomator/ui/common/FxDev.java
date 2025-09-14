package org.cryptomator.ui.common;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FxDev {

	public static void hookSceneStyles(Scene scene) {
		for (String uri : scene.getStylesheets()) {
			watchUri(uri, () -> reloadStylesheet(scene, uri));
		}
		scene.getStylesheets().addListener((ListChangeListener<String>) ch -> {
			while (ch.next()) {
				if (ch.wasAdded()) {
					for (String uri : ch.getAddedSubList()) {
						watchUri(uri, () -> reloadStylesheet(scene, uri));
					}
				}
			}
		});
	}

	public static void watchFxmlGraph(URL rootUrl, Runnable onAnyChange) {
		watchFile(rootUrl, onAnyChange);

		var visited = new java.util.HashSet<URL>();
		for (URL dep : collectIncludedFxmls(rootUrl, visited)) {
			watchFile(dep, onAnyChange);
		}
	}

	private static java.util.Set<URL> collectIncludedFxmls(URL base, java.util.Set<URL> visited) {
		var out = new java.util.LinkedHashSet<URL>();
		if (visited.contains(base)) {
			return out;
		}
		visited.add(base);

		try (var in = base.openStream()) {
			var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			try {
				dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
				dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
				dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			} catch (Exception ignore) {}

			var doc = dbf.newDocumentBuilder().parse(in);
			final String FX_NS = "http://javafx.com/fxml";

			var nodeList = doc.getElementsByTagNameNS(FX_NS, "include");
			for (int i = 0; i < nodeList.getLength(); i++) {
				var el = (org.w3c.dom.Element) nodeList.item(i);
				var src = el.getAttribute("source");
				if (src == null || src.isBlank()) continue;

				URL includeUrl = new URL(base, src);
				out.add(includeUrl);

				out.addAll(collectIncludedFxmls(includeUrl, visited));
			}
		} catch (Exception e) {
			System.err.println("[fxdev] include scan failed for " + base + ": " + e.getMessage());
		}

		return out;
	}

	private static void reloadStylesheet(Scene scene, String uri) {
		var list = scene.getStylesheets();
		int idx = list.indexOf(uri);
		if (idx >= 0) {
			list.remove(idx);
			list.add(idx, uri);
		}
	}

	private static final ExecutorService FXDEV_WATCH_EXEC = Executors.newSingleThreadExecutor(r -> {
		var t = new Thread(r, "fxdev-watch"); t.setDaemon(true); return t;
	});

	private static volatile WatchService FXDEV_WS;
	private static volatile boolean FXDEV_WATCH_STARTED;
	private static final Map<Path, List<Runnable>> FXDEV_FILE_CBS = new java.util.concurrent.ConcurrentHashMap<>();

	private static synchronized void ensureWatcher() {
		if (FXDEV_WATCH_STARTED) return;
		try { FXDEV_WS = FileSystems.getDefault().newWatchService(); }
		catch (IOException e) { throw new RuntimeException(e); }
		FXDEV_WATCH_EXEC.submit(() -> {
			for (;;) {
				WatchKey key;
				try { key = FXDEV_WS.take(); } catch (InterruptedException ex) { return; }
				Path dir = (Path) key.watchable();
				for (WatchEvent<?> ev : key.pollEvents()) {
					if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
					@SuppressWarnings("unchecked")
					Path name = ((WatchEvent<Path>) ev).context();
					Path changed = dir.resolve(name).toAbsolutePath().normalize();
					var cbs = FXDEV_FILE_CBS.get(changed);
					if (cbs != null) {
						for (Runnable r : cbs) Platform.runLater(r);
					}
				}
				key.reset();
			}
		});
		FXDEV_WATCH_STARTED = true;
	}

	private static void watchFile(URL url, Runnable onChange) {
		try {
			URI uri = url.toURI();
			if (!"file".equalsIgnoreCase(uri.getScheme())) {
				return;
			}
			ensureWatcher();
			Path file = Path.of(uri).toAbsolutePath().normalize();
			FXDEV_FILE_CBS.computeIfAbsent(file, k -> {
				try {
					file.getParent().register(
							FXDEV_WS,
							StandardWatchEventKinds.ENTRY_MODIFY,
							StandardWatchEventKinds.ENTRY_CREATE,
							StandardWatchEventKinds.ENTRY_DELETE
					);
				} catch (IOException e) { throw new RuntimeException(e); }
				return new CopyOnWriteArrayList<>();
			}).add(onChange);
		} catch (Exception ignored) {}
	}

	private static void watchUri(String uri, Runnable onChange) {
		try {
			URI u = URI.create(uri);
			if (!"file".equalsIgnoreCase(u.getScheme())) {
				return;
			}
			ensureWatcher();
			Path file = Path.of(u).toAbsolutePath().normalize();
			FXDEV_FILE_CBS.computeIfAbsent(file, k -> {
				try {
					file.getParent().register(
							FXDEV_WS,
							StandardWatchEventKinds.ENTRY_MODIFY,
							StandardWatchEventKinds.ENTRY_CREATE,
							StandardWatchEventKinds.ENTRY_DELETE
					);
				} catch (IOException e) { throw new RuntimeException(e); }
				return new CopyOnWriteArrayList<>();
			}).add(onChange);
		} catch (Exception ignored) {}
	}
}
