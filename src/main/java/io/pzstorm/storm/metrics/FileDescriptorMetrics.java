package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.pzstorm.storm.logging.StormLogger;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.Collectors;

/**
 * Breaks the process's open file descriptors down by kind, so a descriptor surge can be attributed
 * to sockets, files, or pipes instead of showing up only as a rising {@code process_open_fds}.
 *
 * <p>The number that matters most is the highest descriptor in use. Native code that still calls
 * {@code select()}, {@code steamclient.so} among it, cannot watch a descriptor numbered {@value
 * #SELECT_FD_LIMIT} or above, and glibc aborts the whole process when it tries.
 *
 * <p>A daemon thread samples {@code /proc/self/fd} once a second, and the gauges serve the latest
 * sample. Peaks are kept for the life of the process because a surge can rise and kill the server
 * between two scrapes. For the same reason the sampler logs the full breakdown once the highest
 * descriptor passes {@value #WARN_HIGHEST_FD}, which leaves a record that outlives the process.
 *
 * <p>Linux only. Elsewhere nothing is registered and no thread starts. Loaded through {@link
 * EventDispatchMetrics}'s static initializer.
 */
public final class FileDescriptorMetrics {

    static final int SELECT_FD_LIMIT = 1024;

    private static final int WARN_HIGHEST_FD = 768;
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long SAMPLE_INTERVAL_MILLIS = 1000;
    private static final int TOP_DIRECTORIES_LOGGED = 10;

    private static final Path FD_DIR = Path.of("/proc/self/fd");
    private static final String[] TCP_TABLES = {"/proc/net/tcp", "/proc/net/tcp6"};
    private static final String[] UDP_TABLES = {"/proc/net/udp", "/proc/net/udp6"};

    private static final String TCP_LISTEN_STATE = "0A";
    private static final String OUTBOUND = "outbound";
    private static final String OTHER_EXTENSION = "other";

    private static final Set<String> TRACKED_EXTENSIONS =
            Set.of(
                    "bin",
                    "lotpack",
                    "lotheader",
                    "jar",
                    "pack",
                    "db",
                    "log",
                    "txt",
                    "lua",
                    "so",
                    "zip",
                    "png");

    enum FdType {
        FILE("file"),
        DELETED("deleted"),
        DEVICE("device"),
        PIPE("pipe"),
        EVENTPOLL("eventpoll"),
        EVENTFD("eventfd"),
        ANON_INODE("anon_inode"),
        SOCKET("socket_other"),
        SOCKET_TCP("socket_tcp"),
        SOCKET_TCP_LISTEN("socket_tcp_listen"),
        SOCKET_UDP("socket_udp"),
        OTHER("other");

        private static final FdType[] VALUES = values();

        final String label;

        FdType(String label) {
            this.label = label;
        }
    }

    record SocketRow(long inode, int localPort, boolean listening) {}

    record Sample(
            int[] byType,
            int highestFd,
            Map<String, Integer> tcpByLocalPort,
            Map<String, Integer> filesByExtension,
            List<String> fileTargets) {

        static final Sample EMPTY =
                new Sample(new int[FdType.VALUES.length], 0, Map.of(), Map.of(), List.of());

        int count(FdType type) {
            return byType[type.ordinal()];
        }

        int total() {
            int total = 0;
            for (int count : byType) {
                total += count;
            }
            return total;
        }
    }

    private static final AtomicIntegerArray PEAK_BY_TYPE =
            new AtomicIntegerArray(FdType.VALUES.length);
    private static final AtomicInteger PEAK_HIGHEST_FD = new AtomicInteger();

    private static volatile Sample latest = Sample.EMPTY;

    static {
        if (Files.isDirectory(FD_DIR)) {
            registerGauges();
            Thread sampler = new Thread(FileDescriptorMetrics::sampleForever, "StormFdSampler");
            sampler.setDaemon(true);
            sampler.start();
        }
    }

    private FileDescriptorMetrics() {}

    /** No-op; calling forces class load so the static initializer fires. */
    public static void ensureStarted() {}

    private static void registerGauges() {
        GaugeWithCallback.builder()
                .name("storm_process_open_fds")
                .help("Open file descriptors by kind, sampled once a second.")
                .labelNames("type")
                .callback(
                        cb -> {
                            Sample sample = latest;
                            for (FdType type : FdType.VALUES) {
                                cb.call(sample.count(type), type.label);
                            }
                        })
                .register(StormPrometheus.registry());

        GaugeWithCallback.builder()
                .name("storm_process_open_fds_peak")
                .help("Highest count of open file descriptors of each kind since process start.")
                .labelNames("type")
                .callback(
                        cb -> {
                            for (FdType type : FdType.VALUES) {
                                cb.call(PEAK_BY_TYPE.get(type.ordinal()), type.label);
                            }
                        })
                .register(StormPrometheus.registry());

        GaugeWithCallback.builder()
                .name("storm_process_highest_fd")
                .help(
                        "Highest file descriptor number in use. Native select() callers abort the"
                                + " process at "
                                + SELECT_FD_LIMIT
                                + ".")
                .callback(cb -> cb.call(latest.highestFd()))
                .register(StormPrometheus.registry());

        GaugeWithCallback.builder()
                .name("storm_process_highest_fd_peak")
                .help("Highest file descriptor number seen since process start.")
                .callback(cb -> cb.call(PEAK_HIGHEST_FD.get()))
                .register(StormPrometheus.registry());

        GaugeWithCallback.builder()
                .name("storm_process_tcp_connections")
                .help(
                        "TCP connections held by this process, by the listening port that accepted"
                                + " them. Connections this process opened are under"
                                + " local_port=\"outbound\".")
                .labelNames("local_port")
                .callback(cb -> latest.tcpByLocalPort().forEach((port, n) -> cb.call(n, port)))
                .register(StormPrometheus.registry());

        GaugeWithCallback.builder()
                .name("storm_process_open_files")
                .help("Open regular files by file extension.")
                .labelNames("extension")
                .callback(cb -> latest.filesByExtension().forEach((ext, n) -> cb.call(n, ext)))
                .register(StormPrometheus.registry());
    }

    private static void sampleForever() {
        boolean failureLogged = false;
        long lastWarnNanos = System.nanoTime() - WARN_INTERVAL_NANOS;

        while (true) {
            try {
                Sample sample = sample();
                latest = sample;
                recordPeaks(sample);

                long now = System.nanoTime();
                if (sample.highestFd() >= WARN_HIGHEST_FD
                        && now - lastWarnNanos >= WARN_INTERVAL_NANOS) {
                    lastWarnNanos = now;
                    StormLogger.LOGGER.warn(describe(sample));
                }
            } catch (Throwable t) {
                if (!failureLogged) {
                    failureLogged = true;
                    StormLogger.LOGGER.warn("FileDescriptorMetrics: sampling failed", t);
                }
            }

            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void recordPeaks(Sample sample) {
        for (FdType type : FdType.VALUES) {
            int index = type.ordinal();
            if (sample.byType()[index] > PEAK_BY_TYPE.get(index)) {
                PEAK_BY_TYPE.set(index, sample.byType()[index]);
            }
        }
        if (sample.highestFd() > PEAK_HIGHEST_FD.get()) {
            PEAK_HIGHEST_FD.set(sample.highestFd());
        }
    }

    static Sample sample() throws IOException {
        int[] byType = new int[FdType.VALUES.length];
        int highestFd = 0;
        List<Long> socketInodes = new ArrayList<>();
        List<String> fileTargets = new ArrayList<>();
        Map<String, Integer> filesByExtension = new TreeMap<>();

        try (DirectoryStream<Path> fds = Files.newDirectoryStream(FD_DIR)) {
            for (Path fd : fds) {
                String target;
                try {
                    target = Files.readSymbolicLink(fd).toString();
                } catch (IOException closedMeanwhile) {
                    continue;
                }
                if (isFdDirectory(target)) {
                    continue;
                }

                highestFd = Math.max(highestFd, Integer.parseInt(fd.getFileName().toString()));

                FdType type = classify(target);
                if (type == FdType.SOCKET) {
                    socketInodes.add(socketInode(target));
                    continue;
                }
                byType[type.ordinal()]++;
                if (type == FdType.FILE) {
                    fileTargets.add(target);
                    filesByExtension.merge(extensionOf(target), 1, Integer::sum);
                }
            }
        }

        Map<String, Integer> tcpByLocalPort = new TreeMap<>();
        if (!socketInodes.isEmpty()) {
            countSockets(socketInodes, byType, tcpByLocalPort);
        }

        return new Sample(byType, highestFd, tcpByLocalPort, filesByExtension, fileTargets);
    }

    private static void countSockets(
            List<Long> socketInodes, int[] byType, Map<String, Integer> tcpByLocalPort) {
        Map<Long, SocketRow> tcp = readSocketTables(TCP_TABLES);
        Map<Long, SocketRow> udp = readSocketTables(UDP_TABLES);

        List<Integer> connectionPorts = new ArrayList<>();
        for (long inode : socketInodes) {
            SocketRow row = tcp.get(inode);
            if (row == null) {
                FdType type = udp.containsKey(inode) ? FdType.SOCKET_UDP : FdType.SOCKET;
                byType[type.ordinal()]++;
            } else if (row.listening()) {
                byType[FdType.SOCKET_TCP_LISTEN.ordinal()]++;
                tcpByLocalPort.putIfAbsent(String.valueOf(row.localPort()), 0);
            } else {
                byType[FdType.SOCKET_TCP.ordinal()]++;
                connectionPorts.add(row.localPort());
            }
        }

        tcpByLocalPort.put(OUTBOUND, 0);
        for (int port : connectionPorts) {
            String accepted = String.valueOf(port);
            String label = tcpByLocalPort.containsKey(accepted) ? accepted : OUTBOUND;
            tcpByLocalPort.merge(label, 1, Integer::sum);
        }
    }

    private static Map<Long, SocketRow> readSocketTables(String[] tables) {
        Map<Long, SocketRow> rows = new HashMap<>();
        for (String table : tables) {
            try (BufferedReader reader = Files.newBufferedReader(Path.of(table))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    SocketRow row = parseSocketRow(line);
                    if (row != null) {
                        rows.put(row.inode(), row);
                    }
                }
            } catch (IOException tableUnavailable) {
                // IPv6 tables are absent when the kernel has IPv6 disabled.
            }
        }
        return rows;
    }

    /** Parses one row of {@code /proc/net/tcp}, {@code tcp6}, {@code udp} or {@code udp6}. */
    static SocketRow parseSocketRow(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 10) {
            return null;
        }
        try {
            long inode = Long.parseLong(fields[9]);
            if (inode == 0) {
                return null;
            }
            String local = fields[1];
            int port = Integer.parseInt(local.substring(local.lastIndexOf(':') + 1), 16);
            return new SocketRow(inode, port, TCP_LISTEN_STATE.equals(fields[3]));
        } catch (NumberFormatException headerOrMalformed) {
            return null;
        }
    }

    static FdType classify(String target) {
        if (target.startsWith("socket:[")) {
            return FdType.SOCKET;
        }
        if (target.startsWith("pipe:[")) {
            return FdType.PIPE;
        }
        if (target.equals("anon_inode:[eventpoll]")) {
            return FdType.EVENTPOLL;
        }
        if (target.equals("anon_inode:[eventfd]")) {
            return FdType.EVENTFD;
        }
        if (target.startsWith("anon_inode:")) {
            return FdType.ANON_INODE;
        }
        if (target.endsWith(" (deleted)")) {
            return FdType.DELETED;
        }
        if (target.startsWith("/dev/")) {
            return FdType.DEVICE;
        }
        if (target.startsWith("/")) {
            return FdType.FILE;
        }
        return FdType.OTHER;
    }

    static String extensionOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return OTHER_EXTENSION;
        }
        String extension = name.substring(dot + 1).toLowerCase();
        return TRACKED_EXTENSIONS.contains(extension) ? extension : OTHER_EXTENSION;
    }

    private static long socketInode(String target) {
        return Long.parseLong(target.substring("socket:[".length(), target.length() - 1));
    }

    /** The directory listing itself holds one descriptor open on {@code /proc/<pid>/fd}. */
    private static boolean isFdDirectory(String target) {
        return target.startsWith("/proc/") && target.endsWith("/fd");
    }

    static String describe(Sample sample) {
        Map<String, Integer> byType = new TreeMap<>();
        for (FdType type : FdType.VALUES) {
            if (sample.count(type) > 0) {
                byType.put(type.label, sample.count(type));
            }
        }

        Map<String, Long> byDirectory =
                sample.fileTargets().stream()
                        .collect(
                                Collectors.groupingBy(
                                        path -> path.substring(0, path.lastIndexOf('/') + 1),
                                        Collectors.counting()));
        String topDirectories =
                byDirectory.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(TOP_DIRECTORIES_LOGGED)
                        .map(entry -> entry.getValue() + " " + entry.getKey())
                        .collect(Collectors.joining(", "));

        return "FileDescriptorMetrics: highest fd "
                + sample.highestFd()
                + " is nearing the select() limit of "
                + SELECT_FD_LIMIT
                + ". open="
                + sample.total()
                + " byType="
                + byType
                + " tcpByLocalPort="
                + sample.tcpByLocalPort()
                + " filesByExtension="
                + sample.filesByExtension()
                + " topFileDirectories=["
                + topDirectories
                + "]";
    }
}
