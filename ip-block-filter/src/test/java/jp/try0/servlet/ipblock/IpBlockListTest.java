package jp.try0.servlet.ipblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class IpBlockListTest {

    @Test
    void mergesAllSources() {
        try (IpBlockList list = IpBlockList.builder()
                .addSource(new InMemoryBlockListSource().add("192.0.2.10"))
                .addSource(BlockListSource.named("lambda", () -> List.of("198.51.100.0/24")))
                .reloadInterval(Duration.ZERO)
                .build()) {
            list.start();
            assertTrue(list.isBlocked("192.0.2.10"));
            assertTrue(list.isBlocked("198.51.100.200"));
            assertFalse(list.isBlocked("203.0.113.1"));
            assertEquals(2, list.size());
        }
    }

    @Test
    void notBlockingBeforeStart() {
        IpBlockList list = IpBlockList.builder()
                .addSource(new InMemoryBlockListSource().add("192.0.2.10")).build();
        assertFalse(list.isBlocked("192.0.2.10"));
    }

    @Test
    void reloadNowReflectsChanges() {
        InMemoryBlockListSource manual = new InMemoryBlockListSource();
        try (IpBlockList list = IpBlockList.builder().addSource(manual).reloadInterval(Duration.ZERO).build()) {
            list.start();
            assertFalse(list.isBlocked("192.0.2.10"));

            manual.add("192.0.2.10");
            assertFalse(list.isBlocked("192.0.2.10"), "reloadNow まではまだ反映されない");
            assertTrue(list.reloadNow());
            assertTrue(list.isBlocked("192.0.2.10"));

            manual.remove("192.0.2.10");
            list.reloadNow();
            assertFalse(list.isBlocked("192.0.2.10"));
        }
    }

    @Test
    void failingSourceKeepsLastGoodEntriesWhileOthersUpdate() {
        AtomicBoolean dbDown = new AtomicBoolean(false);
        BlockListSource db = BlockListSource.named("db", () -> {
            if (dbDown.get()) {
                throw new IOException("DB down");
            }
            return List.of("192.0.2.10");
        });
        InMemoryBlockListSource manual = new InMemoryBlockListSource().add("198.51.100.1");

        try (IpBlockList list = IpBlockList.builder()
                .addSource(db).addSource(manual).reloadInterval(Duration.ZERO).build()) {
            list.start();
            assertTrue(list.isBlocked("192.0.2.10"));

            dbDown.set(true);
            manual.add("198.51.100.2");
            assertFalse(list.reloadNow(), "失敗したソースがあれば false");
            assertTrue(list.isBlocked("192.0.2.10"), "DB 障害中も前回の内容でブロックし続ける");
            assertTrue(list.isBlocked("198.51.100.2"), "他のソースは更新される");

            dbDown.set(false);
            assertTrue(list.reloadNow());
            assertTrue(list.isBlocked("192.0.2.10"));
        }
    }

    @Test
    void invalidEntriesAreSkipped() {
        try (IpBlockList list = IpBlockList.builder()
                .addSource(() -> List.of("192.0.2.10", "not-an-ip", "10.0.0.0/99"))
                .reloadInterval(Duration.ZERO).build()) {
            list.start();
            assertTrue(list.isBlocked("192.0.2.10"));
            assertEquals(List.of("192.0.2.10"), list.entries());
        }
    }

    @Test
    void nullFromSourceIsTreatedAsFailure() {
        try (IpBlockList list = IpBlockList.builder()
                .addSource(() -> null).reloadInterval(Duration.ZERO).build()) {
            assertFalse(list.reloadNow());
        }
    }

    @Test
    void failOnStartupErrorStopsStart() {
        BlockListSource broken = () -> {
            throw new IOException("unreachable");
        };
        try (IpBlockList strict = IpBlockList.builder()
                .addSource(broken).failOnStartupError(true).reloadInterval(Duration.ZERO).build()) {
            assertThrows(IllegalStateException.class, strict::start);
            assertFalse(strict.isRunning());
        }
        try (IpBlockList lenient = IpBlockList.builder()
                .addSource(broken).addSource(new InMemoryBlockListSource().add("192.0.2.10"))
                .reloadInterval(Duration.ZERO).build()) {
            lenient.start();
            assertTrue(lenient.isRunning());
            assertTrue(lenient.isBlocked("192.0.2.10"), "読めたソースだけで動き始める");
        }
    }

    @Test
    void periodicReloadPicksUpChangesAndStopEndsThread() throws Exception {
        InMemoryBlockListSource manual = new InMemoryBlockListSource();
        try (IpBlockList list = IpBlockList.builder()
                .addSource(manual).reloadInterval(Duration.ofMillis(50)).build()) {
            list.start();
            list.start(); // 二重 start は無視される
            manual.add("192.0.2.10");
            assertTrue(waitUntil(() -> list.isBlocked("192.0.2.10")), "定期リロードで反映される");
            assertTrue(reloaderThreadAlive());
        }
        assertTrue(waitUntil(() -> !reloaderThreadAlive()), "stop でリロード用スレッドが終了する");
    }

    @Test
    void periodicReloadSurvivesExceptions() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        InMemoryBlockListSource manual = new InMemoryBlockListSource();
        BlockListSource flaky = () -> {
            if (fail.get()) {
                throw new IllegalStateException("boom");
            }
            return manual.load();
        };
        try (IpBlockList list = IpBlockList.builder()
                .addSource(flaky).reloadInterval(Duration.ofMillis(30)).build()) {
            list.start();
            Thread.sleep(150); // 失敗が何回か続く
            fail.set(false);
            manual.add("192.0.2.10");
            assertTrue(waitUntil(() -> list.isBlocked("192.0.2.10")), "例外の後も定期リロードが続いている");
        }
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalStateException.class, () -> IpBlockList.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> IpBlockList.builder().reloadInterval(Duration.ofSeconds(-1)));
    }

    private static boolean reloaderThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals(IpBlockList.THREAD_NAME) && t.isAlive());
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
