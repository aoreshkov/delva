package app.oreshkov.clikit

import app.oreshkov.clikit.testing.GoldenFiles
import java.nio.file.Path

/**
 * This module's golden files. Regenerate with `./gradlew :cli-kit:jvmTest -Ddelva.updateGolden=true`,
 * then read the diff.
 */
internal val Golden = GoldenFiles(Path.of("src", "jvmTest", "resources", "golden"), "delva.updateGolden")
