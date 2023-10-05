package sims.michael.gitkspr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.ProcessExecutor
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals

class CliTest {

    private val logger = LoggerFactory.getLogger(CliTest::class.java)

    @TestFactory
    fun `github info is correctly inferred from remote name and URI`(): List<DynamicTest> {
        fun test(name: String, remoteUri: String, remoteName: String, expected: GitHubInfo) = dynamicTest(name) {
            val (repoDir, homeDir) = createScratchDirs()
            try {
                val expectedConfig = Config(repoDir, remoteName, expected)
                val actual = getEffectiveConfigFromCli(
                    repoDir,
                    homeDir,
                    remoteUri,
                    remoteName,
                    homeDirConfig = mapOf("remote-name" to remoteName),
                )
                assertEquals(ComparableConfig(expectedConfig), ComparableConfig(actual))
            } finally {
                repoDir.delete()
                homeDir.delete()
            }
        }
        return listOf(
            test("from origin", "git@github.com:owner/name.git", "origin", GitHubInfo("github.com", "owner", "name")),
            test("from other", "git@other.com:owner/name.git", "other", GitHubInfo("other.com", "owner", "name")),
        )
    }

    @Test
    fun `test config happy path`() {
        val (repoDir, homeDir) = createScratchDirs()
        val expected = Config(
            workingDirectory = repoDir,
            remoteName = DEFAULT_REMOTE_NAME,
            gitHubInfo = GitHubInfo(
                host = "github.com",
                owner = "SomeOwner",
                name = "some-repo-name",
            ),
        )
        val actual = getEffectiveConfigFromCli(
            repoDir,
            homeDir,
            remoteUri = "git@github.com:SomeOwner/some-repo-name.git",
            remoteName = expected.remoteName,
        )
        assertEquals(ComparableConfig(expected), ComparableConfig(actual))
    }

    @Test
    fun `gitHubInfo can be partially explicit and partially implicit`() {
        val (repoDir, homeDir) = createScratchDirs()
        // This will come from the configuration, the rest will be inferred by the URI
        val explicitlyConfiguredHost = "example.com"
        val expected = Config(
            workingDirectory = repoDir,
            remoteName = DEFAULT_REMOTE_NAME,
            gitHubInfo = GitHubInfo(
                host = explicitlyConfiguredHost,
                owner = "SomeOwner",
                name = "some-repo-name",
            ),
        )
        val actual = getEffectiveConfigFromCli(
            repoDir,
            homeDir,
            remoteUri = "git@github.com:SomeOwner/some-repo-name.git",
            remoteName = expected.remoteName,
            homeDirConfig = mapOf("github-host" to explicitlyConfiguredHost),
        )
        assertEquals(ComparableConfig(expected), ComparableConfig(actual))
    }

    @Test
    fun `configuration priority is as expected`() {
        // CLI takes precedence over repo dir config file which takes precedence over home dir config file
        val (repoDir, homeDir) = createScratchDirs()
        val expected = Config(
            workingDirectory = repoDir,
            remoteName = DEFAULT_REMOTE_NAME,
            gitHubInfo = GitHubInfo(
                host = "hostFromHomeDir",
                owner = "ownerFromRepoDir",
                name = "nameFromCli",
            ),
        )
        val actual = getEffectiveConfigFromCli(
            repoDir,
            homeDir,
            remoteUri = "git@example.com:SomeOwner/some-repo-name.git",
            remoteName = expected.remoteName,
            homeDirConfig = mapOf(
                "github-host" to "hostFromHomeDir",
                "repo-owner" to "ownerFromHomeDir",
                "repo-name" to "nameFromHomeDir",
            ),
            repoDirConfig = mapOf(
                "repo-owner" to "ownerFromRepoDir",
                "repo-name" to "nameFromRepoDir",
            ),
            extraCliArgs = listOf(
                "--repo-name",
                "nameFromCli",
            ),
        )
        assertEquals(ComparableConfig(expected), ComparableConfig(actual))
    }

    private fun createScratchDirs(): Pair<File, File> {
        val dir = checkNotNull(Files.createTempDirectory(CliTest::class.java.simpleName).toFile()).canonicalFile
        logger.info("Temp dir created in {}", dir.toStringWithClickableURI())
        val repo = dir.resolve("repo")
        val home = dir.resolve("home")
        return repo to home
    }
}

private fun getEffectiveConfigFromCli(
    repoDir: File,
    homeDir: File,
    remoteUri: String,
    remoteName: String,
    extraCliArgs: List<String> = emptyList(),
    homeDirConfig: Map<String, String> = emptyMap(),
    repoDirConfig: Map<String, String> = emptyMap(),
): Config {
    check(homeDir.mkdir())
    homeDir.writeConfigFile(mapOf("github-token" to "REQUIRED_BY_CLI_BUT_UNUSED_IN_THESE_TESTS") + homeDirConfig)

    repoDir.initGitDirWithRemoteUri(remoteUri, remoteName)
    repoDir.writeConfigFile(repoDirConfig)

    val processResult = ProcessExecutor()
        .environment("HOME", homeDir.absolutePath)
        .command(getInvokeCliList(repoDir) + listOf("status", "--show-config") + extraCliArgs + remoteName)
        .readOutput(true)
        .execute()

    val outputString = processResult.outputString()
    check(processResult.exitValue == 0) {
        "Process exit value was ${processResult.exitValue}, output was $outputString"
    }

    return Json.decodeFromString(outputString)
}

private fun File.writeConfigFile(config: Map<String, String>) {
    require(config.keys.none { it.startsWith("--") }) {
        "Keys should not begin with `--`"
    }
    resolve(CONFIG_FILE_NAME).writer().use { writer ->
        Properties().apply { putAll(config) }.store(writer, null)
    }
}

private fun File.initGitDirWithRemoteUri(uriString: String, remoteName: String = "origin") {
    Git.init().setDirectory(this).call().use { git ->
        git.remoteAdd().setName(remoteName).setUri(URIish(uriString)).call()
    }
}

private fun getInvokeCliList(workingDir: File = findNearestGitDir()): List<String> {
    // Use the same JDK we were invoked with, if we can determine it. Else fall back to whatever "java" is in our $PATH
    val javaBinary = System.getProperty("java.home")
        ?.let { javaHome -> "$javaHome/bin/java" }
        ?.takeIf { javaHomeBinary -> File(javaHomeBinary).exists() }
        ?: "java"
    return listOf(
        javaBinary,
        "-D${WORKING_DIR_PROPERTY_NAME}=${workingDir.absolutePath}",
        "-cp",
        System.getProperty("java.class.path"),
    ) + Cli::class.java.name
}

private fun findNearestGitDir(): File {
    val initialDir = File(".").canonicalFile
    var currentPath = initialDir
    val parentFiles = generateSequence { currentPath.also { currentPath = currentPath.parentFile } }
    return checkNotNull(parentFiles.firstOrNull { it.resolve(".git").isDirectory }) {
        "Can't find a git dir in $initialDir or any of its parent directories"
    }
}

private val json = Json {
    prettyPrint = true
}

data class ComparableConfig(val config: Config) {
    override fun toString(): String = json.encodeToString(config)
}
