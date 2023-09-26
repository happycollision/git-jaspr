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
    fun `push new commits`(testInfo: TestInfo) {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val git = JGitClient(gitDir).clone(REPO_URI)
        fun addCommit() {
            val testName = testInfo.displayName.substringBefore("(")
            val testFileName = "${testName.sanitize()}-${generateUuid()}.txt"
            gitDir.resolve(testFileName).writeText("This is a test file.\n")
            git.add(testFileName).commit(testFileName)
        }

        addCommit()
        addCommit()
        addCommit()

        System.setProperty(WORKING_DIR_PROPERTY_NAME, gitDir.absolutePath)
        push()
    }

    @Test
    fun `amend HEAD commit and re-push`(testInfo: TestInfo) {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val git = JGitClient(gitDir).clone(REPO_URI)
        fun addCommit() {
            val testName = testInfo.displayName.substringBefore("(")
            val testFileName = "${testName.sanitize()}-${generateUuid()}.txt"
            gitDir.resolve(testFileName).writeText("This is a test file.\n")
            git.add(testFileName).commit(testFileName)
        }

        addCommit()
        addCommit()
        addCommit()

        System.setProperty(WORKING_DIR_PROPERTY_NAME, gitDir.absolutePath)
        push()

        val first = gitDir.walkTopDown().maxDepth(1).filter { it.name.endsWith(".txt") }.first()
        first.appendText("An amendment.\n")
        val headCommit = git.log("HEAD", 1).single()
        git.add(first.relativeTo(gitDir).name).commitAmend("I amended this\n\n${COMMIT_ID_LABEL}: ${headCommit.id}")

        push()
    }

    @Test
    fun `reorder, drop, add, and re-push`(testInfo: TestInfo) {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val git = JGitClient(gitDir).clone(REPO_URI)
        fun addCommit(commitLabel: String) {
            val testName = testInfo.displayName.substringBefore("(")
            val testFileName = "${testName.sanitize()}-$commitLabel.txt"
            gitDir.resolve(testFileName).writeText("$commitLabel\n")
            git.add(testFileName).commit(commitLabel)
        }

        addCommit("A")
        addCommit("B")
        addCommit("C")
        addCommit("D")
        addCommit("E")

        System.setProperty(WORKING_DIR_PROPERTY_NAME, gitDir.absolutePath)
        push()

        val stack = git.getLocalCommitStack("origin", "HEAD", "main")
        val a = stack.first { it.shortMessage == "A" }
        val b = stack.first { it.shortMessage == "B" }
        val c = stack.first { it.shortMessage == "C" }
        val d = stack.first { it.shortMessage == "D" }
        val e = stack.first { it.shortMessage == "E" }

        git.reset("${a.hash}^")
        git.cherryPick(e)
        git.cherryPick(c)
        addCommit("1")
        git.cherryPick(b)
        git.cherryPick(a)
        addCommit("2")

        push()
    }

    @Test
    fun `DELETE ALL KSPR BRANCHES (DANGEROUS)`() {
        val gitDir = createTempDir().resolve(REPO_NAME)
        logger.info("{}", gitDir.toStringWithClickableURI())

        val git = JGitClient(gitDir).clone(REPO_URI)
        val regex = ".*(${REMOTE_BRANCH_PREFIX}.*?)$".toRegex()

        val refSpecs = git
            .getRemoteBranches()
            .mapNotNull(regex::matchEntire)
            .map { it.groupValues[1] }
            .map { remoteBranch -> RefSpec("+", remoteBranch) }
        git.push(refSpecs)
    }

    /** main -> [Push.run] -> [GitKspr.push] */
    private fun push() = main(arrayOf("push"))

    private fun createTempDir() = checkNotNull(Files.createTempDirectory(GitKsprTest::class.java.simpleName).toFile())
}

private val filenameSafeRegex = "\\W+".toRegex()
private fun String.sanitize() = replace(filenameSafeRegex, "_")

private const val REPO_NAME = "git-spr-demo"
private const val REPO_URI = "git@github.com:MichaelSims/${REPO_NAME}.git"
