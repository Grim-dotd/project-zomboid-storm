package io.pzstorm.storm.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.metrics.FileDescriptorMetrics.FdType;
import io.pzstorm.storm.metrics.FileDescriptorMetrics.Sample;
import io.pzstorm.storm.metrics.FileDescriptorMetrics.SocketRow;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDescriptorMetricsTest implements UnitTest {

    @Test
    void descriptorTargetsAreClassifiedByKind() {
        assertEquals(FdType.SOCKET, FileDescriptorMetrics.classify("socket:[7150818]"));
        assertEquals(FdType.PIPE, FileDescriptorMetrics.classify("pipe:[88213]"));
        assertEquals(FdType.EVENTPOLL, FileDescriptorMetrics.classify("anon_inode:[eventpoll]"));
        assertEquals(FdType.EVENTFD, FileDescriptorMetrics.classify("anon_inode:[eventfd]"));
        assertEquals(FdType.ANON_INODE, FileDescriptorMetrics.classify("anon_inode:[timerfd]"));
        assertEquals(FdType.DEVICE, FileDescriptorMetrics.classify("/dev/urandom"));
        assertEquals(
                FdType.FILE,
                FileDescriptorMetrics.classify("/home/pzuser/Zomboid/Saves/map_1_2.bin"));
        assertEquals(FdType.OTHER, FileDescriptorMetrics.classify("net:[4026531840]"));
    }

    @Test
    void anUnlinkedFileIsCountedAsDeleted() {
        assertEquals(
                FdType.DELETED, FileDescriptorMetrics.classify("/tmp/hsperfdata/1234 (deleted)"));
    }

    @Test
    void aListeningTcpRowYieldsItsInodeAndPort() {
        SocketRow row =
                FileDescriptorMetrics.parseSocketRow(
                        "   0: 0100007F:8867 00000000:0000 0A 00000000:00000000 00:00000000"
                                + " 00000000  1002        0 7150818 1 0000000000000000 100 0 0"
                                + " 10 0");

        assertEquals(new SocketRow(7150818L, 0x8867, true), row);
    }

    @Test
    void anEstablishedIpv6RowIsNotListening() {
        SocketRow row =
                FileDescriptorMetrics.parseSocketRow(
                        "   4: 0000000000000000FFFF00000914A028:2384"
                                + " 0000000000000000FFFF00003654CCC7:D2F0 01 00000000:00000000"
                                + " 00:00000000 00000000  1002        0 9911 1 0000000000000000 20"
                                + " 4 30 10 -1");

        assertEquals(new SocketRow(9911L, 9092, false), row);
    }

    @Test
    void headerAndOrphanedRowsAreSkipped() {
        assertNull(
                FileDescriptorMetrics.parseSocketRow(
                        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when"
                                + " retrnsmt   uid  timeout inode"));
        assertNull(
                FileDescriptorMetrics.parseSocketRow(
                        "   9: 0100007F:8867 0100007F:C350 06 00000000:00000000 03:00000A2B"
                                + " 00000000     0        0 0 3 0000000000000000"),
                "a TIME_WAIT row has inode 0 and belongs to no descriptor");
    }

    @Test
    void onlyTrackedExtensionsBecomeLabelValues() {
        assertEquals("bin", FileDescriptorMetrics.extensionOf("/saves/map_10_12.BIN"));
        assertEquals("jar", FileDescriptorMetrics.extensionOf("/opt/pzserver/java/storm.jar"));
        assertEquals("other", FileDescriptorMetrics.extensionOf("/saves/players.a8f31c"));
        assertEquals("other", FileDescriptorMetrics.extensionOf("/opt/pz.server/console"));
    }

    @Test
    void theWarningNamesTheDirectoriesHoldingTheMostFiles() {
        int[] byType = new int[FdType.values().length];
        byType[FdType.FILE.ordinal()] = 3;
        byType[FdType.SOCKET_TCP.ordinal()] = 900;
        Sample sample =
                new Sample(
                        byType,
                        1001,
                        Map.of("9092", 898, "outbound", 2),
                        Map.of("bin", 3),
                        List.of("/saves/a/1.bin", "/saves/a/2.bin", "/saves/b/3.bin"));

        String warning = FileDescriptorMetrics.describe(sample);

        assertTrue(warning.contains("highest fd 1001"), warning);
        assertTrue(warning.contains("open=903"), warning);
        assertTrue(warning.contains("socket_tcp=900"), warning);
        assertTrue(warning.contains("9092=898"), warning);
        assertTrue(warning.contains("[2 /saves/a/, 1 /saves/b/]"), warning);
    }

    @Test
    void aLiveSampleAttributesAnAcceptedConnectionToItsListeningPort(@TempDir Path dir)
            throws Exception {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "needs Linux procfs");

        Path file = Files.createFile(dir.resolve("held.lotpack"));
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                Socket client = new Socket(server.getInetAddress(), server.getLocalPort());
                Socket accepted = server.accept();
                FileChannel held = FileChannel.open(file, StandardOpenOption.READ)) {
            String port = String.valueOf(server.getLocalPort());

            Sample sample = FileDescriptorMetrics.sample();

            assertEquals(1, sample.tcpByLocalPort().get(port), sample.tcpByLocalPort().toString());
            assertTrue(sample.tcpByLocalPort().get("outbound") >= 1, "the client end is outbound");
            assertTrue(sample.count(FdType.SOCKET_TCP_LISTEN) >= 1);
            assertTrue(sample.count(FdType.SOCKET_TCP) >= 2);
            assertTrue(sample.fileTargets().contains(file.toRealPath().toString()));
            assertTrue(sample.filesByExtension().get("lotpack") >= 1);
            assertTrue(sample.highestFd() >= sample.total() - 1);
        }
    }
}
