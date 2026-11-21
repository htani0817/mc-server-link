package jp.mc.serverlink.server;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jp.mc.serverlink.McServerLink;
import jp.mc.serverlink.logic.LinkConfig;
import jp.mc.serverlink.logic.StatusPing;

/**
 * ほかのサーバーが動いているかを、決まった間隔で別のスレッドから確かめて覚えておく。
 * サーバーの tick（メインのスレッド）は待たせない。
 */
final class StatusCache {
    private final LinkConfig config;
    private final ScheduledExecutorService exec;
    private final Map<String, StatusPing.Result> results = new ConcurrentHashMap<>();
    /** 前回の状態（変わったときだけログに出す） */
    private final Map<String, StatusPing.State> lastLogged = new ConcurrentHashMap<>();

    StatusCache(LinkConfig config) {
        this.config = config;
        AtomicInteger n = new AtomicInteger();
        this.exec = Executors.newScheduledThreadPool(Math.min(4, Math.max(1, config.servers.size())), r -> {
            Thread t = new Thread(r, "mc-server-link status #" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    void start() {
        exec.scheduleWithFixedDelay(this::refreshAll, 0, config.statusIntervalSeconds, TimeUnit.SECONDS);
    }

    void stop() {
        exec.shutdownNow();
    }

    /** まだ確かめていなければ null */
    StatusPing.Result get(String id) {
        return results.get(id);
    }

    /** いま確かめ直す（別のスレッドで）。結果は覚えておく */
    CompletableFuture<StatusPing.Result> pingNow(LinkConfig.Entry e) {
        CompletableFuture<StatusPing.Result> f = new CompletableFuture<>();
        try {
            exec.execute(() -> f.complete(pingAndStore(e)));
        } catch (RuntimeException ex) {
            // 止めている途中など
            f.complete(StatusPing.Result.unknown("確かめられません: " + ex.getMessage(), 0));
        }
        return f;
    }

    /** 呼んだスレッドで確かめる（サーバーを止めるときだけ使う） */
    StatusPing.Result pingBlocking(LinkConfig.Entry e) {
        return pingAndStore(e);
    }

    private void refreshAll() {
        for (LinkConfig.Entry e : config.servers) {
            if (e.id.equals(config.thisServer)) {
                continue;
            }
            try {
                pingAndStore(e);
            } catch (RuntimeException ex) {
                McServerLink.LOGGER.warn("[mc-server-link] {} の確認に失敗しました", e.id, ex);
            }
        }
    }

    private StatusPing.Result pingAndStore(LinkConfig.Entry e) {
        StatusPing.Result r = StatusPing.ping(e.pingAddress(), config.pingTimeoutMillis);
        results.put(e.id, r);
        StatusPing.State before = lastLogged.put(e.id, r.state());
        if (before != r.state()) {
            if (r.state() == StatusPing.State.ONLINE) {
                McServerLink.LOGGER.info("[mc-server-link] {}（{}）: 動いています（{}/{}、{} ms）", e.id, e.pingAddress(), r.online(), r.max(), r.millis());
            } else {
                McServerLink.LOGGER.info("[mc-server-link] {}（{}）: {}（{}）", e.id, e.pingAddress(),
                        r.state() == StatusPing.State.OFFLINE ? "止まっています" : "応答を読めません", r.error());
            }
        }
        return r;
    }
}
