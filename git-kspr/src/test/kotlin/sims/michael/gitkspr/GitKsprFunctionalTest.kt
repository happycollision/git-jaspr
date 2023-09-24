package sims.michael.gitkspr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.slf4j.LoggerFactory
import sims.michael.gitkspr.Cli.main
import sims.michael.gitkspr.testing.FunctionalTest
import sims.michael.gitkspr.testing.toStringWithClickableURI
import java.nio.file.Files

/**
 * Functional test which reads the actual git configuration and interacts with GitHub.
 */
@FunctionalTest
class GitKsprFunctionalTest {

    private val logger = LoggerFactory.getLogger(GitKsprTest::class.java)

    @Test
    fun `push one new commit`(testInfo: TestInfo) {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val git = JGitClient(gitDir).clone(REPO_URI)
        val testName = testInfo.displayName.substringBefore("(")
        val testFileName = "$testName-${generateUuid()}.txt"
        gitDir.resolve(testFileName).writeText("This is a test file.\n")
        git.add(testFileName).commit(testFileName)

        System.setProperty(WORKING_DIR_PROPERTY_NAME, gitDir.absolutePath)
        push()
    }

    /** main -> [Push.run] -> [GitKspr.push] */
    private fun push() = main(arrayOf("push"))

    private fun createTempDir() = checkNotNull(Files.createTempDirectory(GitKsprTest::class.java.simpleName).toFile())
}

private const val REPO_NAME = "git-spr-demo"
private const val REPO_URI = "git@github.com:MichaelSims/${REPO_NAME}.git"
