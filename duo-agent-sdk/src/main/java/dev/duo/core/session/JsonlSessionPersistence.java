package dev.duo.core.session;

import dev.duo.api.agent.SessionPersistence;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionHeader;
import dev.duo.model.session.SessionId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * JSONL 文件持久化后端（对应 TS 源码中的 {@code dsh-session-persistence-jsonl}）。
 * <p>
 * 文件布局：{@code {baseDir}/{base64url(sessionId)}.jsonl}；首行为会话头
 * （type = "session"），其后每行一个事件。SessionId 经 Base64url 编码后再入路径，
 * 中和路径遍历与非法字符。写入采用 write-behind 批处理（默认 200ms），
 * 首批事件通过临时文件 + 原子改名发布；{@link #append} 与 {@link #flush}
 * 返回即已落盘（FileChannel.force）。加载时撕裂的末行被丢弃，崩溃遗留的
 * open turn 由 {@link InterruptedTurnRepair} 持久化闭合。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class JsonlSessionPersistence implements SessionPersistence, AutoCloseable {

    private static final String FILE_SUFFIX = ".jsonl";
    /** write-behind 批处理的最大等待时长（对应 dsh 的 DEFAULT_WRITE_BATCH_MAX_DELAY_MS）。 */
    private static final long WRITE_BATCH_DELAY_MS = 200;

    private final Path baseDir;
    private final Map<SessionId, SessionWriter> writers = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor flushScheduler;

    public JsonlSessionPersistence(Path baseDir) throws IOException {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null");
        Files.createDirectories(baseDir);
        this.flushScheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "jsonl-session-flusher");
            thread.setDaemon(true);
            return thread;
        });
        this.flushScheduler.scheduleWithFixedDelay(this::flushAll,
                WRITE_BATCH_DELAY_MS, WRITE_BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void create(SessionHeader header) throws IOException {
        Objects.requireNonNull(header, "header must not be null");
        if (Files.exists(fileFor(header.id()))) {
            // 磁盘上已有该会话的日志：拒绝重复创建，恢复必须走 load（fail loud，
            // 防止向既有日志追加 seq 重新计数的第二段事件流）
            throw new IllegalArgumentException("会话 \"" + header.id() + "\" 已有持久化日志，请走 load 恢复");
        }
        var existing = writers.get(header.id());
        if (existing != null) {
            throw new IllegalArgumentException("会话 \"" + header.id() + "\" 已存在");
        }
        // 惰性物化：先记头，首批 append 时才落盘（created-never-appended 不留痕迹）
        writers.putIfAbsent(header.id(), new SessionWriter(header, fileFor(header.id()), 0));
    }

    @Override
    public void append(SessionId id, List<SessionEvent> events) throws IOException {
        var writer = writerOf(id);
        synchronized (writer) {
            writer.append(events);
        }
    }

    /** 供 {@link Session#onAppend} 监听接入的非抛错入口；写入失败记入 writer 失败状态。 */
    public void acceptFromListener(SessionId id, SessionEvent event) {
        var writer = writers.get(id);
        if (writer == null) {
            return;
        }
        synchronized (writer) {
            writer.accept(event);
        }
    }

    @Override
    public SessionInspection load(SessionId id) throws IOException {
        var file = fileFor(id);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("会话 \"" + id + "\" 无持久化文件");
        }
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("会话日志为空: " + file);
        }
        var header = SessionEventCodec.decodeHeader(lines.getFirst());

        var events = new ArrayList<SessionEvent>(lines.size());
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                events.add(SessionEventCodec.decode(line));
            } catch (RuntimeException e) {
                if (i == lines.size() - 1) {
                    // 撕裂的末行（崩溃时写了一半）：丢弃
                    break;
                }
                throw new IOException("会话日志第 " + (i + 1) + " 行损坏: " + file, e);
            }
        }

        // 冷恢复：建立写入器（seq 从闭合后日志尾续）；崩溃遗留的 open turn 持久化闭合
        var closers = InterruptedTurnRepair.closersFor(events);
        var writer = writers.computeIfAbsent(id,
                ignored -> new SessionWriter(header, file, events.getLast().seq() + 1));
        synchronized (writer) {
            writer.materializeIfAbsent();
            if (!closers.isEmpty()) {
                writer.append(closers);
                events.addAll(closers);
            }
        }
        return new SessionInspection(header, List.copyOf(events));
    }

    @Override
    public List<SessionHeader> list() throws IOException {
        var headers = new ArrayList<SessionHeader>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "*" + FILE_SUFFIX)) {
            for (var path : stream) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    var first = reader.readLine();
                    if (first != null && !first.isBlank()) {
                        headers.add(SessionEventCodec.decodeHeader(first));
                    }
                }
            }
        }
        return headers;
    }

    @Override
    public void flush(SessionId id) throws IOException {
        var writer = writers.get(id);
        if (writer != null) {
            synchronized (writer) {
                writer.flush();
            }
        }
    }

    /** 关闭：刷尽所有缓冲并停止调度器。 */
    @Override
    public void close() throws IOException {
        flushScheduler.shutdownNow();
        IOException failure = null;
        for (var entry : writers.entrySet()) {
            try {
                flush(entry.getKey());
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void flushAll() {
        for (var id : writers.keySet()) {
            try {
                flush(id);
            } catch (IOException ignored) {
                // 定时刷失败保留在 writer 的待写缓冲中，下次重试；最终失败由 flush/close 暴露
            }
        }
    }

    private SessionWriter writerOf(SessionId id) {
        var writer = writers.get(id);
        if (writer == null) {
            throw new IllegalArgumentException("会话 \"" + id + "\" 未 create");
        }
        return writer;
    }

    private Path fileFor(SessionId id) {
        // SessionId 是未校验字符串；Base64url 编码后入路径，中和遍历与非法字符
        var encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.value().getBytes(StandardCharsets.UTF_8));
        return baseDir.resolve(encoded + FILE_SUFFIX);
    }

    /** 单会话的写状态：待写缓冲 + 物化标志 + 持久 seq 期望值。 */
    private static final class SessionWriter {

        private final SessionHeader header;
        private final Path file;
        private final List<SessionEvent> pending = new ArrayList<>();
        private IOException failure;
        private boolean materialized;
        /** 下一批首事件必须满足的 seq（连续性契约的写入侧校验）。 */
        private int durableNextSeq;

        SessionWriter(SessionHeader header, Path file, int durableNextSeq) {
            this.header = header;
            this.file = file;
            this.durableNextSeq = durableNextSeq;
        }

        void accept(SessionEvent event) {
            pending.add(event);
        }

        void append(List<SessionEvent> events) throws IOException {
            pending.addAll(events);
            flush();
        }

        void materializeIfAbsent() throws IOException {
            if (materialized) {
                return;
            }
            if (Files.exists(file)) {
                // 已物化（如 load 修复路径复用 writer）：不得重写 header
                materialized = true;
                return;
            }
            // 原子首写：临时文件 + 改名发布，崩溃不留半写文件
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, SessionEventCodec.encodeHeader(header) + "\n",
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            materialized = true;
        }

        void flush() throws IOException {
            if (failure != null) {
                // 每次抛出包装新实例：同一实例被方法体与 close 各抛一次会触发
                // try-with-resources 的 self-suppression 错误
                throw new IOException(failure.getMessage(), failure);
            }
            if (pending.isEmpty()) {
                return;
            }
            // seq 连续性契约：首事件 seq 必须等于已存 next-seq，其后逐个递增
            for (var event : pending) {
                if (event.seq() != durableNextSeq) {
                    failure = new IOException("事件 seq 不连续：期望 " + durableNextSeq
                            + "，实际 " + event.seq() + "（" + event.type() + "）");
                    throw failure;
                }
                durableNextSeq++;
            }
            materializeIfAbsent();
            var sb = new StringBuilder(pending.size() * 96);
            for (var event : pending) {
                sb.append(SessionEventCodec.encode(event)).append('\n');
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            pending.clear();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(false);
            }
        }
    }
}
