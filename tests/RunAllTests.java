///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//DEPS com.github.oshi:oshi-core:6.6.5
//DEPS org.slf4j:slf4j-nop:2.0.16
//DEPS org.apache.commons:commons-compress:1.27.1
//SOURCES ../apps/digest/Digest.java
//SOURCES ../apps/digest/DigestTest.java
//SOURCES ../apps/fetch/Fetch.java
//SOURCES ../apps/fetch/FetchTest.java
//SOURCES ../apps/install-native/InstallNative.java
//SOURCES ../apps/install-native/InstallNativeTest.java
//SOURCES ../apps/jellyfin-backup/JellyfinBackup.java
//SOURCES ../apps/jellyfin-backup/JellyfinBackupTest.java
//SOURCES ../apps/jwt/Jwt.java
//SOURCES ../apps/jwt/JwtTest.java
//SOURCES ../apps/killport/Killport.java
//SOURCES ../apps/killport/KillportTest.java
//SOURCES ../apps/nudge/Nudge.java
//SOURCES ../apps/nudge/NudgeTest.java
//SOURCES ../apps/reach/Reach.java
//SOURCES ../apps/reach/ReachTest.java
//SOURCES ../apps/serve/Serve.java
//SOURCES ../apps/serve/ServeTest.java
//SOURCES ../apps/slowfetch/Slowfetch.java
//SOURCES ../apps/slowfetch/SlowfetchTest.java
//SOURCES ../apps/typeit/Typeit.java
//SOURCES ../apps/typeit/TypeitTest.java

package tests;

import digest.DigestTest;
import fetch.FetchTest;
import installnative.InstallNativeTest;
import java.io.PrintWriter;
import java.util.List;
import jellyfinbackup.JellyfinBackupTest;
import jwt.JwtTest;
import killport.KillportTest;
import nudge.NudgeTest;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import reach.ReachTest;
import serve.ServeTest;
import slowfetch.SlowfetchTest;
import typeit.TypeitTest;

/// Centralized test suite runner executing all catalog application unit and integration tests.
public class RunAllTests {

  private static final List<Class<?>> TEST_CLASSES = List.of(DigestTest.class, FetchTest.class,
      InstallNativeTest.class, JellyfinBackupTest.class, JwtTest.class, KillportTest.class,
      NudgeTest.class, ReachTest.class, ServeTest.class, SlowfetchTest.class, TypeitTest.class);

  public static void main(String... args) {
    System.out.println("===============================================================");
    System.out.println("  jbang-catalog Test Suite Runner");
    System.out.printf("  Executing tests across %d applications%n", TEST_CLASSES.size());
    System.out.println("===============================================================");

    var launcher = LauncherFactory.create();
    var summaryListener = new SummaryGeneratingListener();

    var requestBuilder = LauncherDiscoveryRequestBuilder.request();
    for (var testClass : TEST_CLASSES) {
      requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
    }

    long startNs = System.nanoTime();
    launcher.execute(requestBuilder.build(), summaryListener);
    long durationMs = (System.nanoTime() - startNs) / 1_000_000;

    var summary = summaryListener.getSummary();

    System.out.println("---------------------------------------------------------------");
    System.out.printf("Finished test run in %d ms%n", durationMs);
    System.out.printf(
        "Tests found:     %d%n" + "Tests started:   %d%n" + "Tests succeeded: %d%n"
            + "Tests failed:    %d%n" + "Tests skipped:   %d%n",
        summary.getTestsFoundCount(), summary.getTestsStartedCount(),
        summary.getTestsSucceededCount(), summary.getTestsFailedCount(),
        summary.getTestsSkippedCount());
    System.out.println("===============================================================");

    if (summary.getTestsFailedCount() > 0 || summary.getContainersFailedCount() > 0) {
      System.err.println("\nFailures details:");
      summary.printFailuresTo(new PrintWriter(System.err));
      System.exit(1);
    }

    System.out.println("All test suites passed successfully.");
    System.exit(0);
  }
}
